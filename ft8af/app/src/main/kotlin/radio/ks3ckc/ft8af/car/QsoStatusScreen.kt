package radio.ks3ckc.ft8af.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.ForegroundCarColorSpan
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
import radio.ks3ckc.ft8af.rota.RotaTripManager
import radio.ks3ckc.ft8af.ui.components.slotTimerState

/**
 * The main Android Auto screen: a read-only pane mirroring the live QSO
 * sequence (headline, current TX message, sequence step, slot countdown,
 * band/mode). All decisions live in [buildCarQsoStatus]; this class only
 * observes the engine's LiveData and renders.
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

    private val tick = object : Runnable {
        override fun run() {
            maybeAttach()
            val vm = attachedVm
            if (vm != null) {
                val slotMillis = currentSlotMillis(vm)
                val seconds = slotTimerState(UtcTimer.getSystemTime(), slotMillis).secondsRemaining
                if (shouldInvalidateForTick(lastRenderedSecond, seconds)) {
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

        val target = toCallsign?.callsign?.takeIf { it.isNotEmpty() && it != "CQ" }
        // Status row (state + green countdown, target + yellow SNR + TX-queue state).
        val statusRow = carStatusDashRow(
            txState = status.txState,
            secondsRemaining = slot.secondsRemaining,
            target = target,
            snrLabel = status.snrLabel,
            txMessage = status.messageLine,
            hunting = GeneralVariables.autoFollowCQ && !GeneralVariables.huntCallsCQ,
        )
        // Band row: "20m" badge + "14.074 MHz · FT8" + per-cycle decode count.
        // currentMessages is the label overlay (refreshed each cycle, cleared on a
        // silent slot, so it drops to 0), not the cross-cycle mutableFt8MessageList.
        val bandRow = carBandDashRow(
            freqHz = GeneralVariables.band,
            bandName = currentBandName(),
            modeName = mode.displayName,
            decodeCount = vm.currentMessages?.size ?: 0,
        )
        // Activation rows while a POTA/ROTA is live, otherwise a session-summary row.
        // Plain StateFlow reads are enough for freshness — the 1 Hz tick re-renders.
        val pota = PotaSessionManager.currentActivation.value
        val rota = RotaTripManager.state.value
        val potaRow = carPotaDashRow(pota?.parkRefsDisplay, pota?.qsoCount ?: 0)
        val rotaRow = carRotaDashRow(rota.active, rota.tripName, rota.sentQsos + rota.pendingQsos, rota.miles)
        val sessionRow = carSessionDashRow(
            sessionQsoCount = GeneralVariables.QSL_Callsign_list_today.size,
            // Last *logged* callsign (worked-list is appended in completion order),
            // not the current TX target — those differ when calling CQ right after a
            // QSO completes, and the target would then hide a real "last logged" line.
            lastQsoCallsign = GeneralVariables.QSL_Callsign_list.lastOrNull(),
            lastQsoBandName = currentBandName(),
            lastQsoMinutesAgo = minutesAgo(UtcTimer.getSystemTime(), ts.mutableQsoCompletedAt.value),
        )

        val dashRows = buildCarDashboardRows(statusRow, bandRow, potaRow, rotaRow, sessionRow)
        val builtRows = dashRows.map { renderDashRow(it) }
        val pane = Pane.Builder().apply {
            selectCarPaneRows(dashRows.map { it.priority }, paneRowLimit(carContext))
                .forEach { addRow(builtRows[it]) }
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

    /**
     * A dashboard row → a Pane Row with its colored leading badge. The Car App
     * Library only permits [ForegroundCarColorSpan] on a row's secondary text, not
     * its title (Row.setTitle validates against a no-color constraint and throws),
     * so the title is rendered as plain text and color emphasis lives on the
     * secondary line.
     */
    private fun renderDashRow(row: CarDashRow): Row =
        Row.Builder()
            .setImage(badgeIcon(row.badge))
            .setTitle(row.title.joinToString("") { it.text })
            .apply { row.secondary?.let { addText(carText(it)) } }
            .build()

    /** Concatenate [spans] into a CarText, applying a [ForegroundCarColorSpan] over each colored run. */
    private fun carText(spans: List<CarSpan>): CarText {
        val sb = SpannableStringBuilder()
        for (span in spans) {
            val start = sb.length
            sb.append(span.text)
            span.color?.let {
                sb.setSpan(
                    ForegroundCarColorSpan.create(spanColor(it)),
                    start,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return CarText.create(sb)
    }

    private fun spanColor(color: CarSpanColor): CarColor = when (color) {
        CarSpanColor.GREEN -> CarColor.GREEN
        CarSpanColor.YELLOW -> CarColor.YELLOW
        CarSpanColor.BLUE -> CarColor.BLUE
    }

    /**
     * A [CarBadge] rendered as a colored circle with its glyph centered (an empty
     * glyph becomes a filled dot — the status indicator). Cached by badge so the
     * 1 Hz re-render doesn't re-rasterize the same icon every second.
     */
    private fun badgeIcon(badge: CarBadge): CarIcon = badgeCache.getOrPut(badge) {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        canvas.drawCircle(center, center, center, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badge.bgArgb })
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badge.fgArgb }
        if (badge.text.isEmpty()) {
            canvas.drawCircle(center, center, size * 0.2f, fg)
        } else {
            fg.textAlign = Paint.Align.CENTER
            fg.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fg.textSize = if (badge.text.length <= 1) size * 0.5f else size * 0.32f
            val fm = fg.fontMetrics
            canvas.drawText(badge.text, center, center - (fm.ascent + fm.descent) / 2f, fg)
        }
        CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    private val badgeCache = HashMap<CarBadge, CarIcon>()

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
