package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for the PSK filter helpers — band classification and the
 * client-side band cull. No Android runtime.
 */
class PskFilterTest {

    private data class Spot(override val frequencyHz: Long) : HasFrequencyHz

    @Test
    fun freqHzToBand_classifiesCommonBands() {
        assertThat(freqHzToBand(14_076_000)).isEqualTo(Band.M20)
        assertThat(freqHzToBand(7_074_000)).isEqualTo(Band.M40)
        assertThat(freqHzToBand(50_313_000)).isEqualTo(Band.M6)
        assertThat(freqHzToBand(1_840_000)).isEqualTo(Band.M160)
    }

    @Test
    fun freqHzToBand_outOfBandIsNull() {
        assertThat(freqHzToBand(100_000_000)).isNull()
        assertThat(freqHzToBand(9_000_000)).isNull()  // between 30m and 40m
    }

    @Test
    fun freqHzToBand_boundariesInclusive() {
        assertThat(freqHzToBand(14_000_000)).isEqualTo(Band.M20)  // low edge
        assertThat(freqHzToBand(14_350_000)).isEqualTo(Band.M20)  // high edge
    }

    @Test
    fun applyPskBandFilter_nullBandPassesAll() {
        val spots = listOf(Spot(14_076_000), Spot(7_074_000))
        assertThat(applyPskBandFilter(spots, null)).hasSize(2)
    }

    @Test
    fun applyPskBandFilter_keepsOnlyMatchingBand() {
        val spots = listOf(Spot(14_076_000), Spot(7_074_000), Spot(14_080_000))
        val filtered = applyPskBandFilter(spots, Band.M20)
        assertThat(filtered).hasSize(2)
        assertThat(filtered.map { it.frequencyHz }).containsExactly(14_076_000L, 14_080_000L)
    }

    @Test
    fun applyPskBandFilter_dropsOutOfBandSpots() {
        val spots = listOf(Spot(14_076_000), Spot(9_000_000))
        assertThat(applyPskBandFilter(spots, Band.M20)).hasSize(1)
    }

    @Test
    fun pskTimeLabel_mapsPresets() {
        assertThat(pskTimeLabel(900)).isEqualTo("15m")
        assertThat(pskTimeLabel(3600)).isEqualTo("1h")
        assertThat(pskTimeLabel(86400)).isEqualTo("24h")
    }
}
