package radio.ks3ckc.ft8af.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.ModeProfile
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.rigs.BaseRigOperation
import com.k1af.ft8af.timer.UtcTimer
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.ui.components.slotTimerState

/**
 * The main Android Auto screen: a read-only pane mirroring the live QSO
 * sequence (headline, current TX message, sequence step, slot countdown,
 * band/mode, POTA activation status). All decisions live in
 * [buildCarQsoStatus]; this class only observes the engine's LiveData and
 * renders.
 *
 * The tick invalidates the template only at **slot boundaries** (every 15 s
 * for FT8, 7.5 s for FT4) rather than every second. This eliminates the
 * dimming/flashing the host caused when `invalidate()` rebuilt the
 * PaneTemplate once per second for the countdown. LiveData observers still
 * trigger an immediate refresh for real state changes (QSO progress, band
 * change, TX on/off).
 *
 * The engine is never started from here: until ComposeMainActivity has created
 * the [MainViewModel] singleton, [MainViewModel.peekInstance] is null and the
 * screen shows an "open the app on your phone" message, re-checking on its
 * 1 Hz tick.
 */
class QsoStatusScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private var attachedVm: MainViewModel? = null

    /** Tracks the slot index so the tick only invalidates at slot boundaries. */
    private var lastRenderedSlot = -1

    private val tick = object : Runnable {
        override fun run() {
            maybeAttach()
            val vm = attachedVm
            if (vm != null) {
                val slotMillis = currentSlotMillis(vm)
                val slot = slotTimerState(UtcTimer.getSystemTime(), slotMillis)
                // Only invalidate at slot boundaries — eliminates per-second dimming.
                if (slot.currentSlot != lastRenderedSlot) {
                    invalidate()
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.post(tick)
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
    }

    /**
     * Attach LiveData observers once the engine singleton exists. Observers use
     * the Screen's own lifecycle, so they detach automatically on destroy, and
     * registration itself delivers the current values (triggering a render).
     */
    private fun maybeAttach() {
        if (attachedVm != null) return
        val vm = MainViewModel.peekInstance() ?: return
        attachedVm = vm
        val onChange = Observer<Any?> { invalidate() }
        vm.ft8TransmitSignal.mutableToCallsign.observe(this, onChange)
        vm.ft8TransmitSignal.mutableFunctionOrder.observe(this, onChange)
        vm.ft8TransmitSignal.mutableIsActivated.observe(this, onChange)
        vm.ft8TransmitSignal.mutableIsTransmitting.observe(this, onChange)
        vm.ft8TransmitSignal.mutableTransmittingMessage.observe(this, onChange)
        vm.ft8TransmitSignal.mutableSequential.observe(this, onChange)
        vm.mutableOperatingMode.observe(this, onChange)
        GeneralVariables.mutableBandChange.observe(this, onChange)
    }

    override fun onGetTemplate(): Template {
        val vm = MainViewModel.peekInstance() ?: return openPhoneTemplate(carContext)
        val ts = vm.ft8TransmitSignal
        val mode = ModeProfile.fromId(vm.mutableOperatingMode.value ?: GeneralVariables.operatingMode)
        val slot = slotTimerState(UtcTimer.getSystemTime(), mode.slotMillis.toLong())
        lastRenderedSlot = slot.currentSlot

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
        val potaLine = buildCarPotaLine(activation?.parkRefsDisplay, activation?.qsoCount)
        if (potaLine != null) {
            rows.add(Row.Builder().setTitle(potaLine).build())
        }

        // QSO map image: operator position → partner station line
        val opGrid = GeneralVariables.getMyMaidenheadGrid()
        val partnerCall = toCallsign?.callsign
        val partnerGrid = partnerCall?.let { call ->
            vm.mutableFt8MessageList.value?.firstOrNull {
                it.callsignFrom == call
            }?.maidenGrid
        }
        val mapBitmap = CarQsoMapImage.render(carContext, opGrid, partnerCall, partnerGrid)
        val mapIcon = CarIcon.Builder(IconCompat.createWithBitmap(mapBitmap)).build()

        val pane = Pane.Builder().apply {
            rows.take(paneRowLimit(carContext)).forEach { addRow(it) }
            setImage(mapIcon)
        }.build()
        return PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.car_screen_title))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_decodes_action))
                            .setOnClickListener {
                                screenManager.push(RecentDecodesScreen(carContext))
                            }
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun resolve(spec: CarStringSpec): String =
        carContext.getString(spec.resId, *spec.args.toTypedArray())

    private companion object {
        const val TICK_MS = 1000L
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
