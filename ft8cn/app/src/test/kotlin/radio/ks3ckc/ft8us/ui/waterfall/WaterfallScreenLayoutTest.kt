package radio.ks3ckc.ft8us.ui.waterfall

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the waterfall layout geometry constants. [SpectrumStripHeight] is a
 * pure Compose Dp value (no Android runtime needed), so this is a plain JVM test.
 */
class WaterfallScreenLayoutTest {

    @Test
    fun spectrumStrip_isTallerThanOriginalAndTheRulerBelowIt() {
        // #206: the spectrum strip was enlarged from the original 56.dp. Guard
        // against a regression that shrinks it back below that legible height,
        // and keep it clearly taller than the 20.dp frequency ruler beneath it.
        assertThat(SpectrumStripHeight.value).isAtLeast(56f)
        assertThat(SpectrumStripHeight.value).isGreaterThan(20f)
    }
}
