package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.Ft8Message
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit-tests for the pure [collapseByStation] coalescing/sort logic and its
 * small helper functions. Robolectric is needed only because [Ft8Message]
 * reaches android.util.Log on construction (same reason as [DecodeFilterTest]).
 */
@RunWith(RobolectricTestRunner::class)
class DecodeCollapseTest {

    private fun msg(
        from: String,
        utc: Long,
        snr: Int = 0,
        freq: Float = 1000f,
    ): Ft8Message = Ft8Message("CQ", from, "FN42").apply {
        utcTime = utc
        this.snr = snr
        freq_hz = freq
    }

    // ---- duplicate collapse --------------------------------------------------

    @Test
    fun collapse_keepsOneRowPerStation_withLatestDecode() {
        val messages = listOf(
            msg("K1ABC", utc = 1000, snr = -5, freq = 1000f),
            msg("K2DEF", utc = 1100, snr = 3),
            msg("K1ABC", utc = 2000, snr = 12, freq = 1500f),
        )

        val result = collapseByStation(messages, DecodeSortMode.LAST_HEARD)

        assertThat(result.map { it.callsignFrom }).containsExactly("K1ABC", "K2DEF")
        val k1 = result.first { it.callsignFrom == "K1ABC" }
        // Fields come from the *latest* K1ABC decode (utc 2000), not the first.
        assertThat(k1.utcTime).isEqualTo(2000)
        assertThat(k1.snr).isEqualTo(12)
        assertThat(k1.getFreq_hz()).isEqualTo("1500")
    }

    @Test
    fun collapse_isCaseInsensitiveByCallsign() {
        // callsignFrom is uppercased on construction, but guard against callers
        // handing us mixed case anyway.
        val a = msg("k1abc", utc = 1000)
        val b = msg("K1ABC", utc = 2000)

        val result = collapseByStation(listOf(a, b), DecodeSortMode.LAST_HEARD)

        assertThat(result).hasSize(1)
        assertThat(result.first().utcTime).isEqualTo(2000)
    }

    @Test
    fun collapse_laterDecodeWinsOnTiedUtcTime() {
        val first = msg("K1ABC", utc = 5000, snr = -1)
        val later = msg("K1ABC", utc = 5000, snr = 20)

        val result = collapseByStation(listOf(first, later), DecodeSortMode.LAST_HEARD)

        assertThat(result).hasSize(1)
        assertThat(result.first().snr).isEqualTo(20)
    }

    @Test
    fun collapse_ignoresOutOfOrderOlderDecode() {
        val newer = msg("K1ABC", utc = 5000, snr = 20)
        val stale = msg("K1ABC", utc = 1000, snr = -30)

        val result = collapseByStation(listOf(newer, stale), DecodeSortMode.LAST_HEARD)

        assertThat(result).hasSize(1)
        assertThat(result.first().utcTime).isEqualTo(5000)
        assertThat(result.first().snr).isEqualTo(20)
    }

    // ---- sort modes ----------------------------------------------------------

    @Test
    fun sort_lastHeard_mostRecentFirst() {
        val messages = listOf(
            msg("AA", utc = 1000),
            msg("BB", utc = 3000),
            msg("CC", utc = 2000),
        )

        val result = collapseByStation(messages, DecodeSortMode.LAST_HEARD)

        assertThat(result.map { it.callsignFrom }).containsExactly("BB", "CC", "AA").inOrder()
    }

    @Test
    fun sort_callsign_alphabetical() {
        val messages = listOf(
            msg("W9XYZ", utc = 3000),
            msg("K1ABC", utc = 1000),
            msg("N0RC", utc = 2000),
        )

        val result = collapseByStation(messages, DecodeSortMode.CALLSIGN)

        assertThat(result.map { it.callsignFrom }).containsExactly("K1ABC", "N0RC", "W9XYZ").inOrder()
    }

    @Test
    fun sort_snr_strongestFirst_unknownSinks() {
        val messages = listOf(
            msg("AA", utc = 1000, snr = -10),
            msg("BB", utc = 1000, snr = 15),
            msg("CC", utc = 1000).apply { snr = Ft8Message.SNR_UNKNOWN },
            msg("DD", utc = 1000, snr = 0),
        )

        val result = collapseByStation(messages, DecodeSortMode.SNR)

        // 15, 0, -10, then unknown last.
        assertThat(result.map { it.callsignFrom }).containsExactly("BB", "DD", "AA", "CC").inOrder()
    }

    // ---- stable ordering on ties ---------------------------------------------

    @Test
    fun sort_lastHeard_stableOnTiedTime() {
        val messages = listOf(
            msg("AA", utc = 1000),
            msg("BB", utc = 1000),
            msg("CC", utc = 1000),
        )

        val result = collapseByStation(messages, DecodeSortMode.LAST_HEARD)

        // Equal times keep first-appearance order.
        assertThat(result.map { it.callsignFrom }).containsExactly("AA", "BB", "CC").inOrder()
    }

    @Test
    fun sort_snr_stableOnTiedSnr() {
        val messages = listOf(
            msg("AA", utc = 1000, snr = 5),
            msg("BB", utc = 2000, snr = 5),
            msg("CC", utc = 3000, snr = 5),
        )

        val result = collapseByStation(messages, DecodeSortMode.SNR)

        assertThat(result.map { it.callsignFrom }).containsExactly("AA", "BB", "CC").inOrder()
    }

    @Test
    fun collapse_dropsBlankCallsigns() {
        val messages = listOf(
            msg("K1ABC", utc = 1000),
            Ft8Message(1).apply { callsignFrom = null; utcTime = 2000 },
        )

        val result = collapseByStation(messages, DecodeSortMode.LAST_HEARD)

        assertThat(result.map { it.callsignFrom }).containsExactly("K1ABC")
    }

    @Test
    fun collapse_emptyInputYieldsEmpty() {
        assertThat(collapseByStation(emptyList(), DecodeSortMode.SNR)).isEmpty()
    }

    // ---- helper functions ----------------------------------------------------

    @Test
    fun sortMode_configRoundTrips() {
        for (mode in DecodeSortMode.entries) {
            assertThat(DecodeSortMode.fromConfig(mode.configValue)).isEqualTo(mode)
        }
    }

    @Test
    fun sortMode_fromConfig_defaultsToLastHeard() {
        assertThat(DecodeSortMode.fromConfig(-1)).isEqualTo(DecodeSortMode.LAST_HEARD)
        assertThat(DecodeSortMode.fromConfig(99)).isEqualTo(DecodeSortMode.LAST_HEARD)
    }

    @Test
    fun nextSortMode_cyclesThroughAllThenWraps() {
        assertThat(nextSortMode(DecodeSortMode.LAST_HEARD)).isEqualTo(DecodeSortMode.CALLSIGN)
        assertThat(nextSortMode(DecodeSortMode.CALLSIGN)).isEqualTo(DecodeSortMode.SNR)
        assertThat(nextSortMode(DecodeSortMode.SNR)).isEqualTo(DecodeSortMode.LAST_HEARD)
    }

    @Test
    fun showTimeGroupDividers_onlyInLastHeard() {
        assertThat(showTimeGroupDividers(DecodeSortMode.LAST_HEARD)).isTrue()
        assertThat(showTimeGroupDividers(DecodeSortMode.CALLSIGN)).isFalse()
        assertThat(showTimeGroupDividers(DecodeSortMode.SNR)).isFalse()
    }

    @Test
    fun autoScrollTarget_onlyLastHeardScrollsToTopWhenGrown() {
        assertThat(autoScrollTargetIndex(DecodeSortMode.LAST_HEARD, size = 3, previousSize = 2)).isEqualTo(0)
        // No growth -> no scroll.
        assertThat(autoScrollTargetIndex(DecodeSortMode.LAST_HEARD, size = 2, previousSize = 2)).isNull()
        // Other modes never auto-scroll.
        assertThat(autoScrollTargetIndex(DecodeSortMode.CALLSIGN, size = 3, previousSize = 2)).isNull()
        assertThat(autoScrollTargetIndex(DecodeSortMode.SNR, size = 3, previousSize = 2)).isNull()
        // Empty list -> no scroll.
        assertThat(autoScrollTargetIndex(DecodeSortMode.LAST_HEARD, size = 0, previousSize = 0)).isNull()
    }
}
