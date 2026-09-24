package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [drawerTargetExpanded] — the pure gesture rule deciding whether the
 * operate-controls drawer (design 3a/3b) settles open or collapsed after a drag on its handle.
 * No Android/Compose runtime needed. Sign convention: negative dy = upward drag.
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
}
