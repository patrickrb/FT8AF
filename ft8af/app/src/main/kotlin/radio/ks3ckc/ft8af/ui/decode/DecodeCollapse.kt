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
        val cs = stationKey(m)
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
 * The station identity a decode collapses onto: [Ft8Message.callsignFrom] trimmed
 * and upper-cased, or null when there is nothing to coalesce on.
 *
 * [collapseByStation] returns the original [Ft8Message] objects, whose
 * `callsignFrom` field is *not* rewritten to this normalized form — the decode
 * objects are shared with the rest of the app and must not be mutated here. Only
 * one constructor of `Ft8Message` upper-cases the callsign; the others (and the
 * hashed-callsign resolution path) can leave mixed case or padding in place. Any
 * caller that keys UI state off a station must therefore route through this same
 * function, or a station arriving as "k1abc" one cycle and "K1ABC" the next would
 * collapse to one row while its row key churned — recreating the row and
 * defeating the update-in-place behaviour this file exists to provide.
 */
internal fun stationKey(message: Ft8Message): String? =
    message.callsignFrom?.trim()?.uppercase()

/**
 * The per-render bookkeeping behind the "row is new or just updated" entry
 * animation: [seen] is what to retain for the next render, [new] is what to
 * animate now.
 */
internal data class RowAnimationState(
    val seen: Set<String>,
    val new: Set<String>,
)

/**
 * Advance the entry-animation state for a render whose visible rows are [current].
 *
 * Retains exactly [current] rather than accumulating every key ever seen. The
 * keys embed a timestamp (see [rowAnimationKey]), so a union would add one entry
 * per station per cycle and grow without bound over a long session even though
 * the visible list stays small — a slow leak in a screen that is left running for
 * hours. Anything absent from [current] can no longer be on screen, so dropping
 * it is safe; if that station returns later it animates again, which is the
 * intended "just updated" cue.
 */
internal fun advanceRowAnimation(
    previousSeen: Set<String>,
    current: Set<String>,
): RowAnimationState = RowAnimationState(seen = current, new = current - previousSeen)

/**
 * Entry-animation key for one collapsed row: normalized station plus the decode's
 * timestamp, so re-decoding a station in a later cycle re-triggers the highlight.
 */
internal fun rowAnimationKey(message: Ft8Message): String =
    "${stationKey(message).orEmpty()}_${message.utcTime}"

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
