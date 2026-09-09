package radio.ks3ckc.ft8af.ui.logbook

/**
 * Pure presentation logic for the "Worked All Continents" (WAC) award shown on
 * the logbook Stats tab. Kept as plain top-level functions/data (no Compose) so
 * the award reducer is unit-testable on the JVM without Robolectric — the
 * Composable card is a thin wrapper that just renders [workedAllContinents].
 *
 * WAC is one of the oldest and most recognisable amateur-radio awards: confirm a
 * two-way contact with each of the six populated continents. Antarctica ("AN")
 * is an optional endorsement, not one of the six required, so it never counts
 * toward the award total here.
 *
 * The raw continent codes come from the DXCC lookup tables via
 * [com.k1af.ft8af.count.CountDbOpr.queryWorkedContinents] (grid -> DXCC entity ->
 * continent), matching how the DXCC / CQ-zone stats are already derived.
 */

/** The six WAC continents, in a conventional display order. */
internal val WAC_CONTINENTS: List<String> = listOf("NA", "SA", "EU", "AF", "AS", "OC")

/** One continent's award state: its code (e.g. "EU") and whether it's worked. */
internal data class ContinentChip(val code: String, val worked: Boolean)

/** Reduced WAC award progress: a chip per required continent plus a worked tally. */
internal data class WacProgress(
    val chips: List<ContinentChip>,
    val workedCount: Int,
) {
    /** Number of continents required for the award (always 6). */
    val total: Int get() = WAC_CONTINENTS.size

    /** True once every required continent has been worked. */
    val isComplete: Boolean get() = workedCount >= WAC_CONTINENTS.size
}

/**
 * Reduce a collection of raw continent codes (as stored in `dxccList.continent`,
 * e.g. "NA", "EU", or the stray "AN"/"-") into [WacProgress].
 *
 * Codes are trimmed and upper-cased before matching, duplicates collapse, and
 * anything outside the six standard continents (Antarctica, blanks, unknown "-",
 * junk) is ignored so it can never inflate the award total. The returned chip
 * list is always the six [WAC_CONTINENTS] in canonical order, so the UI renders a
 * stable row regardless of which continents happen to be present in the input.
 */
internal fun workedAllContinents(rawCodes: Collection<String?>): WacProgress {
    val worked = rawCodes
        .asSequence()
        .filterNotNull()
        .map { it.trim().uppercase() }
        .filter { it in WAC_CONTINENTS }
        .toSet()
    val chips = WAC_CONTINENTS.map { ContinentChip(code = it, worked = it in worked) }
    return WacProgress(chips = chips, workedCount = worked.size)
}
