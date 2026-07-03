package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.StatusWarn
import radio.ks3ckc.ft8af.theme.StatusWorked

/**
 * Tests the extracted decision/geometry logic behind the RX input-level
 * indicator (issue #356): peak -> status classification, meter fill fraction,
 * percent readout, and status -> color mapping. Pure logic — no runner needed.
 */
class InputLevelIndicatorTest {

    // ------------------------------------------------------------------
    // classifyInputLevel
    // ------------------------------------------------------------------

    @Test
    fun silence_isTooLow() {
        assertThat(classifyInputLevel(0f)).isEqualTo(InputLevelStatus.TOO_LOW)
    }

    @Test
    fun belowLowThreshold_isTooLow() {
        assertThat(classifyInputLevel(0.249f)).isEqualTo(InputLevelStatus.TOO_LOW)
    }

    @Test
    fun lowBoundary_isJustRight() {
        // 25% is inclusive: the advertised window is 25-75%.
        assertThat(classifyInputLevel(0.25f)).isEqualTo(InputLevelStatus.JUST_RIGHT)
    }

    @Test
    fun midRange_isJustRight() {
        assertThat(classifyInputLevel(0.5f)).isEqualTo(InputLevelStatus.JUST_RIGHT)
    }

    @Test
    fun highBoundary_isJustRight() {
        // 75% is inclusive too.
        assertThat(classifyInputLevel(0.75f)).isEqualTo(InputLevelStatus.JUST_RIGHT)
    }

    @Test
    fun aboveHighThreshold_isTooHigh() {
        assertThat(classifyInputLevel(0.76f)).isEqualTo(InputLevelStatus.TOO_HIGH)
    }

    @Test
    fun justBelowClip_isTooHigh() {
        assertThat(classifyInputLevel(0.989f)).isEqualTo(InputLevelStatus.TOO_HIGH)
    }

    @Test
    fun clipThreshold_isClipping() {
        assertThat(classifyInputLevel(INPUT_LEVEL_CLIP)).isEqualTo(InputLevelStatus.CLIPPING)
    }

    @Test
    fun fullScale_isClipping() {
        assertThat(classifyInputLevel(1.0f)).isEqualTo(InputLevelStatus.CLIPPING)
    }

    @Test
    fun beyondFullScale_isClipping() {
        // Gain > 100% can push samples past +/-1.0.
        assertThat(classifyInputLevel(1.8f)).isEqualTo(InputLevelStatus.CLIPPING)
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
    // inputLevelColor
    // ------------------------------------------------------------------

    @Test
    fun colors_matchStatusSemantics() {
        assertThat(inputLevelColor(InputLevelStatus.TOO_LOW)).isEqualTo(StatusWorked)
        assertThat(inputLevelColor(InputLevelStatus.JUST_RIGHT)).isEqualTo(StatusConfirmed)
        assertThat(inputLevelColor(InputLevelStatus.TOO_HIGH)).isEqualTo(StatusWarn)
        assertThat(inputLevelColor(InputLevelStatus.CLIPPING)).isEqualTo(StatusBad)
    }
}
