package radio.ks3ckc.ft8af.rtota

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
data class RtotaTripState(
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
 * Trip mode: streams the rover's route and contacts to rtota.app while driving.
 *
 * Responsibilities, in the order they matter on the road:
 *  1. Record. Every accepted GPS breadcrumb and every logged QSO lands in
 *     [RtotaQueue] (on disk) before anything is attempted over the network.
 *  2. Deliver. A single-flight flush drains the queue in batches whenever there
 *     is something to send, backing off on failure and flushing immediately when
 *     connectivity returns.
 *  3. Survive. Trip identity lives in [RtotaSettings], so a reboot or a killed
 *     process resumes the same trip rather than orphaning it.
 *
 * A trip can be started *and* ended with no coverage at all: creation and
 * completion are both deferred flags that the flush loop resolves later.
 *
 * The object is a singleton because its two producers — the location tracker and
 * the QSO save path deep in DatabaseOpr — have no shared owner to hang it off.
 */
object RtotaTripManager {
    private const val TAG = "RtotaTripManager"
    private const val QUEUE_FILE = "rtota_queue.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushLock = Mutex()

    private val _state = MutableStateFlow(RtotaTripState())
    val state: StateFlow<RtotaTripState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var queue: RtotaQueue? = null

    private var sampler = SmartBeaconSampler()

    /**
     * The freshest fix seen, kept or not. QSOs are stamped from this rather than
     * from the last *beacon*: on an interstate the last kept point can be half a
     * minute and half a mile back, and a contact belongs where the rover actually
     * was when it happened.
     */
    @Volatile
    private var lastRawFix: TripPoint? = null

    /** Consecutive failed flushes — drives [rtotaBackoffMs]. */
    @Volatile
    private var failedAttempts = 0

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
            queue = RtotaQueue(File(ctx.filesDir, QUEUE_FILE)).also { it.load() }
        }
        registerConnectivity(ctx)
        restore()
    }

    /** Re-derive UI state from what was persisted, and resume delivery. */
    private fun restore() {
        if (!RtotaSettings.enabled) return
        if (!RtotaSettings.hasActiveTrip) {
            publish()
            return
        }
        // A resumed trip starts the sampler fresh: the first fix after a restart
        // becomes a FIRST beacon, which is right — the gap while the app was dead
        // is real, and pretending to continue from a stale point would draw a
        // straight line across wherever the phone actually went.
        sampler = SmartBeaconSampler()
        lastRawFix = null
        _state.value =
            _state.value.copy(
                active = true,
                tripId = RtotaSettings.tripId,
                tripName = RtotaSettings.tripName,
                startedMs = RtotaSettings.tripStartedMs,
                shareToken = RtotaSettings.tripShareToken,
                pendingCreate = RtotaSettings.tripPendingCreate,
            )
        publish()
        log(
            "restored trip id=${RtotaSettings.tripId.ifEmpty { "(pending)" }} " +
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
    ) {
        if (RtotaSettings.hasActiveTrip) {
            log("startTrip ignored — a trip is already running")
            return
        }
        val startedMs = System.currentTimeMillis()
        RtotaSettings.tripName = name.trim().ifEmpty { defaultTripName(startedMs) }
        RtotaSettings.tripStartedMs = startedMs
        RtotaSettings.tripPrivacy = privacy.orEmpty()
        RtotaSettings.tripPendingCreate = true
        RtotaSettings.tripPendingComplete = false
        RtotaSettings.tripId = ""
        RtotaSettings.tripShareToken = ""

        sampler = SmartBeaconSampler()
        lastRawFix = null
        queue?.clear()
        _state.value =
            RtotaTripState(
                active = true,
                tripName = RtotaSettings.tripName,
                startedMs = startedMs,
                pendingCreate = true,
            )
        log("startTrip '${RtotaSettings.tripName}' privacy=${privacy ?: "(default)"}")
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
        if (!RtotaSettings.hasActiveTrip) return
        // Pin the last known position before tracking stops, so the route ends
        // where the trip ended rather than at the last beacon behind it.
        lastRawFix?.let { fix -> sampler.anchorForQso(fix)?.let { recordPoint(it) } }
        log(
            "endTrip '${RtotaSettings.tripName}' queued=${queue?.pointCount() ?: 0}pts/" +
                "${queue?.qsoCount() ?: 0}qsos",
        )
        RtotaSettings.tripPendingComplete = true
        stopTracking()
        requestFlush("end-trip")
    }

    /**
     * Give up on a trip that can't be delivered (bad key, deleted trip). Drops
     * the local queue and forgets the trip — the end-of-trip ADIF upload from the
     * logbook remains the way to publish it.
     */
    fun abandonTrip() {
        stopTracking()
        queue?.clear()
        RtotaSettings.clearTrip()
        sampler.reset()
        lastRawFix = null
        _state.value = RtotaTripState()
        log("abandonTrip — local queue dropped")
    }

    private fun defaultTripName(startedMs: Long): String {
        val call = RtotaSettings.callsign.ifBlank { GeneralVariables.myCallsign.orEmpty() }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startedMs))
        return listOf(call, day).filter { it.isNotBlank() }.joinToString(" ")
    }

    // -----------------------------------------------------------------------
    // Producers
    // -----------------------------------------------------------------------

    /**
     * A GPS fix from [RtotaLocationTracker], arriving about once a second.
     * [SmartBeaconSampler] decides which ones become route points; the rest still
     * update [lastRawFix] so a QSO can be placed precisely.
     */
    fun onLocationFix(location: Location) {
        if (!RtotaSettings.hasActiveTrip) return
        val candidate =
            TripPoint(
                timestampMs = if (location.time > 0) location.time else System.currentTimeMillis(),
                latitude = location.latitude,
                longitude = location.longitude,
                // Android reports m/s; the API wants mph.
                speedMph = if (location.hasSpeed()) location.speed * MPS_TO_MPH else null,
                headingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                state = stateForLatLon(location.latitude, location.longitude),
            )
        lastRawFix = candidate

        val decision =
            sampler.offer(candidate) ?: run {
                // Not a beacon — but a long stretch of not-a-beacon is how the sampler
                // learns the rover has parked, so let it see the fix either way.
                sampler.noteStationary(candidate)
                _state.value =
                    _state.value.copy(
                        lastFixMs = candidate.timestampMs,
                        parked = sampler.isParked,
                    )
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
            if (record == null || !RtotaSettings.enabled || !RtotaSettings.hasActiveTrip) return
            val here = lastRawFix ?: sampler.lastAccepted
            val qso =
                RtotaQsoMapper.tripQsoFromRecord(
                    record = record,
                    roverLat = here?.latitude,
                    roverLon = here?.longitude,
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
        if (!RtotaSettings.enabled || !RtotaSettings.isConfigured) return
        scope.launch { flush(reason) }
    }

    /**
     * Drain the queue. Serialized by [flushLock] so a burst of triggers (a QSO
     * logged the same second a network appears) collapses into one pass.
     */
    private suspend fun flush(reason: String) {
        val q = queue ?: return
        if (!RtotaSettings.hasActiveTrip) return
        val apiKey = RtotaSettings.apiKey
        if (apiKey.isBlank()) return

        flushLock.withLock {
            val baseUrl = RtotaSettings.baseUrl
            _state.value = _state.value.copy(uploading = true)

            // 1. The trip may not exist server-side yet (started out of coverage).
            if (RtotaSettings.tripPendingCreate) {
                val created =
                    RtotaClient.createTrip(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        name = RtotaSettings.tripName,
                        startTimeMs = RtotaSettings.tripStartedMs,
                        notes = pendingNotes,
                        privacy = RtotaSettings.tripPrivacy.takeIf { it.isNotBlank() },
                    )
                created.fold(
                    onSuccess = { handle ->
                        RtotaSettings.tripId = handle.id
                        RtotaSettings.tripShareToken = handle.shareToken.orEmpty()
                        RtotaSettings.tripPendingCreate = false
                        pendingNotes = null
                        _state.value =
                            _state.value.copy(
                                tripId = handle.id,
                                shareToken = handle.shareToken.orEmpty(),
                                pendingCreate = false,
                            )
                        log("trip created id=${handle.id}")
                    },
                    onFailure = { e ->
                        failed(e, "create")
                        return@withLock
                    },
                )
            }

            val tripId = RtotaSettings.tripId
            if (tripId.isEmpty()) {
                _state.value = _state.value.copy(uploading = false)
                return@withLock
            }

            // 2. Ship the backlog, oldest first, one batch per request.
            while (!q.isEmpty()) {
                val batch = q.peekBatch()
                if (batch.isEmpty) break
                val result = RtotaClient.sendLive(baseUrl, apiKey, tripId, batch)
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

            // 3. Finalize once nothing is left to send.
            if (RtotaSettings.tripPendingComplete && q.isEmpty()) {
                RtotaClient.completeTrip(baseUrl, apiKey, tripId).fold(
                    onSuccess = {
                        log("trip completed id=$tripId")
                        RtotaSettings.clearTrip()
                        sampler.reset()
                        lastRawFix = null
                        _state.value = RtotaTripState()
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
        val retryable = isRetryableRtotaFailure(error)
        val message =
            when (error) {
                is RtotaHttpException -> error.serverMessage ?: "HTTP ${error.httpCode}"
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
        scheduleRetry(rtotaBackoffMs(failedAttempts))
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
        RtotaTripService.start(ctx)
    }

    private fun stopTracking() {
        val ctx = appContext ?: return
        RtotaTripService.stop(ctx)
    }

    /** Refresh the queue-derived counters and push them to the notification. */
    private fun publish() {
        val q = queue
        _state.value =
            _state.value.copy(
                pendingPoints = q?.pointCount() ?: 0,
                pendingQsos = q?.qsoCount() ?: 0,
            )
        appContext?.let { RtotaTripService.updateNotification(it, _state.value) }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = appContext ?: GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts Rtota: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
