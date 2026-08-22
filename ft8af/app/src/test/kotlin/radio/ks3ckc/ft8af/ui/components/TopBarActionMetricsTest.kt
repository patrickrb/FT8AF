package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit test for [topBarActionMetrics], the rule that sizes a [TopBar] action
 * row for the screen it is drawn on. The metrics are plain Compose [Dp] values,
 * so this needs no Android runtime.
 *
 * The compact case is a regression guard: on a 360dp phone in pt-BR/es the
 * subtitle is wide enough that any extra width spent on the three Decode action
 * icons pushed the last one off the right edge.
 */
class TopBarActionMetricsTest {

    /** TopBar's own horizontal padding, which the action row has to fit inside. */
    private val topBarHorizontalPaddingDp = 18f * 2

    @Test
    fun compactPhone_keepsTheIconsSmallAndTouching() {
        val metrics = topBarActionMetrics(360)

        assertThat(metrics.buttonSize.value).isEqualTo(36f)
        assertThat(metrics.spacing.value).isEqualTo(0f)
    }

    @Test
    fun compactPhone_leavesRoomForTheTitleColumn() {
        val metrics = topBarActionMetrics(360)

        // Three action buttons plus the gaps between them.
        val rowWidth = 3 * metrics.buttonSize.value + 2 * metrics.spacing.value
        val leftForText = 360f - topBarHorizontalPaddingDp - rowWidth

        assertThat(rowWidth).isAtMost(120f)
        // Enough for "Decode" plus a wrapped localized subtitle.
        assertThat(leftForText).isAtLeast(180f)
    }

    @Test
    fun tablet_getsBiggerAndMoreSpacedIcons() {
        val phone = topBarActionMetrics(360)

        listOf(800, 1024, 1280).forEach { width ->
            val tablet = topBarActionMetrics(width)
            assertThat(tablet.buttonSize.value).isGreaterThan(phone.buttonSize.value)
            assertThat(tablet.iconSize.value).isGreaterThan(phone.iconSize.value)
            assertThat(tablet.spacing.value).isGreaterThan(phone.spacing.value)
        }
    }

    @Test
    fun expandedWidth_isRoomierThanMedium() {
        val medium = topBarActionMetrics(700)
        val expanded = topBarActionMetrics(900)

        assertThat(expanded.buttonSize.value).isAtLeast(medium.buttonSize.value)
        assertThat(expanded.spacing.value).isGreaterThan(medium.spacing.value)
    }

    @Test
    fun breakpointsFollowTheMaterialWindowClasses() {
        assertThat(topBarActionMetrics(599)).isEqualTo(topBarActionMetrics(320))
        assertThat(topBarActionMetrics(600)).isEqualTo(topBarActionMetrics(839))
        assertThat(topBarActionMetrics(840)).isEqualTo(topBarActionMetrics(1600))
        assertThat(topBarActionMetrics(599)).isNotEqualTo(topBarActionMetrics(600))
        assertThat(topBarActionMetrics(839)).isNotEqualTo(topBarActionMetrics(840))
    }

    @Test
    fun metricsNeverShrinkAsTheScreenGrows() {
        var previous = topBarActionMetrics(240)

        (240..1600 step 20).forEach { width ->
            val metrics = topBarActionMetrics(width)
            assertThat(metrics.buttonSize.value).isAtLeast(previous.buttonSize.value)
            assertThat(metrics.iconSize.value).isAtLeast(previous.iconSize.value)
            assertThat(metrics.spacing.value).isAtLeast(previous.spacing.value)
            previous = metrics
        }
    }

    @Test
    fun everyGlyphFitsInsideItsButton() {
        listOf(320, 360, 600, 800, 840, 1280).forEach { width ->
            val metrics = topBarActionMetrics(width)
            assertThat(metrics.iconSize.value).isLessThan(metrics.buttonSize.value)
        }
    }
}
