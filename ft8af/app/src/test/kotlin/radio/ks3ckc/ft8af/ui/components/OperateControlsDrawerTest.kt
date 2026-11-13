package radio.ks3ckc.ft8af.ui.components

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [drawerTargetExpanded] — the pure gesture rule deciding whether the
 * operate-controls drawer (design 3a/3b) settles open or collapsed after a drag on its handle —
 * plus the collapsed-peek geometry invariants. No Android/Compose runtime needed. Sign convention:
 * negative dy = upward drag.
 */
class OperateControlsDrawerTest {

    private val threshold = 48f

    @Test
    fun `upward drag past threshold from collapsed expands`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = false, dragDy = -60f, thresholdPx = threshold))
            .isTrue()
    }

    @Test
    fun `upward drag exactly at threshold from collapsed expands`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = false, dragDy = -48f, thresholdPx = threshold))
            .isTrue()
    }

    @Test
    fun `small upward drag from collapsed stays collapsed`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = false, dragDy = -20f, thresholdPx = threshold))
            .isFalse()
    }

    @Test
    fun `downward drag from collapsed stays collapsed`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = false, dragDy = 200f, thresholdPx = threshold))
            .isFalse()
    }

    @Test
    fun `downward drag past threshold from expanded collapses`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = true, dragDy = 60f, thresholdPx = threshold))
            .isFalse()
    }

    @Test
    fun `downward drag exactly at threshold from expanded collapses`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = true, dragDy = 48f, thresholdPx = threshold))
            .isFalse()
    }

    @Test
    fun `small downward drag from expanded stays expanded`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = true, dragDy = 20f, thresholdPx = threshold))
            .isTrue()
    }

    @Test
    fun `upward drag from expanded stays expanded`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = true, dragDy = -200f, thresholdPx = threshold))
            .isTrue()
    }

    @Test
    fun `no drag leaves state unchanged`() {
        assertThat(drawerTargetExpanded(currentlyExpanded = false, dragDy = 0f, thresholdPx = threshold))
            .isFalse()
        assertThat(drawerTargetExpanded(currentlyExpanded = true, dragDy = 0f, thresholdPx = threshold))
            .isTrue()
    }

    // ---- Collapsed-peek geometry (see OperateDrawerPeekHeight) ----

    @Test
    fun `peek height is the handle, the primary row and its padding`() {
        assertThat(OperateDrawerPeekHeight).isEqualTo(
            DrawerHandleHeight + DrawerContentTopGap + OperatePrimaryRowHeight +
                DrawerContentBottomPad
        )
    }

    @Test
    fun `peek height clears the whole primary row`() {
        // Anything less and the collapsed sheet clips the Call CQ / Hunt buttons.
        assertThat(OperateDrawerPeekHeight)
            .isGreaterThan(DrawerHandleHeight + DrawerContentTopGap + OperatePrimaryRowHeight)
    }

    @Test
    fun `row gap exceeds the bottom padding so the next row cannot peek through`() {
        // The sheet is clipped DrawerContentBottomPad past the primary row's bottom edge; the
        // following row starts DrawerRowSpacing past it, so the gap has to be the larger of the two.
        assertThat(DrawerRowSpacing).isGreaterThan(DrawerContentBottomPad)
    }

    @Test
    fun `primary row has breathing room above and below it`() {
        // The 120.dp peek this replaced left 0.dp above the buttons and 6.dp below, which is what
        // made the collapsed drawer look jammed onto the tab bar.
        assertThat(DrawerContentTopGap).isAtLeast(8.dp)
        assertThat(DrawerContentBottomPad).isAtLeast(8.dp)
    }
}
