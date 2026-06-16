package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit coverage for [potaSpotFrequencyKhz], the helper that turns the dial
 * frequency (GeneralVariables.band, in Hz) into the kHz value posted to a POTA
 * self-spot.
 *
 * Regression guard: the spot must use the carrier/dial frequency, NOT the audio
 * offset (getBaseFrequency). Posting the audio offset produced ~1-3.5 kHz spots
 * on every band — most visibly wrong on 6 m, where 50313.0 came out as ~1.
 *
 * Pure arithmetic, so plain JUnit (no Robolectric runner) suffices.
 */
class PotaSpotFrequencyTest {

    @Test
    fun `6m FT8 dial yields 50313 kHz`() {
        assertThat(potaSpotFrequencyKhz(50_313_000L)).isEqualTo(50_313.0)
    }

    @Test
    fun `20m FT8 dial yields 14074 kHz`() {
        assertThat(potaSpotFrequencyKhz(14_074_000L)).isEqualTo(14_074.0)
    }

    @Test
    fun `40m FT8 dial yields 7074 kHz`() {
        assertThat(potaSpotFrequencyKhz(7_074_000L)).isEqualTo(7_074.0)
    }

    @Test
    fun `result is the dial in kHz, never the bare audio offset`() {
        // A 1000 Hz audio offset must not leak through as a ~1 kHz spot.
        val spot = potaSpotFrequencyKhz(50_313_000L)
        assertThat(spot).isGreaterThan(1_000.0)
        assertThat(spot).isEqualTo(50_313.0)
    }
}
