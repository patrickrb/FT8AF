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
import androidx.car.app.navigation.model.MapWithContentTemplate
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
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.ui.components.slotTimerState
import radio.ks3ckc.ft8af.ui.map.WorldOutlines

/**
 * The main Android Auto screen: mirrors the live QSO sequence (headline,
 * current TX message, sequence step, slot countdown, band/mode, POTA status).
 *
 * On car-app API level >= [SURFACE_MIN_API] the screen renders via a [Surface]
 * using [CarMapSurfaceRenderer]: a world map with station markers behind a
 * semi-transparent overlay with status text. The 1 Hz tick redraws the surface
 * directly, so countdown updates never trigger a template refresh and the host
 * never dims/flashes the display.
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

    /** True when the host supports MapWithContentTemplate + surface rendering. */
    private val useSurface: Boolean =
        carContext.carAppApiLevel >= SURFACE_MIN_API

    private val renderer: CarMapSurfaceRenderer? = if (useSurface) CarMapSurfaceRenderer() else null

    /** Cached land rings — loaded once, then reused every frame. */
    private var landRings: List<FloatArray>? = null

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

    override fun onStart(owner: LifecycleOwner) {
        if (useSurface && surfaceCallback != null) {
            carContext.getCarService(AppManager::class.java)
                .setSurfaceCallback(surfaceCallback)
        }
        handler.post(tick)
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
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
        GeneralVariables.mutableBandChange.observe(this, onChange)
    }

    // -----------------------------------------------------------------------
    // Template
    // -----------------------------------------------------------------------

    override fun onGetTemplate(): Template {
        val vm = MainViewModel.peekInstance() ?: return openPhoneTemplate(carContext)
        return if (useSurface) buildMapTemplate() else buildPaneTemplate(vm)
    }

    /** API level >= 7: MapWithContentTemplate — all visual content is on the surface. */
    private fun buildMapTemplate(): Template {
        return MapWithContentTemplate.Builder()
            .setActionStrip(decodesActionStrip())
            .build()
    }

    /** API level < 7 fallback: the original PaneTemplate with per-second invalidate. */
    private fun buildPaneTemplate(vm: MainViewModel): Template {
        val ts = vm.ft8TransmitSignal
        val mode = ModeProfile.fromId(vm.mutableOperatingMode.value ?: GeneralVariables.operatingMode)
        val slot = slotTimerState(UtcTimer.getSystemTime(), mode.slotMillis.toLong())
        lastRenderedSecond = slot.secondsRemaining

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
        val pane = Pane.Builder().apply {
            rows.take(paneRowLimit(carContext)).forEach { addRow(it) }
        }.build()
        return PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.car_screen_title))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(decodesActionStrip())
            .build()
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
        val rings = landRings ?: try {
            WorldOutlines.load(carContext).also { landRings = it }
        } catch (_: Exception) {
            emptyList()
        }
        r.drawFrame(state, rings)
    }

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
        val opLatLng = myGrid?.takeIf { it.isNotEmpty() }?.let {
            try { MaidenheadGrid.gridToLatLng(it) } catch (_: Exception) { null }
        }

        // Station markers from decoded messages
        val messages = vm.mutableFt8MessageList.value.orEmpty()
        val stations = buildStationMarkers(messages)

        // POTA activation
        val activation = PotaSessionManager.currentActivation.value
        val potaText = buildCarPotaLine(activation?.parkRefsDisplay, activation?.qsoCount)

        return CarSurfaceState(
            opLat = opLatLng?.latitude ?: Double.NaN,
            opLon = opLatLng?.longitude ?: Double.NaN,
            stations = stations,
            headlineText = resolve(status.headline) +
                (status.snrLabel?.let { " \u00B7 $it" } ?: ""),
            txMessageText = status.messageLine,
            seqText = status.seqLine?.let { resolve(it) },
            slotText = resolve(status.slotLine),
            bandText = status.bandLine,
            potaText = potaText,
            txState = status.txState,
        )
    }

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
            val call = msg.callsignFrom ?: continue
            if (call in seen) continue
            seen.add(call)

            val grid = msg.maidenGrid ?: continue
            val latLng = try {
                MaidenheadGrid.gridToLatLng(grid)
            } catch (_: Exception) { continue }
            if (latLng == null) continue

            val isToMe = GeneralVariables.checkIsMyCallsign(msg.callsignTo ?: "")
            val isWorked = msg.isQSL_Callsign
            val isCQ = msg.checkIsCQ()

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

        /** MapWithContentTemplate requires car-app API level 5. */
        const val SURFACE_MIN_API = CarAppApiLevels.LEVEL_5

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
