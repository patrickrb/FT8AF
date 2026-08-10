package radio.ks3ckc.ft8af.car

import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.ui.components.formatMhz

/**
 * A localizable string as (resource id, format args), resolved with
 * `carContext.getString(resId, *args)` at render time. Keeps this mapper free of
 * Android types so it stays plain-JVM unit-testable.
 */
internal data class CarStringSpec(val resId: Int, val args: List<Any> = emptyList())

internal enum class CarTxState { OFF, ARMED_RX, TRANSMITTING }

/** Everything the Android Auto status pane renders, pre-decided and testable. */
internal data class CarQsoStatus(
    /** "Calling CQ" / "QSOing with W1XYZ" / "Waiting for W1XYZ" / "Monitoring — TX off". */
    val headline: CarStringSpec,
    /** Target SNR ("-12 dB", ASCII hyphen), null when there is no target or its SNR is unknown. */
    val snrLabel: String?,
    val txState: CarTxState,
    /** The message text going out right now; null when not transmitting. */
    val messageLine: String?,
    /** "Step RR73 (4/6)"; null when TX is not activated. */
    val seqLine: CarStringSpec?,
    /** "TX slot · 7 s" or "RX slot · 7 s". */
    val slotLine: CarStringSpec,
    /** "14.074 MHz · 20m · FT8". */
    val bandLine: String,
)

/**
 * Maps the raw engine LiveData values to the car status pane. The target rule
 * (a real target is a non-empty callsign other than "CQ") and the headline
 * selection mirror ActiveQsoPanel's StationHeader so phone and car agree.
 */
internal fun buildCarQsoStatus(
    isActivated: Boolean,
    isTransmitting: Boolean,
    functionOrder: Int,
    toCallsign: String?,
    snr: Int?,
    transmittingMessage: String?,
    myTxSequential: Int,
    currentSlot: Int,
    secondsRemaining: Int,
    freqHz: Long,
    bandName: String,
    modeName: String,
    huntEnabled: Boolean = false,
    huntCallsCQ: Boolean = false,
): CarQsoStatus {
    val target = toCallsign?.takeIf { it.isNotEmpty() && it != "CQ" }
    val headline = when {
        isActivated && target == null && huntEnabled && !huntCallsCQ ->
            CarStringSpec(R.string.qsopanel_hunting)
        isActivated && target == null -> CarStringSpec(R.string.qsopanel_calling_cq)
        target == null -> CarStringSpec(R.string.car_monitoring)
        isTransmitting -> CarStringSpec(R.string.qsopanel_qsoing_with, listOf(target))
        else -> CarStringSpec(R.string.qsopanel_waiting_for, listOf(target))
    }
    val txState = when {
        isTransmitting -> CarTxState.TRANSMITTING
        isActivated -> CarTxState.ARMED_RX
        else -> CarTxState.OFF
    }
    val slotResId = if (currentSlot % 2 == myTxSequential) R.string.car_slot_tx else R.string.car_slot_rx
    return CarQsoStatus(
        headline = headline,
        snrLabel = if (target != null) formatSnrLabel(snr) else null,
        txState = txState,
        messageLine = transmittingMessage?.takeIf { isTransmitting && it.isNotEmpty() },
        seqLine = if (isActivated) {
            CarStringSpec(R.string.car_seq_step, listOf(txFunctionLabel(functionOrder), functionOrder, TX_FUNCTION_COUNT))
        } else {
            null
        },
        slotLine = CarStringSpec(slotResId, listOf(secondsRemaining)),
        bandLine = carBandLine(freqHz, bandName, modeName),
    )
}

/** The six TX sequence steps (functionOrder 1..6). */
internal const val TX_FUNCTION_COUNT = 6

/**
 * Short label for a TX sequence step, matching the phone UI's TxSelector chips
 * (that map is a local inside a private composable, so it is duplicated here).
 * Unknown orders fall back to "TX n" rather than crashing the car screen.
 */
internal fun txFunctionLabel(functionOrder: Int): String = when (functionOrder) {
    1 -> "GRID"
    2 -> "RPT"
    3 -> "R-RPT"
    4 -> "RR73"
    5 -> "73"
    6 -> "CQ"
    else -> "TX $functionOrder"
}

/** "14.074 MHz · 20m · FT8"; the band segment is omitted when [bandName] is blank. */
internal fun carBandLine(freqHz: Long, bandName: String, modeName: String): String = buildString {
    append(formatMhz(freqHz))
    append(" MHz")
    if (bandName.isNotBlank()) {
        append(" · ")
        append(bandName)
    }
    append(" · ")
    append(modeName)
}

/** "+3 dB" / "-12 dB" (ASCII hyphen from Int.toString); null in (SNR unknown) gives null out. */
internal fun formatSnrLabel(snr: Int?): String? = snr?.let { if (it > 0) "+$it dB" else "$it dB" }

// --- Pane row priorities ---------------------------------------------------
// Lower value = kept first when the host's pane row limit is tight. The
// POTA/ROTA activation rows outrank the band row on purpose: on a 3-row host an
// active activation replaces the band line rather than being silently dropped.
internal const val CAR_ROW_HEADLINE = 0
internal const val CAR_ROW_SEQ_SLOT = 1
internal const val CAR_ROW_ACTIVATION = 2
internal const val CAR_ROW_BAND = 3

/**
 * Picks which pane rows survive the host's row limit: keeps the [limit] rows
 * with the lowest priority values (ties keep the earlier row) and returns their
 * indices in build order.
 */
internal fun selectCarPaneRows(priorities: List<Int>, limit: Int): List<Int> {
    if (limit <= 0) return emptyList()
    if (priorities.size <= limit) return priorities.indices.toList()
    return priorities.withIndex()
        .sortedWith(compareBy({ it.value }, { it.index }))
        .take(limit)
        .map { it.index }
        .sorted()
}

// --- Design dashboard model (Pane template "1a") ---------------------------
// The status pane follows the FT8AF Android Auto design doc: every row carries a
// colored circular leading badge and its text uses colored emphasis spans. The
// decision/format/color logic is decided here (pure, unit-tested); QsoStatusScreen
// only renders the badges as bitmaps and the spans as CarText.

/** Standard car text-emphasis colors (host renders its own accessible shade). */
internal enum class CarSpanColor { GREEN, YELLOW, BLUE }

/** A run of row text, optionally emphasized with a [CarSpanColor]. */
internal data class CarSpan(val text: String, val color: CarSpanColor? = null)

/**
 * The colored leading badge on a row: a short glyph ("P", "R", "Σ", "20m", or ""
 * for a plain dot) drawn in [fgArgb] over a filled circle of [bgArgb].
 */
internal data class CarBadge(val text: String, val fgArgb: Int, val bgArgb: Int)

/** One dashboard row: leading [badge], [title] runs, optional [secondary] runs, and row [priority]. */
internal data class CarDashRow(
    val badge: CarBadge,
    val title: List<CarSpan>,
    val secondary: List<CarSpan>? = null,
    val priority: Int = CAR_ROW_ACTIVATION,
)

// Palette from the FT8AF Android Auto design doc (fg glyph / bg circle per role).
internal const val CAR_GREEN_FG = 0xFF81C995.toInt()
internal const val CAR_GREEN_BG = 0xFF0D2818.toInt()
internal const val CAR_BLUE_FG = 0xFF8AB4F8.toInt()
internal const val CAR_BLUE_BG = 0xFF1F2430.toInt()
internal const val CAR_AMBER_FG = 0xFFFFB45C.toInt()
internal const val CAR_AMBER_BG = 0xFF2B2114.toInt()
internal const val CAR_GRAY_FG = 0xFF9AA0A6.toInt()
internal const val CAR_GRAY_BG = 0xFF17181C.toInt()

/** "1 QSO" / "0 QSOs" / "3 QSOs" — singular only at exactly one. */
internal fun qsosLabel(count: Int): String = if (count == 1) "1 QSO" else "$count QSOs"

/** "1 decode" / "12 decodes" — singular only at exactly one. */
internal fun decodesLabel(count: Int): String = if (count == 1) "1 decode" else "$count decodes"

/**
 * QSOs a POTA activation needs before it counts under the POTA program rules.
 * There is no constant for this in the POTA session code (the phone UI never
 * shows a remaining-to-validate figure), so the well-known program rule lives
 * here where the car dashboard uses it.
 */
internal const val POTA_ACTIVATION_TARGET = 10

/** "0.0" / "12.3" — one decimal, locale-independent so tests are stable. */
internal fun formatMiles(miles: Double): String =
    String.format(java.util.Locale.US, "%.1f", miles)

/**
 * Whole minutes between [thenMs] and [nowMs] for the "last logged … N min" line.
 * Returns null when there is no timestamp (0/null) or the clock is skewed so [thenMs]
 * is in the future, so the session row degrades to "No QSOs logged yet" rather than
 * showing a nonsense figure.
 */
internal fun minutesAgo(nowMs: Long, thenMs: Long?): Int? {
    if (thenMs == null || thenMs <= 0L) return null
    val delta = nowMs - thenMs
    if (delta < 0L) return null
    return (delta / 60_000L).toInt()
}

/**
 * The status row: a green dot badge (gray when TX is off), a "<state> · <countdown>"
 * title (countdown green while armed), and a secondary that names the target with a
 * yellow SNR and the TX-queue state — "Waiting for W1ABC · −8 dB · no TX queued".
 */
internal fun carStatusDashRow(
    txState: CarTxState,
    secondsRemaining: Int,
    target: String?,
    snrLabel: String?,
    txMessage: String?,
    hunting: Boolean,
): CarDashRow {
    val (stateWord, countdown, armed) = when (txState) {
        CarTxState.TRANSMITTING -> Triple("Transmitting", " · $secondsRemaining s left", true)
        CarTxState.ARMED_RX -> Triple("Receiving", " · next TX in $secondsRemaining s", true)
        CarTxState.OFF -> Triple("Monitoring", " · TX off", false)
    }
    val title = listOf(CarSpan(stateWord), CarSpan(countdown, if (armed) CarSpanColor.GREEN else null))

    val lead = when {
        target != null && txState == CarTxState.TRANSMITTING -> "Working $target"
        target != null -> "Waiting for $target"
        txState == CarTxState.OFF -> "Monitoring — TX off"
        hunting -> "Hunting for CQ"
        else -> "Calling CQ"
    }
    val secondary = mutableListOf(CarSpan(lead))
    if (target != null && snrLabel != null) {
        secondary.add(CarSpan(" · "))
        secondary.add(CarSpan(snrLabel, CarSpanColor.YELLOW))
    }
    if (txState != CarTxState.OFF) {
        secondary.add(CarSpan(" · " + (txMessage?.takeIf { it.isNotEmpty() } ?: "no TX queued")))
    }
    val badge = if (armed) CarBadge("", CAR_GREEN_FG, CAR_GREEN_BG) else CarBadge("", CAR_GRAY_FG, CAR_GRAY_BG)
    return CarDashRow(badge, title, secondary, CAR_ROW_HEADLINE)
}

/**
 * The band row: a blue badge showing the band name ("20m"), a "14.074 MHz · FT8"
 * title, and a "N decodes last cycle" secondary (dropped when the last cycle was
 * silent, so the row shows the frequency alone rather than "0 decodes").
 */
internal fun carBandDashRow(freqHz: Long, bandName: String, modeName: String, decodeCount: Int): CarDashRow {
    val title = listOf(CarSpan("${formatMhz(freqHz)} MHz · $modeName"))
    val secondary = if (decodeCount > 0) listOf(CarSpan("${decodesLabel(decodeCount)} last cycle")) else null
    val badge = CarBadge(bandName.trim().ifEmpty { "RF" }, CAR_BLUE_FG, CAR_BLUE_BG)
    return CarDashRow(badge, title, secondary, CAR_ROW_BAND)
}

/**
 * The POTA row: amber "P" badge, "POTA <park> · N QSOs" title (green QSO count),
 * and a "N more to validate the activation" / "Activation validated" secondary
 * (POTA's [POTA_ACTIVATION_TARGET]-QSO program rule). Null when no activation runs.
 */
internal fun carPotaDashRow(parkRefsDisplay: String?, qsoCount: Int): CarDashRow? {
    if (parkRefsDisplay.isNullOrBlank()) return null
    val title = listOf(CarSpan("POTA $parkRefsDisplay"), CarSpan(" · ${qsosLabel(qsoCount)}", CarSpanColor.GREEN))
    val remaining = (POTA_ACTIVATION_TARGET - qsoCount).coerceAtLeast(0)
    val secondary = listOf(
        CarSpan(if (remaining > 0) "$remaining more to validate the activation" else "Activation validated"),
    )
    return CarDashRow(CarBadge("P", CAR_AMBER_FG, CAR_AMBER_BG), title, secondary, CAR_ROW_ACTIVATION)
}

/**
 * The ROTA row: amber "R" badge, "ROTA <trip> · N QSOs" title (QSOs = sent+pending
 * so out-of-coverage contacts still count), and a "X.X mi driven this activation"
 * secondary. Null when no trip is running or the trip has no name.
 */
internal fun carRotaDashRow(active: Boolean, tripName: String?, qsoCount: Int, miles: Double): CarDashRow? {
    if (!active || tripName.isNullOrBlank()) return null
    val title = listOf(CarSpan("ROTA $tripName · ${qsosLabel(qsoCount)}"))
    val secondary = listOf(CarSpan("${formatMiles(miles)} mi driven this activation"))
    return CarDashRow(CarBadge("R", CAR_AMBER_FG, CAR_AMBER_BG), title, secondary, CAR_ROW_ACTIVATION)
}

/**
 * The session-summary row shown when no POTA/ROTA activation is running (the design's
 * "activation rows drop out, session stats take the slot"): gray "Σ" badge,
 * "Session · N QSOs" title, and a "Last logged JA1XYZ · 20m · 41 min" secondary that
 * degrades to a band-less form, then to "No QSOs logged yet" when the last contact or
 * its timestamp is unknown.
 */
internal fun carSessionDashRow(
    sessionQsoCount: Int,
    lastQsoCallsign: String?,
    lastQsoBandName: String?,
    lastQsoMinutesAgo: Int?,
): CarDashRow {
    val title = listOf(CarSpan("Session · ${qsosLabel(sessionQsoCount)}"))
    val call = lastQsoCallsign?.takeIf { it.isNotBlank() }
    val secondary = if (call != null && lastQsoMinutesAgo != null) {
        val band = lastQsoBandName?.takeIf { it.isNotBlank() }
        if (band != null) {
            listOf(CarSpan("Last logged $call · $band · $lastQsoMinutesAgo min"))
        } else {
            listOf(CarSpan("Last logged $call · $lastQsoMinutesAgo min"))
        }
    } else {
        listOf(CarSpan("No QSOs logged yet"))
    }
    return CarDashRow(CarBadge("Σ", CAR_GRAY_FG, CAR_GRAY_BG), title, secondary, CAR_ROW_ACTIVATION)
}

/**
 * Assembles the pane in design order: status, band, then the POTA and/or ROTA
 * activation rows — or, when neither is active, the single [session] row in their
 * place. The Screen then applies [selectCarPaneRows] so the band row (not an
 * activation) is the first to drop on a row-limited host.
 */
internal fun buildCarDashboardRows(
    status: CarDashRow,
    band: CarDashRow,
    pota: CarDashRow?,
    rota: CarDashRow?,
    session: CarDashRow,
): List<CarDashRow> {
    val rows = mutableListOf(status, band)
    val activations = listOfNotNull(pota, rota)
    if (activations.isEmpty()) rows.add(session) else rows.addAll(activations)
    return rows
}

/** One row of the car's recent-decodes list. */
internal data class CarDecodeRow(val utcTimeMs: Long, val text: String, val snrLabel: String?)

/**
 * Rows for the recent-decodes ListTemplate: newest first, truncated to [maxRows]
 * (the host's content limit). Input triples are (utcTimeMs, messageText, snr-or-null)
 * so the mapper never touches Ft8Message — the Screen extracts the primitives.
 */
internal fun buildCarDecodeRows(
    decodes: List<Triple<Long, String, Int?>>,
    maxRows: Int,
): List<CarDecodeRow> =
    decodes
        .sortedByDescending { it.first }
        .take(maxRows.coerceAtLeast(0))
        .map { (utc, text, snr) -> CarDecodeRow(utc, text, formatSnrLabel(snr)) }

/** Secondary line of a decode row: "12:34:45 UTC · -12 dB" (SNR omitted when unknown). */
internal fun carDecodeSecondary(utcTimeMs: Long, snrLabel: String?): String {
    val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    val time = "${fmt.format(java.util.Date(utcTimeMs))} UTC"
    return if (snrLabel != null) "$time · $snrLabel" else time
}

/**
 * The 1 Hz tick only refreshes the template when the visible countdown second
 * actually changed, so a jittery handler can't spam the host with no-op renders.
 */
internal fun shouldInvalidateForTick(lastSecond: Int, newSecond: Int): Boolean = newSecond != lastSecond
