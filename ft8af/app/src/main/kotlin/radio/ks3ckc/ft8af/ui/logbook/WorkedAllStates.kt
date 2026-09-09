package radio.ks3ckc.ft8af.ui.logbook

import java.util.Locale

/**
 * Worked All States (WAS) award logic.
 *
 * ARRL's WAS award is earned by making contact with all 50 US states. FT8AF
 * already derives a QSO's state from its Maidenhead grid (see
 * [radio.ks3ckc.ft8af.ui.decode.UsStateLookup]), so both the count of distinct
 * worked states and the list of states still needed can be computed directly
 * from the logbook — no separate tracking table required.
 *
 * The functions here are pure (grid->state resolution is injected as a lambda)
 * so they unit-test without an Android context, mirroring [workedGridFields] /
 * [buildGridHeatmapCells] in the same package.
 *
 * Washington DC is intentionally excluded: it is not one of the 50 WAS states,
 * even though the grid->state table can resolve a grid to "DC".
 */

/** The 50 US states (two-letter USPS abbreviations), the ARRL WAS award basis. */
internal val WAS_STATES: List<String> = listOf(
    "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
    "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
    "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
    "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
    "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
)

private val WAS_STATE_SET: Set<String> = WAS_STATES.toSet()

/**
 * The distinct WAS states worked, derived from each grid via [gridToState].
 *
 * A grid that resolves to no state (or to DC, or to any non-WAS designator) is
 * ignored. Resolved abbreviations are trimmed and upper-cased with
 * [Locale.ROOT] — the state table's designators are ASCII, so upper-casing must
 * be locale-insensitive (a Turkish-locale `uppercase()` would map "il" to "İL"
 * and never match the ASCII "IL" state).
 */
internal fun workedStates(
    grids: List<String?>,
    gridToState: (String) -> String?,
): Set<String> =
    grids.asSequence()
        .filterNotNull()
        .mapNotNull { grid -> gridToState(grid)?.trim()?.uppercase(Locale.ROOT) }
        .filter { it in WAS_STATE_SET }
        .toSet()

/**
 * The WAS states still needed, in the canonical [WAS_STATES] order, given the
 * set already [worked]. Comparison is case-insensitive; anything in [worked]
 * that is not a WAS state (e.g. "DC") is ignored.
 */
internal fun neededStates(worked: Set<String>): List<String> {
    val have = worked.mapTo(HashSet()) { it.trim().uppercase(Locale.ROOT) }
    return WAS_STATES.filter { it !in have }
}

/**
 * Split a [remaining] states list into the chips to display (at most [max]) and
 * the count hidden behind a trailing "+N" overflow chip, so the WAS card can
 * preview the states still needed without ever rendering all 50. [max] <= 0
 * shows nothing and reports the whole list as overflow.
 */
internal fun neededStatesPreview(remaining: List<String>, max: Int): Pair<List<String>, Int> = when {
    max <= 0 -> emptyList<String>() to remaining.size
    remaining.size <= max -> remaining to 0
    else -> remaining.take(max) to (remaining.size - max)
}
