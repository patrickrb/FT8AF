package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [nextTxWindowSeconds] — the pure "seconds until my next transmit slot" math
 * for the redesigned strip's status-row countdown. No Android runtime needed.
 */
class NextTxWindowTest {

    @Test
    fun `off-slot counts down to the next boundary`() {
        // now is in slot index 1 (odd); the operator transmits on even slots (txSlot 0), so
        // the next window is the very next boundary at 30_000ms — 6s away.
        assertThat(nextTxWindowSeconds(nowMs = 24_000L, slotMillis = 15_000L, txSlot = 0))
            .isEqualTo(6)
    }

    @Test
    fun `matching slot points at the following window, never zero`() {
        assertThat(nextTxWindowSeconds(nowMs = 0L, slotMillis = 15_000L, txSlot = 0))
            .isEqualTo(30)
    }

    @Test
    fun `odd txSlot mirrors even txSlot`() {
        assertThat(nextTxWindowSeconds(nowMs = 0L, slotMillis = 15_000L, txSlot = 1))
            .isEqualTo(15)
    }

    @Test
    fun `always a positive, upcoming number across a whole cycle`() {
        for (ms in 0 until 30_000 step 250) {
            val secs = nextTxWindowSeconds(ms.toLong(), 15_000L, txSlot = 0)
            assertThat(secs).isAtLeast(1)
            assertThat(secs).isAtMost(30)
        }
    }

    @Test
    fun `ft4 fast slot scales the window`() {
        assertThat(nextTxWindowSeconds(nowMs = 0L, slotMillis = 7_500L, txSlot = 0))
            .isEqualTo(15)
    }

    @Test
    fun `non-positive slotMillis falls back to 15s grid`() {
        assertThat(nextTxWindowSeconds(nowMs = 0L, slotMillis = 0L, txSlot = 1))
            .isEqualTo(15)
    }
}
