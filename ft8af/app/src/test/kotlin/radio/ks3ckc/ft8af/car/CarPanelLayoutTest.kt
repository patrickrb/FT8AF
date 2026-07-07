package radio.ks3ckc.ft8af.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Placement math for the full-width car status banners ([carBannerBoxTop]/[carBannerBoxBottom]). Pure. */
class CarPanelLayoutTest {

    @Test
    fun topBannerSpansFullWidthAtSafeTop() {
        val box = carBannerBoxTop(surfaceWidth = 1024, contentTop = 180, barH = 120f)
        assertThat(box.left).isWithin(TOL).of(0f)
        assertThat(box.right).isWithin(TOL).of(1024f)   // full surface width
        assertThat(box.top).isWithin(TOL).of(180f)      // pinned to safe-area top
        assertThat(box.bottom).isWithin(TOL).of(300f)   // top + barH
    }

    @Test
    fun bottomBannerSpansFullWidthEndingAtSafeBottom() {
        val box = carBannerBoxBottom(surfaceWidth = 1024, contentBottom = 660, barH = 120f)
        assertThat(box.left).isWithin(TOL).of(0f)
        assertThat(box.right).isWithin(TOL).of(1024f)   // full surface width
        assertThat(box.bottom).isWithin(TOL).of(660f)   // pinned to safe-area bottom
        assertThat(box.top).isWithin(TOL).of(540f)      // bottom - barH
    }

    @Test
    fun bannersLeaveAClearGapInTheMiddle() {
        // Flush to the surface edges (top:0 / bottom:height) on a 1024x768 surface.
        val top = carBannerBoxTop(1024, 0, 120f)
        val bottom = carBannerBoxBottom(1024, 768, 120f)
        assertThat(top.top).isWithin(TOL).of(0f)
        assertThat(bottom.bottom).isWithin(TOL).of(768f)
        assertThat(top.bottom).isLessThan(bottom.top)
        assertThat(bottom.top - top.bottom).isGreaterThan(200f) // meaningful map band
    }

    private companion object {
        const val TOL = 0.001f
    }
}
