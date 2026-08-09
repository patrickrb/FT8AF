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

/**
 * "POTA K-1234 · 3 QSOs" (multi-park refs arrive pre-joined as "K-1234 + K-5678").
 * Null when no activation is running, which removes the row entirely.
 */
internal fun buildCarPotaLine(parkRefsDisplay: String?, qsoCount: Int?): CarStringSpec? {
    if (parkRefsDisplay.isNullOrBlank()) return null
    return CarStringSpec(R.string.car_pota_line, listOf(parkRefsDisplay, qsoCount ?: 0))
}

/**
 * "ROTA Route 66 · 12 QSOs · 45.3 mi"; null when no trip is running. The QSO
 * count is sent+pending so contacts logged out of coverage still show, and the
 * miles match the trip notification's one-decimal format.
 */
internal fun buildCarRotaLine(
    active: Boolean,
    tripName: String,
    sentQsos: Int,
    pendingQsos: Int,
    miles: Double,
): CarStringSpec? {
    if (!active) return null
    val name = tripName.trim().ifEmpty { "trip" }
    val milesLabel = String.format(java.util.Locale.US, "%.1f", miles)
    return CarStringSpec(R.string.car_rota_line, listOf(name, sentQsos + pendingQsos, milesLabel))
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
