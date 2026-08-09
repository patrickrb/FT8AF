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

/** A two-line row of the Android Auto status pane: a title and an optional secondary line. */
internal data class CarPaneRow(val title: CarStringSpec, val secondary: CarStringSpec? = null)

/**
 * QSOs a POTA activation needs before it counts under the POTA program rules.
 * There is no constant for this in the POTA session code (the phone UI never
 * shows a remaining-to-validate figure), so the well-known program rule lives
 * here where the car dashboard uses it.
 */
internal const val POTA_ACTIVATION_TARGET = 10

/**
 * Secondary line for the POTA row: "N more to validate the activation" while the
 * count is below [POTA_ACTIVATION_TARGET], then "Activation validated". Counts at
 * or above the target (including hand-logged overshoot) clamp to validated.
 */
internal fun potaValidateSpec(qsoCount: Int): CarStringSpec {
    val remaining = (POTA_ACTIVATION_TARGET - qsoCount).coerceAtLeast(0)
    return if (remaining > 0) {
        CarStringSpec(R.string.car_pota_to_validate, listOf(remaining))
    } else {
        CarStringSpec(R.string.car_pota_validated)
    }
}

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
 * The session-summary row shown when no POTA/ROTA activation is running. The title
 * is always the session QSO count; the secondary reports the most recent logged
 * contact ("Last logged JA1XYZ · 20m · 41 min") when one is known, degrading to a
 * band-less form, then to "No QSOs logged yet" when [lastQsoCallsign] or
 * [lastQsoMinutesAgo] is missing.
 */
internal fun buildCarSessionRow(
    sessionQsoCount: Int,
    lastQsoCallsign: String?,
    lastQsoBandName: String?,
    lastQsoMinutesAgo: Int?,
): CarPaneRow {
    val title = CarStringSpec(R.string.car_session_line, listOf(sessionQsoCount))
    val call = lastQsoCallsign?.takeIf { it.isNotBlank() }
    val secondary = if (call != null && lastQsoMinutesAgo != null) {
        val band = lastQsoBandName?.takeIf { it.isNotBlank() }
        if (band != null) {
            CarStringSpec(R.string.car_session_last, listOf(call, band, lastQsoMinutesAgo))
        } else {
            CarStringSpec(R.string.car_session_last_noband, listOf(call, lastQsoMinutesAgo))
        }
    } else {
        CarStringSpec(R.string.car_session_none)
    }
    return CarPaneRow(title, secondary)
}

/**
 * The activation block of the car status pane. Emits a POTA row (with a
 * "N to validate" secondary) and/or a ROTA row (with a "X.X mi driven this
 * activation" secondary) for whichever activations are running; when neither is
 * active the block collapses to a single session-summary row (the design's
 * "activation rows drop out, session stats take the slot"). POTA and ROTA are
 * practically mutually exclusive — parked at a park vs. roving on roads — but
 * both are emitted if both happen to be active, ordered POTA then ROTA.
 */
internal fun buildCarActivationRows(
    potaActive: Boolean,
    potaParkRefsDisplay: String?,
    potaQsoCount: Int,
    rotaActive: Boolean,
    rotaTripName: String?,
    rotaQsoCount: Int,
    rotaMiles: Double,
    sessionQsoCount: Int,
    lastQsoCallsign: String?,
    lastQsoBandName: String?,
    lastQsoMinutesAgo: Int?,
): List<CarPaneRow> {
    val rows = mutableListOf<CarPaneRow>()
    if (potaActive) {
        buildCarPotaLine(potaParkRefsDisplay, potaQsoCount)?.let {
            rows.add(CarPaneRow(title = it, secondary = potaValidateSpec(potaQsoCount)))
        }
    }
    if (rotaActive && !rotaTripName.isNullOrBlank()) {
        rows.add(
            CarPaneRow(
                title = CarStringSpec(R.string.car_rota_line, listOf(rotaTripName, rotaQsoCount)),
                secondary = CarStringSpec(R.string.car_rota_miles, listOf(formatMiles(rotaMiles))),
            ),
        )
    }
    if (rows.isEmpty()) {
        rows.add(buildCarSessionRow(sessionQsoCount, lastQsoCallsign, lastQsoBandName, lastQsoMinutesAgo))
    }
    return rows
}

/**
 * Secondary line for the band row: "N decodes last cycle" (null when there were no
 * decodes, so the row shows the frequency alone rather than "0 decodes").
 */
internal fun carDecodesSecondary(decodeCount: Int): CarStringSpec? =
    if (decodeCount > 0) CarStringSpec(R.string.car_decodes_last_cycle, listOf(decodeCount)) else null

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
