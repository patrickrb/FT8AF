package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests [clampVolumePercent], the range guard behind [VolumeSliderDialog]
 * (Copilot review on PR #387).
 *
 * The dialog serves two ranges — TX volume 0..100 and RX input volume 0..200 —
 * and initializes from a persisted config value. A corrupted or imported
 * out-of-range value (settings import, issue #357) must start the slider at a
 * legal position, and the clamped value is what gets synced back and
 * re-persisted on dismiss.
 */
class VolumeClampTest {

    @Test
    fun inRangeValueIsUnchanged() {
        assertThat(clampVolumePercent(0, 100)).isEqualTo(0)
        assertThat(clampVolumePercent(55, 100)).isEqualTo(55)
        assertThat(clampVolumePercent(100, 100)).isEqualTo(100)
        assertThat(clampVolumePercent(150, INPUT_VOLUME_MAX)).isEqualTo(150)
        assertThat(clampVolumePercent(INPUT_VOLUME_MAX, INPUT_VOLUME_MAX)).isEqualTo(INPUT_VOLUME_MAX)
    }

    @Test
    fun aboveMaxClampsToMax() {
        // TX dialog (max 100): a persisted 150 must not start the slider at 150.
        assertThat(clampVolumePercent(150, 100)).isEqualTo(100)
        // Input dialog (max 200): a corrupted 500% import clamps to 200.
        assertThat(clampVolumePercent(500, INPUT_VOLUME_MAX)).isEqualTo(INPUT_VOLUME_MAX)
        assertThat(clampVolumePercent(Int.MAX_VALUE, INPUT_VOLUME_MAX)).isEqualTo(INPUT_VOLUME_MAX)
    }

    @Test
    fun belowZeroClampsToZero() {
        assertThat(clampVolumePercent(-1, 100)).isEqualTo(0)
        assertThat(clampVolumePercent(Int.MIN_VALUE, INPUT_VOLUME_MAX)).isEqualTo(0)
    }

    @Test
    fun stepArithmeticStaysInRange() {
        // The dialog's -5/+5 buttons route through the same clamp.
        assertThat(clampVolumePercent(3 - 5, 100)).isEqualTo(0)
        assertThat(clampVolumePercent(198 + 5, INPUT_VOLUME_MAX)).isEqualTo(INPUT_VOLUME_MAX)
    }

    @Test
    fun inputVolumeMaxMatchesNativeGainCap() {
        // The Kotlin slider bound and the Java config-load clamp
        // (InputAudioLevel.MAX_GAIN) must describe the same limit: 200% = 2.0x.
        assertThat(INPUT_VOLUME_MAX / 100f)
            .isEqualTo(com.k1af.ft8af.wave.InputAudioLevel.MAX_GAIN)
    }
}
