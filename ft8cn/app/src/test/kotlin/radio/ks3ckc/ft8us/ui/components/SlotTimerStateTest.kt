package radio.ks3ckc.ft8us.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8us.theme.Accent
import radio.ks3ckc.ft8us.theme.Signal

/**
 * Unit tests for [slotTimerState] — the pure slot-progress math extracted from
 * SlotTimerBar. No Android runtime needed: UtcTimer.sequential is plain modular
 * arithmetic and the rest is integer/float math.
 */
class SlotTimerStateTest {

    @Test
    fun `ft8 slot start — empty bar, full countdown, slot 0`() {
        val s = slotTimerState(nowMs = 0L, slotMillis = 15_000L)
        assertThat(s.progress).isEqualTo(0f)
        assertThat(s.secondsRemaining).isEqualTo(15)
        assertThat(s.currentSlot).isEqualTo(0)
    }

    @Test
    fun `ft8 mid-slot — half full, slot flips after the boundary`() {
        val s = slotTimerState(nowMs = 7_500L, slotMillis = 15_000L)
        assertThat(s.progress).isWithin(1e-4f).of(0.5f)
        // 7.5s left, rounded up.
        assertThat(s.secondsRemaining).isEqualTo(8)
        assertThat(s.currentSlot).isEqualTo(0)
        assertThat(slotTimerState(15_000L, 15_000L).currentSlot).isEqualTo(1)
    }

    @Test
    fun `ft4 slot rounds the 7point5s length up to 8`() {
        val s = slotTimerState(nowMs = 0L, slotMillis = 7_500L)
        assertThat(s.secondsRemaining).isEqualTo(8)
        assertThat(s.progress).isEqualTo(0f)
    }

    @Test
    fun `ft2 slot — 3point75s peaks at 4 seconds`() {
        val s = slotTimerState(nowMs = 0L, slotMillis = 3_750L)
        assertThat(s.secondsRemaining).isEqualTo(4)
    }

    @Test
    fun `zero slotMillis falls back to 15s instead of crashing`() {
        // The regression guard: a 0 slot length used to crash on nowMs % 0.
        val s = slotTimerState(nowMs = 3_000L, slotMillis = 0L)
        assertThat(s.progress).isWithin(1e-4f).of(0.2f)
        assertThat(s.secondsRemaining).isEqualTo(12)
        assertThat(s.currentSlot).isEqualTo(0)
    }

    @Test
    fun `negative slotMillis also falls back to 15s`() {
        val s = slotTimerState(nowMs = 0L, slotMillis = -5L)
        assertThat(s.progress).isEqualTo(0f)
        assertThat(s.secondsRemaining).isEqualTo(15)
    }

    @Test
    fun `even slot is orange, odd slot is blue`() {
        // Regression guard for the parity coloring: the even cycle (sequential == 0) must
        // be the warm Accent and the odd cycle (sequential == 1) the cool Signal. This
        // broke once when the bar was tied to the active-TX slot and rendered blue only.
        assertThat(slotBarColor(0)).isEqualTo(Accent)
        assertThat(slotBarColor(1)).isEqualTo(Signal)
    }

    @Test
    fun `slot color tracks parity for any slot index`() {
        for (slot in 0..5) {
            val expected = if (slot % 2 == 0) Accent else Signal
            assertThat(slotBarColor(slot)).isEqualTo(expected)
        }
    }

    @Test
    fun `slot color drives off the timer state slot index`() {
        // The bar feeds state.currentSlot into slotBarColor; verify the two stay consistent
        // so the start of the FT8 slot is orange and the next 15s slot is blue.
        assertThat(slotBarColor(slotTimerState(0L, 15_000L).currentSlot)).isEqualTo(Accent)
        assertThat(slotBarColor(slotTimerState(15_000L, 15_000L).currentSlot)).isEqualTo(Signal)
    }

    @Test
    fun `progress and countdown stay in range across a whole slot`() {
        for (ms in 0 until 15_000 step 250) {
            val s = slotTimerState(ms.toLong(), 15_000L)
            assertThat(s.progress).isAtLeast(0f)
            assertThat(s.progress).isLessThan(1f)
            assertThat(s.secondsRemaining).isAtLeast(0)
            assertThat(s.secondsRemaining).isAtMost(15)
        }
    }
}
