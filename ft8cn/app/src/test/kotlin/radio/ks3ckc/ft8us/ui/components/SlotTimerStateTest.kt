package radio.ks3ckc.ft8us.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
    fun `ft2 slot — 3point8s peaks at 4 seconds`() {
        val s = slotTimerState(nowMs = 0L, slotMillis = 3_800L)
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
