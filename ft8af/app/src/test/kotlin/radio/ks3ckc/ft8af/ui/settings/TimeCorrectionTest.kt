package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for the manual time-correction math (no Android/Compose deps,
 * so no Robolectric runner needed).
 */
class TimeCorrectionTest {

    @Test
    fun clamp_keepsValuesInRange() {
        assertThat(clampCorrectionMs(0)).isEqualTo(0)
        assertThat(clampCorrectionMs(500)).isEqualTo(500)
        assertThat(clampCorrectionMs(-500)).isEqualTo(-500)
        assertThat(clampCorrectionMs(TIME_CORRECTION_MAX_MS)).isEqualTo(5000)
        assertThat(clampCorrectionMs(TIME_CORRECTION_MIN_MS)).isEqualTo(-5000)
        // The reported real-world case: an offline phone needing ~3.4 s is now in range.
        assertThat(clampCorrectionMs(3400)).isEqualTo(3400)
    }

    @Test
    fun clamp_pinsBeyondRange() {
        assertThat(clampCorrectionMs(9000)).isEqualTo(5000)
        assertThat(clampCorrectionMs(-9000)).isEqualTo(-5000)
    }

    @Test
    fun step_addsAndClamps() {
        assertThat(stepCorrectionMs(0, 100)).isEqualTo(100)
        assertThat(stepCorrectionMs(0, -100)).isEqualTo(-100)
        assertThat(stepCorrectionMs(400, 500)).isEqualTo(900)
        // +0.5 s past the +5.0 s rail pins to the max.
        assertThat(stepCorrectionMs(4800, 500)).isEqualTo(5000)
        // -0.5 s past the -5.0 s rail pins to the min.
        assertThat(stepCorrectionMs(-4800, -500)).isEqualTo(-5000)
    }

    @Test
    fun suggested_subtractsAverageDt() {
        // Decodes land +0.6 s late in our window (clock fast) -> subtract 600 ms.
        assertThat(suggestedCorrectionMs(0, 0.6f)).isEqualTo(-600)
        // Decodes land -0.4 s early (clock slow) -> add 400 ms.
        assertThat(suggestedCorrectionMs(0, -0.4f)).isEqualTo(400)
        // Builds on the existing correction.
        assertThat(suggestedCorrectionMs(200, 0.3f)).isEqualTo(-100)
        // Already centered -> no change.
        assertThat(suggestedCorrectionMs(150, 0.0f)).isEqualTo(150)
    }

    @Test
    fun suggested_clampsToRange() {
        // Within ±5 s the suggestion passes through unclamped...
        assertThat(suggestedCorrectionMs(0, -3.4f)).isEqualTo(3400)
        // ...and only a larger-than-range DT pins to the rail.
        assertThat(suggestedCorrectionMs(0, -7.0f)).isEqualTo(5000)
        assertThat(suggestedCorrectionMs(0, 7.0f)).isEqualTo(-5000)
    }

    @Test
    fun format_signsAndZero() {
        assertThat(formatOffsetMs(0)).isEqualTo("0.0 s")
        assertThat(formatOffsetMs(600)).isEqualTo("+0.6 s")
        assertThat(formatOffsetMs(-300)).isEqualTo("-0.3 s")
        assertThat(formatOffsetMs(2000)).isEqualTo("+2.0 s")
        assertThat(formatOffsetMs(-2000)).isEqualTo("-2.0 s")
    }

    @Test
    fun format_roundsToOneDecimal() {
        assertThat(formatOffsetMs(640)).isEqualTo("+0.6 s")
        assertThat(formatOffsetMs(660)).isEqualTo("+0.7 s")
        assertThat(formatOffsetMs(-660)).isEqualTo("-0.7 s")
    }
}
