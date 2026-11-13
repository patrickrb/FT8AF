package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.database.OperationBand
import org.junit.Test

/**
 * Unit tests for [txPillBandName] — the TxStrip pill's band-name resolver. It must not
 * trust a stale [bandIndex]: after a band-list rebuild (editing a custom dial) drops the
 * in-memory ad-hoc slot while the rig is off-plan, the cached index can point at a
 * now-valid-but-wrong entry, which previously showed the correct MHz next to the wrong
 * band name (#979).
 */
class TxPillBandNameTest {

    private fun band(freq: Long, waveLength: String) = OperationBand.Band(freq, waveLength)

    @Test
    fun indexMatchesDial_usesIndexedName() {
        val list = listOf(band(14_074_000L, "20m"))
        assertThat(txPillBandName(list, 0, 14_074_000L)).isEqualTo("20m")
    }

    @Test
    fun staleIndexPointingAtWrongEntry_fallsBackToFreqMatch() {
        // bandIndex 0 is 40m, but the dial is 14.074 (a configured 20m entry exists at
        // index 1). The stale index must be ignored in favour of the matching entry.
        val list = listOf(band(7_100_000L, "40m"), band(14_074_000L, "20m"))
        assertThat(txPillBandName(list, 0, 14_074_000L)).isEqualTo("20m")
    }

    @Test
    fun staleIndexOffPlanDial_computesWavelengthFromFreq() {
        // Off-plan dial (7.100 MHz) with no matching entry; the only entry (20m) is at the
        // stale index. The name must come from the dial, not the stale 20m entry.
        val list = listOf(band(14_074_000L, "20m"))
        assertThat(txPillBandName(list, 0, 7_100_000L)).isEqualTo("40m")
    }

    @Test
    fun indexOutOfRange_fallsBackToFreqMatch() {
        val list = listOf(band(14_074_000L, "20m"))
        assertThat(txPillBandName(list, 5, 14_074_000L)).isEqualTo("20m")
    }

    @Test
    fun negativeIndexOffPlan_computesWavelengthFromFreq() {
        // bandListIndex is -1 off-plan (no selection); resolve from the dial.
        val list = listOf(band(14_074_000L, "20m"))
        assertThat(txPillBandName(list, -1, 14_200_000L)).isEqualTo("20m")
    }

    @Test
    fun emptyBandList_computesWavelengthFromFreq() {
        assertThat(txPillBandName(emptyList(), 0, 7_074_000L)).isEqualTo("40m")
    }
}
