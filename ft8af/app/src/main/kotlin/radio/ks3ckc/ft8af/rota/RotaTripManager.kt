package radio.ks3ckc.ft8af.rota

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.log.QSLRecord
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import radio.ks3ckc.ft8af.ui.decode.UsStateLookup
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the trip UI (and the service notification) renders. */
data class RotaTripState(
    val active: Boolean = false,
    val tripId: String = "",
    val tripName: String = "",
    val startedMs: Long = 0L,
    val shareToken: String = "",
    /** True while the trip exists only on the phone — created out of coverage. */
    val pendingCreate: Boolean = false,
    val pendingPoints: Int = 0,
    val pendingQsos: Int = 0,
    val sentPoints: Int = 0,
    val sentQsos: Int = 0,
    val miles: Double = 0.0,
    val lastFixMs: Long = 0L,
    val lastUploadMs: Long = 0L,
    val uploading: Boolean = false,
    /** Which SmartBeaconing rule kept the most recent route point. */
    val lastBeaconReason: BeaconReason? = null,
    /** True while the sampler considers the rover stopped (no points are recorded). */
    val parked: Boolean = false,
    /** Last upload failure, cleared by the next success. Shown verbatim in the UI. */
    val lastError: String? = null,
)

/**
 * Trip mode: streams the rover's route and contacts to roadsontheair.com while driving.
 *
 * Responsibilities, in the order they matter on the road:
 *  1. Record. Every accepted GPS breadcrumb and every logged QSO lands in
 *     [RotaQueue] (on disk) before anything is attempted over the network.
 *  2. Deliver. A single-flight flush drains the queue in batches whenever there
 *     is something to send, backing off on failure and flushing immediately when
 *     connectivity returns.
 *  3. Survive. Trip identity lives in [RotaSettings], so a reboot or a killed
 *     process resumes the same trip rather than orphaning it.
 *
 * A trip can be started *and* ended with no coverage at all: creation and
 * completion are both deferred flags that the flush loop resolves later.
 *
 * The object is a singleton because its two producers — the location tracker and
 * the QSO save path deep in DatabaseOpr — have no shared owner to hang it off.
 */
object RotaTripManager {
    private const val TAG = "RotaTripManager"
    private const val QUEUE_FILE = "rota_queue.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushLock = Mutex()

    private val _state = MutableStateFlow(RotaTripState())
    val state: StateFlow<RotaTripState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var queue: RotaQueue? = null

    /** Names the road for each breadcrumb; null until [init] has a context. */
    @Volatile
    private var highwayResolver: HighwayResolver? = null

    private var sampler = SmartBeaconSampler()

    /**
     * The freshest fix seen, kept or not. QSOs are stamped from this rather than
     * from the last *beacon*: on an interstate the last kept point can be half a
     * minute and half a mile back, and a contact belongs where the rover actually
     * was when it happened.
     */
    @Volatile
    private var lastRawFix: TripPoint? = null

    /**
     * The freshest GPS fix this trip has seen, or null when no trip is running.
     *
     * Exposed for [radio.ks3ckc.ft8af.location.RoverPosition], which stamps every
     * logged QSO with a position whether or not a trip is running — while a trip *is*
     * running, this is by far the best source available, since it is a live fix the
     * app is already paying for.
     */
    fun latestFix(): TripPoint? = if (RotaSettings.hasActiveTrip) lastRawFix else null

    /** Consecutive failed flushes — drives [rotaBackoffMs]. */
    @Volatile
    private var failedAttempts = 0

    /**
     * Set when the trip in hand is one this process did not start — resumed from
     * disk, or adopted after a 409 — and cleared once the server has told us what
     * it already holds. A trip this process started knows exactly what it has
     * sent and skips the request. See [needsResumeHandshake].
     */
    @Volatile
    private var resumeHandshakePending = false

    @Volatile
    private var retryJob: Job? = null

    private var connectivityRegistered = false

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Coming out of a dead zone is the single best moment to flush.
                requestFlush("network-available")
            }
        }

    /**
     * Bind to the application context and restore any interrupted trip. Safe to
     * call more than once; called from the activity once the app is up.
     */
    fun init(context: Context) {
        val ctx = context.applicationContext
        if (appContext == null) {
            appContext = ctx
            queue = RotaQueue(File(ctx.filesDir, QUEUE_FILE)).also { it.load() }
            highwayResolver = HighwayResolver(ctx)
        }
        registerConnectivity(ctx)
        restore()
    }

    /**
     * Re-impose the trip's CQ modifier once the persisted config has loaded.
     *
     * Separate from [init] on purpose. `init` runs early in activity startup,
     * while `DatabaseOpr` assigns `GeneralVariables.toModifier` from the stored
     * config row later — so applying at init time would both bank the wrong
     * "operator's preference" and then be overwritten by the config load anyway.
     * The caller invokes this from the config-loaded callback instead.
     */
    fun onConfigLoaded() {
        if (!RotaSettings.enabled || !RotaSettings.hasActiveTrip) return
        RotaCqSession.apply()
    }

    /** Re-derive UI state from what was persisted, and resume delivery. */
    private fun restore() {
        if (!RotaSettings.enabled) return
        if (!RotaSettings.hasActiveTrip) {
            publish()
            return
        }
        // A resumed trip starts the sampler fresh: the first fix after a restart
        // becomes a FIRST beacon, which is right — the gap while the app was dead
        // is real, and pretending to continue from a stale point would draw a
        // straight line across wherever the phone actually went.
        sampler = SmartBeaconSampler()
        highwayResolver?.reset()
        lastRawFix = null
        // Ask the server what it has before re-sending a queue we can't account for.
        resumeHandshakePending = RotaSettings.tripId.isNotEmpty()
        _state.value =
            _state.value.copy(
                active = true,
                tripId = RotaSettings.tripId,
                tripName = RotaSettings.tripName,
                startedMs = RotaSettings.tripStartedMs,
                shareToken = RotaSettings.tripShareToken,
                pendingCreate = RotaSettings.tripPendingCreate,
            )
        publish()
        log(
            "restored trip id=${RotaSettings.tripId.ifEmpty { "(pending)" }} " +
                "queued=${queue?.pointCount() ?: 0}pts/${queue?.qsoCount() ?: 0}qsos",
        )
        startTracking()
        requestFlush("restore")
    }

    // -----------------------------------------------------------------------
    // Trip lifecycle
    // -----------------------------------------------------------------------

    /**
     * Begin a trip. Returns immediately — creation on the server happens in the
     * background and is retried until it lands, so the rover can pull out of a
     * driveway with no signal and still have a complete route.
     */
    fun startTrip(
        name: String,
        privacy: String?,
        notes: String? = null,
        planId: String? = null,
    ) {
        if (RotaSettings.hasActiveTrip) {
            log("startTrip ignored — a trip is already running")
            return
        }
        val startedMs = System.currentTimeMillis()
        RotaSettings.tripName = name.trim().ifEmpty { defaultTripName(startedMs) }
        RotaSettings.tripStartedMs = startedMs
        RotaSettings.tripPrivacy = privacy.orEmpty()
        RotaSettings.tripPlanId = planId.orEmpty()
        RotaSettings.tripPendingCreate = true
        RotaSettings.tripPendingComplete = false
        RotaSettings.tripId = ""
        RotaSettings.tripShareToken = ""

        sampler = SmartBeaconSampler()
        highwayResolver?.reset()
        lastRawFix = null
        // A trip this process started has nothing to reconcile — it knows what it sent.
        resumeHandshakePending = false
        queue?.clear()
        _state.value =
            RotaTripState(
                active = true,
                tripName = RotaSettings.tripName,
                startedMs = startedMs,
                pendingCreate = true,
            )
        log("startTrip '${RotaSettings.tripName}' privacy=${privacy ?: "(default)"}")
        // From here every generated CQ goes out as "CQ ROTA <call> <grid>".
        RotaCqSession.apply()
        startTracking()
        // The trip notes ride along with the deferred create.
        pendingNotes = notes
        requestFlush("start-trip")
    }

    @Volatile
    private var pendingNotes: String? = null

    /**
     * End the trip: stop tracking, flush what's left, then finalize on the
     * server. Out of coverage this leaves a "complete when possible" flag, and
     * the flush loop finishes the job once the phone is back online.
     */
    fun endTrip() {
        if (!RotaSettings.hasActiveTrip) return
        // Pin the last known position before tracking stops, so the route ends
        // where the trip ended rather than at the last beacon behind it.
        lastRawFix?.let { fix -> sampler.anchorForQso(fix)?.let { recordPoint(it) } }
        log(
            "endTrip '${RotaSettings.tripName}' queued=${queue?.pointCount() ?: 0}pts/" +
                "${queue?.qsoCount() ?: 0}qsos",
        )
        RotaSettings.tripPendingComplete = true
        // Released now, not when the server finally acks the completion: out of
        // coverage that ack can be hours away, and the operator is off the road
        // and off the trip the moment they say so.
        RotaCqSession.release()
        stopTracking()
        requestFlush("end-trip")
    }

    /**
     * Give up on a trip: drop the local queue and forget it — the end-of-trip
     * ADIF upload from the logbook remains the way to publish its contacts.
     *
     * The server row is still completed, best-effort. Discarding used to be
     * local-only, which stranded a created trip as `active` forever: the site
     * called the rover live for a day and badged the trip active after that,
     * with no client left that could end it. One fire-and-forget attempt is
     * deliberate — for the failure cases this button also serves (bad key,
     * deleted trip) the call fails and the row was unreachable anyway.
     */
    fun abandonTrip() {
        // Snapshot before clearTrip wipes them; the coroutine below outlives it.
        val tripId = RotaSettings.tripId
        val pendingCreate = RotaSettings.tripPendingCreate
        val baseUrl = RotaSettings.baseUrl
        val apiKey = RotaSettings.apiKey
        RotaCqSession.release()
        stopTracking()
        queue?.clear()
        RotaSettings.clearTrip()
        sampler.reset()
        lastRawFix = null
        resumeHandshakePending = false
        _state.value = RotaTripState()
        log("abandonTrip — local queue dropped")
        if (shouldCompleteAbandonedTrip(tripId, pendingCreate, apiKey)) {
            scope.launch {
                RotaClient.completeTrip(baseUrl, apiKey, tripId)
                    .onSuccess { log("abandonTrip — server trip $tripId completed") }
                    .onFailure { log("abandonTrip — server complete failed (${it.message}); row may stay active") }
            }
        }
    }

    private fun defaultTripName(startedMs: Long): String {
        val call = RotaSettings.callsign.ifBlank { GeneralVariables.myCallsign.orEmpty() }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedMs))
        return listOf(call, day).filter { it.isNotBlank() }.joinToString(" ")
    }

    // -----------------------------------------------------------------------
    // Producers
    // -----------------------------------------------------------------------

    /**
     * A GPS fix from [RotaLocationTracker], arriving about once a second.
     * [SmartBeaconSampler] decides which ones become route points; the rest still
     * update [lastRawFix] so a QSO can be placed precisely.
     */
    fun onLocationFix(location: Location) {
        if (!RotaSettings.hasActiveTrip) return
        val state = stateForLatLon(location.latitude, location.longitude)
        val candidate =
            TripPoint(
                timestampMs = if (location.time > 0) location.time else System.currentTimeMillis(),
                latitude = location.latitude,
                longitude = location.longitude,
                // Android reports m/s; the API wants mph.
                speedMph = if (location.hasSpeed()) location.speed * MPS_TO_MPH else null,
                headingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                state = state,
                // Answers from cache and refreshes in the background — never blocks
                // this callback, and yields null rather than a guess when offline.
                highway = highwayResolver?.labelFor(location.latitude, location.longitude, state),
            )
        lastRawFix = candidate

        val decision =
            sampler.offer(candidate) ?: run {
                // Not a beacon — but a long stretch of not-a-beacon is how the sampler
                // learns the rover has parked, so let it see the fix either way.
                sampler.noteStationary(candidate)
                val wasParked = _state.value.parked
                _state.value =
                    _state.value.copy(
                        lastFixMs = candidate.timestampMs,
                        parked = sampler.isParked,
                    )
                // The notification says "parked", and this is the only path that can
                // ever set it: once parked, no fix is kept, so recordPoint's publish()
                // never runs and the notification would claim the rover is still
                // rolling for as long as it sits there. Republish on the transition
                // only — every fix would rebuild the notification once a second for
                // the whole trip, since updateNotification does no throttling of its own.
                if (sampler.isParked != wasParked) publish()
                return
            }
        recordPoint(decision)
        requestFlush("point")
    }

    /** Queue a kept breadcrumb and reflect it in the UI/notification. */
    private fun recordPoint(decision: BeaconDecision) {
        queue?.addPoint(decision.point)
        _state.value =
            _state.value.copy(
                lastFixMs = decision.point.timestampMs,
                miles = sampler.traveledMeters / METERS_PER_MILE,
                lastBeaconReason = decision.reason,
                parked = sampler.isParked,
            )
        publish()
    }

    /**
     * A QSO was just written to the log. Called from `DatabaseOpr.doInsertQSLData`
     * for on-air and manually-logged contacts (bulk ADIF imports are excluded by
     * the caller — a whole imported logbook is not part of today's drive).
     *
     * Two things happen, and the second is the reason the map looks right: the
     * contact is stamped with the *current* position ([lastRawFix]), and that
     * position is also forced into the route as a breadcrumb. Without the anchor,
     * a QSO logged mid-interstate-leg would be plotted up to half a mile off the
     * drawn line — the line only has vertices where SmartBeaconing put them.
     * With it, every contact sits exactly on the route it was made from.
     *
     * Never throws: this runs inside the app's log-write path, where an exception
     * would cost the operator the QSO itself.
     */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught") // see the "never throws" note above
    fun onQsoLogged(record: QSLRecord?) {
        try {
            if (record == null || !RotaSettings.enabled || !RotaSettings.hasActiveTrip) return
            val here = lastRawFix ?: sampler.lastAccepted
            // The record was stamped with a position moments ago by the QSO save path
            // (RoverPosition), which runs whether or not a trip is active. Preferring it
            // guarantees the live copy of this contact and the one in the end-of-trip
            // ADIF report the identical coordinate — they dedupe into one row server-side,
            // and two spellings of "where it happened" would be settled by arrival order.
            val qso =
                RotaQsoMapper.tripQsoFromRecord(
                    record = record,
                    roverLat = record.myLat ?: here?.latitude,
                    roverLon = record.myLon ?: here?.longitude,
                    state = here?.state,
                ) ?: return
            queue?.addQso(qso)
            // Pin the route to where the contact happened.
            here?.let { fix -> sampler.anchorForQso(fix)?.let { recordPoint(it) } }
            publish()
            requestFlush("qso")
            log("queued QSO ${qso.callsign} ${qso.band ?: "?"} ${qso.mode ?: "?"}")
        } catch (e: Exception) {
            log("onQsoLogged failed: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
        }
    }

    /**
     * Coarse U.S. state for a position, via the same 4-character-grid table the
     * decode list uses for Worked All States. A grid square straddles state lines
     * near a border, so this is a label for "which states did this trip touch",
     * not a legal determination — good enough for the trip's states-visited roll-up.
     */
    private fun stateForLatLon(
        lat: Double,
        lon: Double,
    ): String? {
        val ctx = appContext ?: return null
        return try {
            val grid = MaidenheadGrid.getGridSquare(LatLng(lat, lon))
            UsStateLookup.stateFromGrid(ctx, grid)
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Delivery
    // -----------------------------------------------------------------------

    /** Ask for a flush. Cheap and safe to call from any thread, as often as you like. */
    fun requestFlush(reason: String) {
        if (!RotaSettings.enabled || !RotaSettings.isConfigured) return
        scope.launch { flush(reason) }
    }

    /**
     * Drain the queue. Serialized by [flushLock] so a burst of triggers (a QSO
     * logged the same second a network appears) collapses into one pass.
     */
    private suspend fun flush(reason: String) {
        val q = queue ?: return
        if (!RotaSettings.hasActiveTrip) return
        val apiKey = RotaSettings.apiKey
        if (apiKey.isBlank()) return

        flushLock.withLock {
            val baseUrl = RotaSettings.baseUrl
            _state.value = _state.value.copy(uploading = true)

            // 1. The trip may not exist server-side yet (started out of coverage).
            //
            //    An announced trip already exists — it is sitting at `planned` —
            //    so it is *started*, not created. Creating would leave the
            //    announcement stranded with a duplicate beside it and lose the
            //    privacy the plan wizard chose.
            if (RotaSettings.tripPendingCreate) {
                val planId = RotaSettings.tripPlanId
                val landed =
                    if (planId.isNotEmpty()) {
                        RotaClient.startPlannedTrip(
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            tripId = planId,
                            startTimeMs = RotaSettings.tripStartedMs,
                        )
                    } else {
                        RotaClient.createTrip(
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            name = RotaSettings.tripName,
                            startTimeMs = RotaSettings.tripStartedMs,
                            notes = pendingNotes,
                            privacy = RotaSettings.tripPrivacy.takeIf { it.isNotBlank() },
                        )
                    }
                landed.fold(
                    onSuccess = { handle ->
                        RotaSettings.tripId = handle.id
                        RotaSettings.tripShareToken = handle.shareToken.orEmpty()
                        RotaSettings.tripPendingCreate = false
                        pendingNotes = null
                        _state.value =
                            _state.value.copy(
                                tripId = handle.id,
                                shareToken = handle.shareToken.orEmpty(),
                                pendingCreate = false,
                            )
                        log(if (planId.isNotEmpty()) "plan started id=${handle.id}" else "trip created id=${handle.id}")
                    },
                    onFailure = { e ->
                        when (classifyPlanStartFailure(e, planId)) {
                            // 409: the plan is no longer `planned`, so a previous
                            // attempt landed (or another device started it). The
                            // row is the trip; adopt it, and arm the resume
                            // handshake below — an adopted row is one this
                            // process did not start, so what it already holds is
                            // exactly as unknown as a trip resumed from disk.
                            PlanStartOutcome.ALREADY_STARTED -> {
                                RotaSettings.tripId = planId
                                RotaSettings.tripPendingCreate = false
                                pendingNotes = null
                                resumeHandshakePending = needsResumeHandshake(PlanStartOutcome.ALREADY_STARTED)
                                _state.value = _state.value.copy(tripId = planId, pendingCreate = false)
                                log("plan already started id=$planId — adopting it")
                            }
                            // 404: the plan was cancelled on the site while the
                            // phone was out of coverage. Fall back to an ordinary
                            // trip rather than stranding a drive that happened.
                            PlanStartOutcome.PLAN_GONE -> {
                                RotaSettings.tripPlanId = ""
                                log("plan $planId is gone — creating a plain trip on the next flush")
                                requestFlush("plan-gone")
                                return@withLock
                            }
                            PlanStartOutcome.RETRY -> {
                                failed(e, if (planId.isNotEmpty()) "start" else "create")
                                return@withLock
                            }
                        }
                    },
                )
            }

            val tripId = RotaSettings.tripId
            if (tripId.isEmpty()) {
                _state.value = _state.value.copy(uploading = false)
                return@withLock
            }

            // 2. Resume handshake, once per app start on an already-created trip.
            //    A process killed 300 miles in comes back holding a queue it can't
            //    tell apart from what already landed; one cheap GET turns "re-send
            //    the whole day" into "send the last few minutes". Best-effort by
            //    design: a failure here is not a delivery failure, so it must not
            //    trip the backoff — the plain re-send below still works, the server
            //    dedupes, and the only cost is bytes.
            if (resumeHandshakePending) {
                RotaClient.fetchSyncState(baseUrl, apiKey, tripId).fold(
                    onSuccess = { sync ->
                        resumeHandshakePending = false
                        val pruned = q.pruneAcknowledgedQsos(sync.qsoDedupeKeys)
                        log(
                            "sync-state: server has ${sync.pointCount}pts/${sync.qsoCount}qsos " +
                                "status=${sync.status}${if (sync.truncated) " (keys truncated)" else ""}; " +
                                "pruned $pruned queued QSO(s)",
                        )
                        publish()
                    },
                    onFailure = { e ->
                        // Leave the flag set so the next pass tries again.
                        log("sync-state unavailable (continuing): ${e.message ?: e.javaClass.simpleName}")
                    },
                )
            }

            // 3. Ship the backlog, oldest first, one batch per request.
            while (!q.isEmpty()) {
                val batch = q.peekBatch()
                if (batch.isEmpty) break
                val result = RotaClient.sendLive(baseUrl, apiKey, tripId, batch)
                result.fold(
                    onSuccess = { ack ->
                        q.commit(batch)
                        failedAttempts = 0
                        _state.value =
                            _state.value.copy(
                                sentPoints = _state.value.sentPoints + batch.points.size,
                                sentQsos = _state.value.sentQsos + ack.qsosInserted,
                                lastUploadMs = System.currentTimeMillis(),
                                lastError = null,
                            )
                        publish()
                    },
                    onFailure = { e ->
                        failed(e, "live")
                        return@withLock
                    },
                )
            }

            // 4. Finalize once nothing is left to send.
            if (RotaSettings.tripPendingComplete && q.isEmpty()) {
                RotaClient.completeTrip(baseUrl, apiKey, tripId).fold(
                    onSuccess = {
                        log("trip completed id=$tripId")
                        RotaSettings.clearTrip()
                        sampler.reset()
                        lastRawFix = null
                        _state.value = RotaTripState()
                    },
                    onFailure = { e ->
                        failed(e, "complete")
                        return@withLock
                    },
                )
            }

            _state.value = _state.value.copy(uploading = false)
            publish()
            if (reason != "point") log("flush ok ($reason)")
        }
    }

    /**
     * Record a failed step and, when it's the kind of failure that can heal
     * itself, schedule a retry. A permanent failure (401 from a rotated key, 403
     * on someone else's trip) just surfaces in the UI: the queue keeps everything,
     * so fixing the key and flushing again loses nothing.
     */
    private fun failed(
        error: Throwable,
        step: String,
    ) {
        val retryable = isRetryableRotaFailure(error)
        val message =
            when (error) {
                is RotaHttpException -> error.serverMessage ?: "HTTP ${error.httpCode}"
                else -> error.message ?: error.javaClass.simpleName
            }
        _state.value = _state.value.copy(uploading = false, lastError = message)
        publish()
        log("$step failed (${if (retryable) "retrying" else "fatal"}): $message")
        if (!retryable) {
            failedAttempts = 0
            return
        }
        failedAttempts++
        scheduleRetry(rotaBackoffMs(failedAttempts))
    }

    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob =
            scope.launch {
                delay(delayMs)
                flush("retry")
            }
    }

    // -----------------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun registerConnectivity(ctx: Context) {
        if (connectivityRegistered) return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // VALIDATED, not merely INTERNET: an unvalidated network (captive portal,
        // still associating) would just burn a flush pass. Same reasoning as
        // QsoAutoSync.
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
            connectivityRegistered = true
        } catch (e: RuntimeException) {
            // SecurityException on a locked-down device, or the platform's
            // too-many-callbacks limit. Trip mode still works — flushes just fall
            // back to the QSO/point triggers and the retry timer.
            log("connectivity register failed: ${e.javaClass.simpleName}")
        }
    }

    private fun startTracking() {
        val ctx = appContext ?: return
        RotaTripService.start(ctx)
    }

    private fun stopTracking() {
        val ctx = appContext ?: return
        RotaTripService.stop(ctx)
    }

    /** Refresh the queue-derived counters and push them to the notification. */
    private fun publish() {
        val q = queue
        _state.value =
            _state.value.copy(
                pendingPoints = q?.pointCount() ?: 0,
                pendingQsos = q?.qsoCount() ?: 0,
            )
        appContext?.let { RotaTripService.updateNotification(it, _state.value) }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = appContext ?: GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts Rota: $msg\n") }
        } catch (_: Exception) {
        }
    }
}

/**
 * Whether abandoning a trip should still complete it server-side. Only when a
 * server row actually exists to complete: a trip still pending creation has no
 * id (and nothing to strand), and without an API key the call cannot succeed —
 * skipping it keeps the abandon path free of a doomed network attempt.
 */
internal fun shouldCompleteAbandonedTrip(
    tripId: String,
    pendingCreate: Boolean,
    apiKey: String,
): Boolean = tripId.isNotEmpty() && !pendingCreate && apiKey.isNotBlank()
