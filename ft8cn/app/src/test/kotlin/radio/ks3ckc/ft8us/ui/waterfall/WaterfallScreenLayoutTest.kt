package radio.ks3ckc.ft8us.ui.waterfall

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the waterfall layout geometry constants. [SpectrumStripHeight] is a
 * pure Compose Dp value (no Android runtime needed), so this is a plain JVM test.
 */
class WaterfallScreenLayoutTest {

    @Test
    fun spectrumStrip_isTallerThanOriginal() {
        // #206: the spectrum strip was enlarged from the original 56.dp. Guard
        // against a regression that shrinks it back below that legible height.
        assertThat(SpectrumStripHeight.value).isAtLeast(56f)
    }

    @Test
    fun bottomStripHeight_isAPositiveOffset() {
        // The floating QSO panel offsets itself up by this much so the waterfall's
        // bottom info/toggle strip stays reachable during a QSO. It must be a real
        // (positive) reservation, and a thin strip rather than a full-height panel.
        assertThat(WaterfallBottomStripHeight.value).isGreaterThan(0f)
        assertThat(WaterfallBottomStripHeight.value).isLessThan(SpectrumStripHeight.value)
    }
}
