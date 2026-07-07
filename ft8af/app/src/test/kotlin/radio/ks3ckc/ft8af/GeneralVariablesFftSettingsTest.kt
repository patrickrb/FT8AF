package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the clamping setters for the FFT display developer knobs (issue #428)
 * in [GeneralVariables]. These values arrive from the config DB (possibly a
 * hand-edited or stale backup), so out-of-range input must fall back to each
 * knob's default rather than reach the native side. Robolectric because
 * GeneralVariables carries Android LiveData statics.
 */
@RunWith(RobolectricTestRunner::class)
class GeneralVariablesFftSettingsTest {

    private var savedWindow = 0
    private var savedAveraging = 0
    private var savedAggregation = 0

    @Before
    fun saveGlobals() {
        savedWindow = GeneralVariables.getFftWindowType()
        savedAveraging = GeneralVariables.getFftAveragingMode()
        savedAggregation = GeneralVariables.getSpectrumBinAggregation()
    }

    @After
    fun restoreGlobals() {
        GeneralVariables.setFftWindowType(savedWindow)
        GeneralVariables.setFftAveragingMode(savedAveraging)
        GeneralVariables.setSpectrumBinAggregation(savedAggregation)
    }

    @Test
    fun `defaults are hann, averaging off, max aggregation`() {
        // Wire values: window 1=Hann, averaging 0=off, aggregation 0=max.
        assertThat(savedWindow).isEqualTo(1)
        assertThat(savedAveraging).isEqualTo(0)
        assertThat(savedAggregation).isEqualTo(0)
    }

    @Test
    fun `window setter accepts every valid value`() {
        for (v in 0..4) {
            GeneralVariables.setFftWindowType(v)
            assertThat(GeneralVariables.getFftWindowType()).isEqualTo(v)
        }
    }

    @Test
    fun `window setter clamps out-of-range to hann`() {
        GeneralVariables.setFftWindowType(5)
        assertThat(GeneralVariables.getFftWindowType()).isEqualTo(1)
        GeneralVariables.setFftWindowType(-1)
        assertThat(GeneralVariables.getFftWindowType()).isEqualTo(1)
    }

    @Test
    fun `averaging setter accepts every valid value`() {
        for (v in 0..2) {
            GeneralVariables.setFftAveragingMode(v)
            assertThat(GeneralVariables.getFftAveragingMode()).isEqualTo(v)
        }
    }

    @Test
    fun `averaging setter clamps out-of-range to off`() {
        GeneralVariables.setFftAveragingMode(3)
        assertThat(GeneralVariables.getFftAveragingMode()).isEqualTo(0)
        GeneralVariables.setFftAveragingMode(-7)
        assertThat(GeneralVariables.getFftAveragingMode()).isEqualTo(0)
    }

    @Test
    fun `aggregation setter accepts every valid value`() {
        for (v in 0..2) {
            GeneralVariables.setSpectrumBinAggregation(v)
            assertThat(GeneralVariables.getSpectrumBinAggregation()).isEqualTo(v)
        }
    }

    @Test
    fun `aggregation setter clamps out-of-range to max`() {
        GeneralVariables.setSpectrumBinAggregation(42)
        assertThat(GeneralVariables.getSpectrumBinAggregation()).isEqualTo(0)
        GeneralVariables.setSpectrumBinAggregation(-1)
        assertThat(GeneralVariables.getSpectrumBinAggregation()).isEqualTo(0)
    }
}
