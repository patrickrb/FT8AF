package radio.ks3ckc.ft8af

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards which safe-drawing sides the root content pads for. The Composable itself can't
 * be unit-tested, so the side selection lives in [appContentInsets] and is checked here
 * against synthetic insets: top and both horizontal sides must survive (portrait notch,
 * landscape notch on either edge), bottom must be dropped (hidden nav bar / IME).
 */
class AppInsetsTest {

    private val density = Density(1f)

    /** Asymmetric values so each side is distinguishable in the assertions. */
    private val raw = WindowInsets(left = 10, top = 20, right = 30, bottom = 40)

    @Test
    fun keepsTop_forThePortraitNotch() {
        assertThat(appContentInsets(raw).getTop(density)).isEqualTo(20)
    }

    @Test
    fun keepsBothHorizontalSides_forTheLandscapeNotch() {
        val insets = appContentInsets(raw)
        assertThat(insets.getLeft(density, LayoutDirection.Ltr)).isEqualTo(10)
        assertThat(insets.getRight(density, LayoutDirection.Ltr)).isEqualTo(30)
    }

    @Test
    fun dropsBottom_navBarIsHiddenAndImeIsHandledByTheField() {
        assertThat(appContentInsets(raw).getBottom(density)).isEqualTo(0)
    }

    @Test
    fun zeroInsetsPadNothing_layoutUnchangedOnPhonesWithoutACutout() {
        val insets = appContentInsets(WindowInsets(0, 0, 0, 0))
        assertThat(insets.getTop(density)).isEqualTo(0)
        assertThat(insets.getLeft(density, LayoutDirection.Ltr)).isEqualTo(0)
        assertThat(insets.getRight(density, LayoutDirection.Ltr)).isEqualTo(0)
    }
}
