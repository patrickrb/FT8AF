package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import com.k1af.ft8af.spectrum.AudioInputLevel
import com.k1af.ft8af.wave.InputAudioLevel
import org.junit.Test
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.StatusWarn
import radio.ks3ckc.ft8af.theme.StatusWorked
import radio.ks3ckc.ft8af.theme.TextMuted

/**
 * Tests the extracted decision/geometry logic behind the RX input-level
 * indicator. Since issue #405 the classification delegates to
 * [AudioInputLevel] — the same peak/RMS dBFS thresholds the legacy spectrum
 * meter uses — so these cases pin the delegation and the strip's own mappers
 * (fill fraction, percent readout, color, status text). Pure logic — no
 * runner needed.
 */
class InputLevelIndicatorTest {

    private fun levels(peak: Float, rms: Float) = InputAudioLevel.Levels(peak, rms)

    // ------------------------------------------------------------------
    // classifyInputLevel — one classifier with the legacy meter (issue #405)
    // ------------------------------------------------------------------

    @Test
    fun nullLevels_isSilent() {
        assertThat(classifyInputLevel(null)).isEqualTo(AudioInputLevel.Status.SILENT)
    }

    @Test
    fun digitalSilence_isSilent() {
        assertThat(classifyInputLevel(levels(0f, 0f)))
            .isEqualTo(AudioInputLevel.Status.SILENT)
    }

    @Test
    fun quietInput_isLow() {
        // RMS -60 dBFS: audible but below the -45 dBFS weak-signal floor.
        assertThat(classifyInputLevel(levels(0.01f, 0.001f)))
            .isEqualTo(AudioInputLevel.Status.LOW)
    }

    @Test
    fun healthyInput_isGood() {
        // RMS ~ -26 dBFS with peaks well under the -3 dBFS hot line.
        assertThat(classifyInputLevel(levels(0.3f, 0.05f)))
            .isEqualTo(AudioInputLevel.Status.GOOD)
    }

    @Test
    fun hotPeaks_areHigh() {
        // Peak -0.9 dBFS: above the -3 dBFS hot threshold, below the clip point.
        assertThat(classifyInputLevel(levels(0.9f, 0.1f)))
            .isEqualTo(AudioInputLevel.Status.HIGH)
    }

    @Test
    fun pinnedPeaks_areClipping() {
        assertThat(classifyInputLevel(levels(0.99f, 0.3f)))
            .isEqualTo(AudioInputLevel.Status.CLIPPING)
        // Gain > 100% can push samples past +/-1.0.
        assertThat(classifyInputLevel(levels(1.8f, 0.5f)))
            .isEqualTo(AudioInputLevel.Status.CLIPPING)
    }

    @Test
    fun agreesWithTheLegacyMeterOnTheSameAudio() {
        // The whole point of issue #405: the same peak/RMS pair must classify
        // identically to AudioInputLevel (what SpectrumFragment shows).
        val cases = listOf(
            0f to 0f,
            0.01f to 0.001f,
            0.3f to 0.05f,
            0.9f to 0.1f,
            0.99f to 0.3f,
        )
        for ((peak, rms) in cases) {
            assertThat(classifyInputLevel(levels(peak, rms)))
                .isEqualTo(AudioInputLevel.fromPeakRms(peak, rms).status)
        }
    }

    // ------------------------------------------------------------------
    // inputLevelFraction / inputLevelPercent
    // ------------------------------------------------------------------

    @Test
    fun fraction_passesThroughInRange() {
        assertThat(inputLevelFraction(0.42f)).isEqualTo(0.42f)
    }

    @Test
    fun fraction_clampsAboveFullScale() {
        assertThat(inputLevelFraction(1.7f)).isEqualTo(1.0f)
    }

    @Test
    fun fraction_clampsNegative() {
        assertThat(inputLevelFraction(-0.3f)).isEqualTo(0f)
    }

    @Test
    fun percent_roundsToInt() {
        assertThat(inputLevelPercent(0.499f)).isEqualTo(50)
        assertThat(inputLevelPercent(0.254f)).isEqualTo(25)
    }

    @Test
    fun percent_clampsTo100() {
        assertThat(inputLevelPercent(2.0f)).isEqualTo(100)
    }

    @Test
    fun percent_zeroForSilence() {
        assertThat(inputLevelPercent(0f)).isEqualTo(0)
    }

    // ------------------------------------------------------------------
    // inputLevelColor / inputLevelStatusText
    // ------------------------------------------------------------------

    @Test
    fun colors_matchStatusSemantics() {
        assertThat(inputLevelColor(AudioInputLevel.Status.SILENT)).isEqualTo(TextMuted)
        assertThat(inputLevelColor(AudioInputLevel.Status.LOW)).isEqualTo(StatusWorked)
        assertThat(inputLevelColor(AudioInputLevel.Status.GOOD)).isEqualTo(StatusConfirmed)
        assertThat(inputLevelColor(AudioInputLevel.Status.HIGH)).isEqualTo(StatusWarn)
        assertThat(inputLevelColor(AudioInputLevel.Status.CLIPPING)).isEqualTo(StatusBad)
    }

    @Test
    fun statusText_coversEveryStatus() {
        assertThat(inputLevelStatusText(AudioInputLevel.Status.SILENT))
            .isEqualTo(R.string.input_level_too_low)
        assertThat(inputLevelStatusText(AudioInputLevel.Status.LOW))
            .isEqualTo(R.string.input_level_too_low)
        assertThat(inputLevelStatusText(AudioInputLevel.Status.GOOD))
            .isEqualTo(R.string.input_level_good)
        assertThat(inputLevelStatusText(AudioInputLevel.Status.HIGH))
            .isEqualTo(R.string.input_level_too_high)
        assertThat(inputLevelStatusText(AudioInputLevel.Status.CLIPPING))
            .isEqualTo(R.string.input_level_clipping)
    }
}
