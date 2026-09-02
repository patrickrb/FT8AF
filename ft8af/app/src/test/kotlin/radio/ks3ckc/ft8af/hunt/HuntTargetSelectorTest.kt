package radio.ks3ckc.ft8af.hunt

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.Ft8Message
import org.junit.Test

/**
 * Coverage for the pure Hunt-options ranking logic in HuntTargetSelector.kt:
 * config parsing, the min-SNR floor, pileup avoidance, and each [HuntPriority]
 * mode including unknown-SNR/-distance handling and freshest-decode tie breaks.
 *
 * Plain JUnit: only the pure top-level functions are exercised (the
 * HuntTargetSelector object bridge touches GeneralVariables/Android and is
 * covered by the engine's on-device behavior).
 */
class HuntTargetSelectorTest {

    private fun candidate(
        index: Int,
        snr: Int? = null,
        distanceKm: Double? = null,
        isActivator: Boolean = false,
        isNewPark: Boolean = false,
        isNewDxcc: Boolean = false,
        isNewGrid: Boolean = false,
        pileupCalls: Int = 0,
    ) = HuntCandidate(index, snr, distanceKm, isActivator, isNewPark, isNewDxcc, isNewGrid, pileupCalls)

    private fun select(
        candidates: List<HuntCandidate>,
        priority: HuntPriority = HuntPriority.LATEST,
        avoidPileups: Boolean = false,
        minSnrDb: Int? = null,
    ) = selectHuntCandidate(candidates, priority, avoidPileups, minSnrDb)

    // ---- huntPriorityFromConfig ----

    @Test
    fun configParse_roundTripsEveryEnumName() {
        for (p in HuntPriority.entries) {
            assertThat(huntPriorityFromConfig(p.name)).isEqualTo(p)
        }
    }

    @Test
    fun configParse_isCaseInsensitiveAndTrims() {
        assertThat(huntPriorityFromConfig(" strongest ")).isEqualTo(HuntPriority.STRONGEST)
    }

    @Test
    fun configParse_fallsBackToLatestOnBadValues() {
        assertThat(huntPriorityFromConfig(null)).isEqualTo(HuntPriority.LATEST)
        assertThat(huntPriorityFromConfig("")).isEqualTo(HuntPriority.LATEST)
        assertThat(huntPriorityFromConfig("BOGUS")).isEqualTo(HuntPriority.LATEST)
    }

    // ---- passesSnrFloor ----

    @Test
    fun snrFloor_disabledPassesEverything() {
        assertThat(passesSnrFloor(-24, null)).isTrue()
        assertThat(passesSnrFloor(null, null)).isTrue()
    }

    @Test
    fun snrFloor_filtersBelowAndKeepsAtOrAbove() {
        assertThat(passesSnrFloor(-16, -15)).isFalse()
        assertThat(passesSnrFloor(-15, -15)).isTrue()
        assertThat(passesSnrFloor(3, -15)).isTrue()
    }

    @Test
    fun snrFloor_unknownSnrPasses() {
        // The decoder occasionally omits SNR; those stations must not silently vanish.
        assertThat(passesSnrFloor(null, -15)).isTrue()
    }

    // ---- floor + empty handling ----

    @Test
    fun select_emptyListReturnsNull() {
        assertThat(select(emptyList())).isNull()
    }

    @Test
    fun select_returnsNullWhenFloorEliminatesEveryone() {
        val all = listOf(candidate(0, snr = -20), candidate(1, snr = -22))
        assertThat(select(all, minSnrDb = -15)).isNull()
    }

    @Test
    fun select_floorAppliesBeforePriority() {
        val strongButFloored = candidate(0, snr = -20)
        val weakerButPassing = candidate(1, snr = -10)
        val picked = select(
            listOf(strongButFloored, weakerButPassing),
            priority = HuntPriority.WEAKEST, minSnrDb = -15,
        )
        assertThat(picked).isEqualTo(weakerButPassing)
    }

    // ---- LATEST (historical behavior) ----

    @Test
    fun latest_picksFirstCandidate_listIsMostRecentFirst() {
        val newest = candidate(0, snr = -20)
        val older = candidate(1, snr = 5)
        assertThat(select(listOf(newest, older))).isEqualTo(newest)
    }

    // ---- STRONGEST / WEAKEST ----

    @Test
    fun strongest_picksHighestSnr() {
        val weak = candidate(0, snr = -18)
        val loud = candidate(1, snr = 4)
        assertThat(select(listOf(weak, loud), HuntPriority.STRONGEST)).isEqualTo(loud)
    }

    @Test
    fun strongest_unknownSnrLosesToAnyKnown() {
        val unknown = candidate(0, snr = null)
        val faint = candidate(1, snr = -24)
        assertThat(select(listOf(unknown, faint), HuntPriority.STRONGEST)).isEqualTo(faint)
    }

    @Test
    fun weakest_picksLowestSnr() {
        val loud = candidate(0, snr = 4)
        val weak = candidate(1, snr = -18)
        assertThat(select(listOf(loud, weak), HuntPriority.WEAKEST)).isEqualTo(weak)
    }

    @Test
    fun weakest_unknownSnrNeverWins() {
        val unknown = candidate(0, snr = null)
        val weak = candidate(1, snr = -5)
        assertThat(select(listOf(unknown, weak), HuntPriority.WEAKEST)).isEqualTo(weak)
    }

    @Test
    fun strongest_tieGoesToFreshestDecode() {
        val newer = candidate(0, snr = -3)
        val older = candidate(1, snr = -3)
        assertThat(select(listOf(newer, older), HuntPriority.STRONGEST)).isEqualTo(newer)
    }

    @Test
    fun tieBreak_usesIndexNotListPosition() {
        // The tie-break is the explicit index comparator, not sort stability: even if
        // the pool arrives out of freshness order, the smaller index still wins.
        val older = candidate(3, snr = -3)
        val newer = candidate(1, snr = -3)
        assertThat(select(listOf(older, newer), HuntPriority.STRONGEST)).isEqualTo(newer)
    }

    @Test
    fun farthest_tieGoesToFreshestDecode() {
        val newer = candidate(0, distanceKm = 5000.0)
        val older = candidate(1, distanceKm = 5000.0)
        assertThat(select(listOf(newer, older), HuntPriority.FARTHEST)).isEqualTo(newer)
    }

    @Test
    fun potaFirst_fullTieGoesToFreshestDecode() {
        val newer = candidate(0, snr = -8, isActivator = true)
        val older = candidate(1, snr = -8, isActivator = true)
        assertThat(select(listOf(newer, older), HuntPriority.POTA_FIRST)).isEqualTo(newer)
    }

    // ---- FARTHEST ----

    @Test
    fun farthest_picksLargestDistance() {
        val near = candidate(0, distanceKm = 120.0)
        val far = candidate(1, distanceKm = 8400.0)
        assertThat(select(listOf(near, far), HuntPriority.FARTHEST)).isEqualTo(far)
    }

    @Test
    fun farthest_unknownDistanceLosesToAnyKnown() {
        val unknown = candidate(0, distanceKm = null)
        val near = candidate(1, distanceKm = 15.0)
        assertThat(select(listOf(unknown, near), HuntPriority.FARTHEST)).isEqualTo(near)
    }

    // ---- POTA_FIRST ----

    @Test
    fun potaFirst_newParkBeatsAnyActivatorBeatsPlainCq() {
        val plain = candidate(0, snr = 10)
        val activator = candidate(1, snr = -10, isActivator = true)
        val newPark = candidate(2, snr = -20, isActivator = true, isNewPark = true)
        assertThat(select(listOf(plain, activator, newPark), HuntPriority.POTA_FIRST))
            .isEqualTo(newPark)
        assertThat(select(listOf(plain, activator), HuntPriority.POTA_FIRST))
            .isEqualTo(activator)
    }

    @Test
    fun potaFirst_snrBreaksTiesWithinTier() {
        val faintActivator = candidate(0, snr = -15, isActivator = true)
        val loudActivator = candidate(1, snr = 0, isActivator = true)
        assertThat(select(listOf(faintActivator, loudActivator), HuntPriority.POTA_FIRST))
            .isEqualTo(loudActivator)
    }

    @Test
    fun activatorRank_tiers() {
        assertThat(activatorRank(candidate(0, isActivator = true, isNewPark = true))).isEqualTo(2)
        assertThat(activatorRank(candidate(0, isActivator = true))).isEqualTo(1)
        assertThat(activatorRank(candidate(0))).isEqualTo(0)
    }

    // ---- NEW_DXCC_FIRST / NEW_GRID_FIRST ----

    @Test
    fun newDxccFirst_newEntityBeatsLouderKnownEntity() {
        val loudKnown = candidate(0, snr = 10)
        val faintNew = candidate(1, snr = -19, isNewDxcc = true)
        assertThat(select(listOf(loudKnown, faintNew), HuntPriority.NEW_DXCC_FIRST))
            .isEqualTo(faintNew)
    }

    @Test
    fun newDxccFirst_fallsBackToStrongestWhenNoNewEntity() {
        val faint = candidate(0, snr = -12)
        val loud = candidate(1, snr = -2)
        assertThat(select(listOf(faint, loud), HuntPriority.NEW_DXCC_FIRST)).isEqualTo(loud)
    }

    @Test
    fun newGridFirst_newGridBeatsLouderKnownGrid() {
        val loudKnown = candidate(0, snr = 7)
        val faintNew = candidate(1, snr = -14, isNewGrid = true)
        assertThat(select(listOf(loudKnown, faintNew), HuntPriority.NEW_GRID_FIRST))
            .isEqualTo(faintNew)
    }

    // ---- Pileup avoidance ----

    @Test
    fun avoidPileups_prefersUncontestedCaller() {
        val contested = candidate(0, snr = 10, pileupCalls = 3)
        val clear = candidate(1, snr = -12, pileupCalls = 0)
        assertThat(select(listOf(contested, clear), HuntPriority.STRONGEST, avoidPileups = true))
            .isEqualTo(clear)
    }

    @Test
    fun avoidPileups_fallsBackWhenEveryoneIsContested() {
        // A soft preference: when every caller has a pileup, still hunt rather than sit idle.
        val a = candidate(0, snr = -12, pileupCalls = 2)
        val b = candidate(1, snr = 3, pileupCalls = 1)
        assertThat(select(listOf(a, b), HuntPriority.STRONGEST, avoidPileups = true))
            .isEqualTo(b)
    }

    @Test
    fun avoidPileups_offIgnoresPileupCounts() {
        val contested = candidate(0, snr = 10, pileupCalls = 5)
        val clear = candidate(1, snr = -12, pileupCalls = 0)
        assertThat(select(listOf(contested, clear), HuntPriority.STRONGEST)).isEqualTo(contested)
    }

    // ---- countRepliesTo ----

    @Test
    fun countReplies_countsDirectedNonCqMessages() {
        val messages = listOf(
            Ft8Message("CQ", "W1ABC", "FN42"), // the CQer himself — not a reply
            Ft8Message("W1ABC", "K2DEF", "-10"), // reply to W1ABC
            Ft8Message("W1ABC", "N3GHI", "EM73"), // reply to W1ABC
            Ft8Message("K9ZZZ", "W4JKL", "R-05"), // reply to someone else
        )
        assertThat(countRepliesTo("W1ABC", messages)).isEqualTo(2)
        assertThat(countRepliesTo("K9ZZZ", messages)).isEqualTo(1)
        assertThat(countRepliesTo("NOBODY", messages)).isEqualTo(0)
    }

    @Test
    fun countReplies_isCaseInsensitiveAndNullSafe() {
        val messages = listOf(Ft8Message("w1abc", "K2DEF", "-10"))
        assertThat(countRepliesTo("W1ABC", messages)).isEqualTo(1)
        assertThat(countRepliesTo(null, messages)).isEqualTo(0)
        assertThat(countRepliesTo("", messages)).isEqualTo(0)
    }
}
