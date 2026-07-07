package radio.ks3ckc.ft8af.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.ModeProfile
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import com.k1af.ft8af.rigs.BaseRigOperation
import com.k1af.ft8af.timer.UtcTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.pskreporter.PskReporterClient
import radio.ks3ckc.ft8af.pskreporter.WhoHeardMeCache
import radio.ks3ckc.ft8af.ui.components.slotTimerState
import radio.ks3ckc.ft8af.ui.map.StateLabel
import radio.ks3ckc.ft8af.ui.map.UsStateLabels
import radio.ks3ckc.ft8af.ui.map.UsStateLocator
import radio.ks3ckc.ft8af.ui.map.UsStateOutlines
import radio.ks3ckc.ft8af.ui.map.WorldCountryOutlines
import radio.ks3ckc.ft8af.ui.map.WorldOutlines

/**
 * The main Android Auto screen: mirrors the live QSO sequence (headline,
 * current TX message, sequence step, slot countdown, band/mode, POTA status).
 *
 * On car-app API level >= [SURFACE_MIN_API] the screen renders via a [Surface]
 * using [CarMapSurfaceRenderer]: a full-bleed world map with decoded-station
 * markers, an operator→partner line, and a semi-transparent overlay carrying the
 * status text. Because the app is a NAVIGATION-category car app, this map stays
 * visible while the vehicle is moving (the point of mobile FT8) — POI/IOT map
 * surfaces would be blanked or unavailable while driving. The 1 Hz tick redraws
 * the surface directly, so countdown updates never trigger a template refresh
 * and the host never dims/flashes the display.
 *
 * On older hosts the screen falls back to the original [PaneTemplate] approach,
 * which still causes per-second dimming but keeps the app functional.
 *
 * The engine is never started from here: until ComposeMainActivity has created
 * the [MainViewModel] singleton, [MainViewModel.peekInstance] is null and the
 * screen shows an "open the app on your phone" message, re-checking on its
 * 1 Hz tick.
 */
class QsoStatusScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var attachedVm: MainViewModel? = null
    private var lastRenderedSecond = -1

    /** True when the host supports NavigationTemplate + surface rendering. */
    private val useSurface: Boolean =
        carContext.carAppApiLevel >= SURFACE_MIN_API

    private val renderer: CarMapSurfaceRenderer? = if (useSurface) CarMapSurfaceRenderer() else null

    /** Cached geo assets — loaded once, then reused every frame. */
    private var landRings: List<FloatArray>? = null
    private var countryRings: List<FloatArray>? = null
    private var stateRings: List<FloatArray>? = null
    private var stateLabels: List<StateLabel>? = null

    private val surfaceCallback = if (useSurface) object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val surface = container.surface ?: return
            renderer?.onSurfaceAvailable(surface, container.width, container.height)
            // Draw the first frame immediately so the surface isn't blank.
            drawSurfaceFrame()
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            renderer?.onSurfaceDestroyed()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            renderer?.onVisibleAreaChanged(visibleArea)
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            renderer?.onStableAreaChanged(stableArea)
        }
    } else null

    private val tick = object : Runnable {
        override fun run() {
            maybeAttach()
            val vm = attachedVm
            if (vm != null) {
                if (useSurface) {
                    // Surface path: redraw directly — no template refresh, no dimming.
                    drawSurfaceFrame()
                } else {
                    // Fallback PaneTemplate path: only invalidate when the second changes.
                    val slotMillis = currentSlotMillis(vm)
                    val seconds = slotTimerState(UtcTimer.getSystemTime(), slotMillis).secondsRemaining
                    if (shouldInvalidateForTick(lastRenderedSecond, seconds)) {
                        invalidate()
                    }
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    /** Scope for the PSKReporter "who heard me" fetch loop; alive between start/stop. */
    private var pskScope: CoroutineScope? = null

    override fun onStart(owner: LifecycleOwner) {
        if (useSurface && surfaceCallback != null) {
            carContext.getCarService(AppManager::class.java)
                .setSurfaceCallback(surfaceCallback)
        }
        handler.post(tick)
        startPskFetchLoop()
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
        pskScope?.cancel()
        pskScope = null
    }

    /**
     * Periodically fetches "who heard me" spots for the operator's callsign and
     * publishes them to [WhoHeardMeCache], which [buildSurfaceState] reads into the
     * map's hollow PSK rings. [PskReporterClient] enforces its own cooldown and
     * rate-limit back-off, so polling on a fixed interval is safe.
     */
    private fun startPskFetchLoop() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        pskScope = scope
        scope.launch {
            while (isActive) {
                val call = GeneralVariables.myCallsign
                if (!call.isNullOrBlank()) {
                    val spots = try {
                        PskReporterClient.fetchSpotsForMe(call, PSK_SECONDS_BACK, byReceiver = false)
                    } catch (_: Exception) {
                        null
                    }
                    if (spots != null) {
                        WhoHeardMeCache.spots = spots
                        if (useSurface) drawSurfaceFrame() else invalidate()
                    }
                }
                delay(PSK_FETCH_INTERVAL_MS)
            }
        }
    }

    /**
     * Attach LiveData observers once the engine singleton exists. Observers use
     * the Screen's own lifecycle, so they detach automatically on destroy, and
     * registration itself delivers the current values (triggering a render).
     *
     * On the surface path observers call [drawSurfaceFrame] instead of
     * [invalidate], so structural LiveData changes redraw without a template
     * refresh. We still call [invalidate] when the engine first appears so the
     * template transitions from the "open phone" message to the map template.
     */
    private fun maybeAttach() {
        if (attachedVm != null) return
        val vm = MainViewModel.peekInstance() ?: return
        attachedVm = vm
        // The engine just appeared — force a template switch.
        invalidate()
        val onChange = Observer<Any?> {
            if (useSurface) drawSurfaceFrame() else invalidate()
        }
        vm.ft8TransmitSignal.mutableToCallsign.observe(this, onChange)
        vm.ft8TransmitSignal.mutableFunctionOrder.observe(this, onChange)
        vm.ft8TransmitSignal.mutableIsActivated.observe(this, onChange)
        vm.ft8TransmitSignal.mutableIsTransmitting.observe(this, onChange)
        vm.ft8TransmitSignal.mutableTransmittingMessage.observe(this, onChange)
        vm.ft8TransmitSignal.mutableSequential.observe(this, onChange)
        vm.mutableOperatingMode.observe(this, onChange)
        vm.mutableFt8MessageList.observe(this, onChange)
        GeneralVariables.mutableBandChange.observe(this, onChange)
    }

    // -----------------------------------------------------------------------
    // Template
    // -----------------------------------------------------------------------

    override fun onGetTemplate(): Template {
        val vm = MainViewModel.peekInstance() ?: return openPhoneTemplate(carContext)
        return if (useSurface) buildMapTemplate() else buildPaneTemplate(vm)
    }

    /**
     * API level >= 5: NavigationTemplate — a full-screen map surface with no host
     * content card, so the status text is drawn directly on the map behind a
     * translucent glass panel (see [CarMapSurfaceRenderer]) and the map stays
     * visible through/around it. NavigationTemplate (not MapWithContentTemplate,
     * whose card is opaque and host-styled) is what makes the transparent overlay
     * possible.
     *
     * NavigationTemplate mandates an action strip, so we keep a single "Decodes"
     * action (top-right, host-drawn). Our status banners are drawn on the surface and
     * left-aligned, so they don't collide with it.
     */
    private fun buildMapTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(decodesActionStrip())
            .build()

    /** API level < 5 fallback: the original PaneTemplate with per-second invalidate. */
    private fun buildPaneTemplate(vm: MainViewModel): Template {
        lastRenderedSecond = slotTimerState(UtcTimer.getSystemTime(), currentSlotMillis(vm)).secondsRemaining
        return PaneTemplate.Builder(buildStatusPane(vm))
            .setTitle(carContext.getString(R.string.car_screen_title))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(decodesActionStrip())
            .build()
    }

    /** The status card shown by both the map template (as content) and the fallback. */
    private fun buildStatusPane(vm: MainViewModel): Pane {
        val ts = vm.ft8TransmitSignal
        val mode = ModeProfile.fromId(vm.mutableOperatingMode.value ?: GeneralVariables.operatingMode)
        val slot = slotTimerState(UtcTimer.getSystemTime(), mode.slotMillis.toLong())

        val toCallsign = ts.mutableToCallsign.value
        val status = buildCarQsoStatus(
            isActivated = ts.mutableIsActivated.value ?: false,
            isTransmitting = ts.mutableIsTransmitting.value ?: false,
            functionOrder = ts.mutableFunctionOrder.value ?: TX_FUNCTION_COUNT,
            toCallsign = toCallsign?.callsign,
            snr = toCallsign?.snr?.takeIf { it != Ft8Message.SNR_UNKNOWN },
            transmittingMessage = ts.mutableTransmittingMessage.value,
            myTxSequential = ts.mutableSequential.value ?: ts.sequential,
            currentSlot = slot.currentSlot,
            secondsRemaining = slot.secondsRemaining,
            freqHz = GeneralVariables.band,
            bandName = currentBandName(),
            modeName = mode.displayName,
            huntEnabled = GeneralVariables.autoFollowCQ,
            huntCallsCQ = GeneralVariables.huntCallsCQ,
        )

        val headline = resolve(status.headline) +
            (status.snrLabel?.let { " · $it" } ?: "")
        val rows = mutableListOf(
            Row.Builder()
                .setTitle(headline)
                .addText(status.messageLine ?: carContext.getString(R.string.car_no_tx))
                .build(),
            Row.Builder()
                .setTitle(status.seqLine?.let { resolve(it) } ?: resolve(status.slotLine))
                .apply { if (status.seqLine != null) addText(resolve(status.slotLine)) }
                .build(),
            Row.Builder().setTitle(status.bandLine).build(),
        )
        // POTA activation line (only when running an activation)
        val activation = PotaSessionManager.currentActivation.value
        buildCarPotaLine(activation?.parkRefsDisplay, activation?.qsoCount)?.let {
            rows.add(Row.Builder().setTitle(resolve(it)).build())
        }
        return Pane.Builder().apply {
            rows.take(paneRowLimit(carContext)).forEach { addRow(it) }
        }.build()
    }

    // -----------------------------------------------------------------------
    // Surface rendering
    // -----------------------------------------------------------------------

    /**
     * Builds a [CarSurfaceState] snapshot from the engine's current LiveData
     * values and hands it to the renderer. Called from the 1 Hz tick and from
     * LiveData observers — always on the main thread.
     */
    private fun drawSurfaceFrame() {
        val r = renderer ?: return
        val vm = attachedVm ?: return
        val state = buildSurfaceState(vm)
        // Cache the result of each load — success OR the empty fallback — so a
        // missing/corrupt asset doesn't re-throw the parse on every frame.
        val rings = landRings ?: (try {
            WorldOutlines.load(carContext)
        } catch (_: Exception) {
            emptyList()
        }).also { landRings = it }
        val countries = countryRings ?: (try {
            WorldCountryOutlines.load(carContext)
        } catch (_: Exception) {
            emptyList()
        }).also { countryRings = it }
        val states = stateRings ?: (try {
            UsStateOutlines.load(carContext)
        } catch (_: Exception) {
            emptyList()
        }).also { stateRings = it }
        val labels = stateLabels ?: (try {
            UsStateLabels.load(carContext)
        } catch (_: Exception) {
            emptyList()
        }).also { stateLabels = it }
        val (statusInset, navInset) = systemBarInsets()
        r.drawFrame(state, rings, countries, states, labels, statusInset, navInset)
    }

    /**
     * The car display's status-bar (top) and nav-bar (bottom) heights in pixels, so
     * the surface banners can sit flush against them. Read from the framework
     * dimen resources — the car surface isn't a normal window, so WindowManager
     * insets come back empty here, but status_bar_height / navigation_bar_height
     * resolve correctly. Cached after the first non-zero read; (0, 0) makes the
     * renderer fall back to the safe-area edges.
     */
    private fun systemBarInsets(): Pair<Int, Int> {
        cachedSystemBars?.let { return it }
        val res = carContext.resources
        fun dimen(name: String): Int {
            val id = res.getIdentifier(name, "dimen", "android")
            return if (id > 0) res.getDimensionPixelSize(id) else 0
        }
        val top = dimen("status_bar_height")
        val bottom = dimen("navigation_bar_height")
        return (top to bottom).also { if (top > 0 || bottom > 0) cachedSystemBars = it }
    }

    private var cachedSystemBars: Pair<Int, Int>? = null

    private fun buildSurfaceState(vm: MainViewModel): CarSurfaceState {
        val ts = vm.ft8TransmitSignal
        val mode = ModeProfile.fromId(vm.mutableOperatingMode.value ?: GeneralVariables.operatingMode)
        val slot = slotTimerState(UtcTimer.getSystemTime(), mode.slotMillis.toLong())
        val toCallsign = ts.mutableToCallsign.value
        val status = buildCarQsoStatus(
            isActivated = ts.mutableIsActivated.value ?: false,
            isTransmitting = ts.mutableIsTransmitting.value ?: false,
            functionOrder = ts.mutableFunctionOrder.value ?: TX_FUNCTION_COUNT,
            toCallsign = toCallsign?.callsign,
            snr = toCallsign?.snr?.takeIf { it != Ft8Message.SNR_UNKNOWN },
            transmittingMessage = ts.mutableTransmittingMessage.value,
            myTxSequential = ts.mutableSequential.value ?: ts.sequential,
            currentSlot = slot.currentSlot,
            secondsRemaining = slot.secondsRemaining,
            freqHz = GeneralVariables.band,
            bandName = currentBandName(),
            modeName = mode.displayName,
            huntEnabled = GeneralVariables.autoFollowCQ,
            huntCallsCQ = GeneralVariables.huntCallsCQ,
        )

        // Operator position from grid
        val myGrid = GeneralVariables.getMyMaidenheadGrid()
        val opLatLng = myGrid?.takeIf { it.isNotEmpty() }?.let { safeGrid(it) }

        // Station markers from decoded messages
        val messages = vm.mutableFt8MessageList.value.orEmpty()
        val stations = buildStationMarkers(messages)

        // Current QSO partner: draw a line from the operator to their grid.
        val partnerCall = toCallsign?.callsign
        // Match on the normalized callsign (getCallsignFrom strips the < > that a
        // hashed decode carries in the raw callsignFrom field).
        val partnerMsg = partnerCall?.let { call -> messages.firstOrNull { it.callsignFrom != null && it.getCallsignFrom() == call } }
        val partnerLatLng = partnerMsg?.maidenGrid?.let { grid -> safeGrid(grid) }
        val partnerLocation = resolvePartnerLocation(
            partnerCall,
            partnerMsg?.fromWhere,
            partnerLatLng?.latitude ?: Double.NaN,
            partnerLatLng?.longitude ?: Double.NaN,
        )

        val activation = PotaSessionManager.currentActivation.value
        return CarSurfaceState(
            opLat = opLatLng?.latitude ?: Double.NaN,
            opLon = opLatLng?.longitude ?: Double.NaN,
            partnerLat = partnerLatLng?.latitude ?: Double.NaN,
            partnerLon = partnerLatLng?.longitude ?: Double.NaN,
            stations = stations,
            pskStations = pskSpotsToMarkers(WhoHeardMeCache.spots),
            headlineText = resolve(status.headline) + (status.snrLabel?.let { " · $it" } ?: ""),
            slotText = resolve(status.slotLine) + (partnerLocation?.let { " · $it" } ?: ""),
            bandText = status.bandLine,
            potaText = buildCarPotaLine(activation?.parkRefsDisplay, activation?.qsoCount)?.let { resolve(it) },
        )
    }

    /**
     * The QSO partner's location for the status banner: their US state (via
     * point-in-polygon on their grid) when the partner is in the US, otherwise their
     * DXCC country (from the callsign database, falling back to the decoded message's
     * resolved country). Null when there's no partner or it can't be resolved.
     * Cached per (call, position) since it does a DB lookup + polygon test.
     */
    private fun resolvePartnerLocation(call: String?, fromWhere: String?, lat: Double, lon: Double): String? {
        if (call.isNullOrBlank()) return null
        val key = "$call|$lat|$lon"
        if (key == locCacheKey) return locCache
        val country = (GeneralVariables.callsignDatabase?.getCallInfo(call)?.CountryNameEn ?: fromWhere)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val loc = when {
            country == null -> null
            country.startsWith("United States", ignoreCase = true) || country.equals("USA", ignoreCase = true) ->
                (if (!lat.isNaN() && !lon.isNaN()) UsStateLocator.stateAt(carContext, lat, lon) else null) ?: country
            else -> country
        }
        locCacheKey = key
        locCache = loc
        return loc
    }

    private var locCacheKey: String? = null
    private var locCache: String? = null

    /**
     * Extracts station markers from the latest decode list, deduplicating by
     * callsign and skipping messages without a known grid. Mirrors the phone
     * MapScreen's marker-building logic but outputs [CarStationMarker] with
     * ARGB int colours instead of Compose [Color].
     */
    private fun buildStationMarkers(messages: List<Ft8Message>): List<CarStationMarker> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<CarStationMarker>()
        for (msg in messages) {
            // Normalize via getCallsignFrom() so hashed decodes (raw "<CALL>")
            // dedupe against their expanded form instead of adding a second marker.
            val call = msg.getCallsignFrom()
            if (call.isEmpty() || call in seen) continue
            seen.add(call)

            val grid = msg.maidenGrid ?: continue
            val latLng = safeGrid(grid) ?: continue

            val isToMe = GeneralVariables.checkIsMyCallsign(msg.callsignTo ?: "")
            val isWorked = msg.isQSL_Callsign
            // checkIsCQ() dereferences callsignTo without a null guard, so gate it.
            val isCQ = msg.callsignTo != null && msg.checkIsCQ()

            val colorInt = when {
                isToMe -> COLOR_SIGNAL
                isWorked -> COLOR_WORKED
                isCQ && !isWorked -> COLOR_ACCENT
                else -> COLOR_NEW
            }

            result.add(CarStationMarker(latLng.latitude, latLng.longitude, colorInt))
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun safeGrid(grid: String) = try {
        MaidenheadGrid.gridToLatLng(grid)
    } catch (_: Exception) {
        null
    }

    private fun decodesActionStrip(): ActionStrip =
        ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_decodes_action))
                    .setOnClickListener {
                        screenManager.push(RecentDecodesScreen(carContext))
                    }
                    .build(),
            )
            .build()

    private fun resolve(spec: CarStringSpec): String =
        carContext.getString(spec.resId, *spec.args.toTypedArray())

    private companion object {
        const val TICK_MS = 1000L

        /** How far back to query PSKReporter reception reports, and how often to poll. */
        const val PSK_SECONDS_BACK = 3600
        const val PSK_FETCH_INTERVAL_MS = 300_000L

        /** The surface templates require car-app API level 5. */
        val SURFACE_MIN_API = CarAppApiLevels.LEVEL_5

        // Station marker colours — dark-theme palette (ARGB ints).
        const val COLOR_SIGNAL  = 0xFF5CD6E8.toInt()   // Cyan (calling me)
        const val COLOR_WORKED  = 0xFF5CD6E8.toInt()   // Cyan (worked before)
        const val COLOR_ACCENT  = 0xFFFFAF5E.toInt()   // Orange (CQ, unworked)
        const val COLOR_NEW     = 0xFFC084FC.toInt()    // Purple (new)
    }
}

/** The slot length of the engine's current operating mode, in milliseconds. */
internal fun currentSlotMillis(vm: MainViewModel): Long =
    ModeProfile.fromId(vm.mutableOperatingMode.value ?: GeneralVariables.operatingMode)
        .slotMillis.toLong()

/**
 * The band name for the frequency pill, mirroring the fallback chain the phone
 * UI uses (FT8AFApp): tuned band-list entry, then a band-list match by exact
 * frequency, then the generic meters-from-frequency lookup.
 */
internal fun currentBandName(): String {
    val bandIndex = GeneralVariables.mutableBandChange.value ?: GeneralVariables.bandListIndex
    val freq = GeneralVariables.band
    return OperationBand.bandList.getOrNull(bandIndex)?.waveLength
        ?: OperationBand.bandList.firstOrNull { it.band == freq }?.waveLength
        ?: BaseRigOperation.getMeterFromFreq(freq)
        ?: ""
}

/** Shown while the engine singleton doesn't exist yet (phone app not opened). */
internal fun openPhoneTemplate(carContext: CarContext): MessageTemplate =
    MessageTemplate.Builder(carContext.getString(R.string.car_open_phone))
        .setTitle(carContext.getString(R.string.car_screen_title))
        .setHeaderAction(Action.APP_ICON)
        .build()

private const val DEFAULT_PANE_ROWS = 3

/**
 * The host's pane row limit. ConstraintManager needs car app API level 2+;
 * older hosts get the conservative default.
 */
internal fun paneRowLimit(carContext: CarContext): Int =
    if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
    } else {
        DEFAULT_PANE_ROWS
    }
