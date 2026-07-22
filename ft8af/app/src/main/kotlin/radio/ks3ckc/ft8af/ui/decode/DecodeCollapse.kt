package radio.ks3ckc.ft8af.ui.decode

import com.k1af.ft8af.Ft8Message

/**
 * How the collapsed (one-row-per-station) decode list is ordered.
 *
 * The [configValue] is the stable integer persisted via
 * `GeneralVariables.decodeSortMode` / DB key `"decodeSortMode"` (same pattern as
 * `msgMode` / `clearDecodesEveryCycle`). Persist by [configValue], never by
 * ordinal, so reordering the enum can't silently repoint a saved choice.
 */
internal enum class DecodeSortMode(val configValue: Int) {
    /** Most recently decoded station first (default). */
    LAST_HEARD(0),

    /** Alphabetical by callsign. */
    CALLSIGN(1),

    /** Strongest signal (highest SNR) first. */
    SNR(2),
    ;

    companion object {
        /** Resolve a persisted [configValue] back to a mode, defaulting to [LAST_HEARD]. */
        fun fromConfig(value: Int): DecodeSortMode =
            entries.firstOrNull { it.configValue == value } ?: LAST_HEARD
    }
}

/** The mode selected when the operator taps the sort control while on [current]. */
internal fun nextSortMode(current: DecodeSortMode): DecodeSortMode = when (current) {
    DecodeSortMode.LAST_HEARD -> DecodeSortMode.CALLSIGN
    DecodeSortMode.CALLSIGN -> DecodeSortMode.SNR
    DecodeSortMode.SNR -> DecodeSortMode.LAST_HEARD
}

/**
 * Collapse an append-only decode stream to one row per station, keeping the
 * latest decode for each callsign, then order the result per [sortMode].
 *
 * "Latest" is the decode with the greatest [Ft8Message.utcTime]; on a tie the
 * one that appears later in [messages] wins (it's the newer decode in the raw
 * append-order stream). Messages with a null/blank callsign have nothing to
 * coalesce on and are dropped — the row UI keys entirely off the callsign.
 *
 * Sorting is stable: ties keep first-appearance order in [messages], so the
 * list doesn't jitter as identical-key decodes stream in.
 *
 * Callers should collapse **after** filtering so the visible-station count
 * agrees with the active chip filter (see `filterMessages`).
 */
internal fun collapseByStation(
    messages: List<Ft8Message>,
    sortMode: DecodeSortMode,
): List<Ft8Message> {
    // LinkedHashMap preserves first-appearance order, which is our stable
    // tiebreak. Overwrite with the newer decode (>= keeps later-on-tie).
    val latest = LinkedHashMap<String, Ft8Message>()
    for (m in messages) {
        val cs = m.callsignFrom?.trim()?.uppercase()
        if (cs.isNullOrEmpty()) continue
        val existing = latest[cs]
        if (existing == null || m.utcTime >= existing.utcTime) {
            latest[cs] = m
        }
    }
    val collapsed = latest.values.toList()
    return when (sortMode) {
        DecodeSortMode.LAST_HEARD -> collapsed.sortedByDescending { it.utcTime }
        DecodeSortMode.CALLSIGN -> collapsed.sortedBy { it.callsignFrom?.uppercase().orEmpty() }
        // Unknown SNR sinks to the bottom rather than pretending to be very weak.
        DecodeSortMode.SNR -> collapsed.sortedByDescending {
            if (it.hasSnr()) it.snr else Int.MIN_VALUE
        }
    }
}

/**
 * Whether the per-cycle time-group dividers make sense for [sortMode]. They only
 * do in [DecodeSortMode.LAST_HEARD], where rows stay time-ordered; once sorted by
 * callsign or SNR the rows interleave cycles and the dividers would be noise.
 */
internal fun showTimeGroupDividers(sortMode: DecodeSortMode): Boolean =
    sortMode == DecodeSortMode.LAST_HEARD

/**
 * Index to auto-scroll to when the collapsed list grows, or null to leave the
 * scroll position alone. Only [DecodeSortMode.LAST_HEARD] auto-scrolls, and to
 * the top (index 0) because that mode puts the newest station first. In the
 * other modes a new station can land anywhere, so yanking the viewport would
 * fight the operator.
 */
internal fun autoScrollTargetIndex(
    sortMode: DecodeSortMode,
    size: Int,
    previousSize: Int,
): Int? {
    if (size <= previousSize || size == 0) return null
    return if (sortMode == DecodeSortMode.LAST_HEARD) 0 else null
}
