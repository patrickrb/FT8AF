package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [sheetBackHandlerActive], the gate that keeps [FT8AFBottomSheet]'s
 * Android-Back handler installed throughout the sheet's exit animation (the
 * follow-up to issue #201, so a Back press during slide-out can't reach the
 * activity's app-exit path while the sheet is still on-screen).
 */
class SheetBackHandlerActiveTest {

    @Test
    fun active_whenFullyShown() {
        // Settled, visible: target and current both true.
        assertThat(sheetBackHandlerActive(currentState = true, targetState = true)).isTrue()
    }

    @Test
    fun active_whileExiting_targetHiddenButStillOnScreen() {
        // The exit window the fix exists for: visible flipped to false
        // (targetState), but the slide-out hasn't finished so currentState is
        // still true and Back must remain captured.
        assertThat(sheetBackHandlerActive(currentState = true, targetState = false)).isTrue()
    }

    @Test
    fun active_whileEntering() {
        // Opening: target true, current not yet true.
        assertThat(sheetBackHandlerActive(currentState = false, targetState = true)).isTrue()
    }

    @Test
    fun inactive_whenFullyHidden() {
        // Settled, hidden: Back propagates to the app handler.
        assertThat(sheetBackHandlerActive(currentState = false, targetState = false)).isFalse()
    }
}
