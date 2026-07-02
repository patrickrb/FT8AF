package radio.ks3ckc.ft8af.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [QsoSyncGate] — the single-flight + debounce decision behind the
 * connectivity-triggered auto-sync. Pure logic: no Android types, an injected clock,
 * so no Robolectric runner is needed.
 */
class QsoSyncGateTest {

    @Test
    fun blocked_whenNoServiceEnabled() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.shouldRun(now = 0, anyServiceEnabled = false)).isFalse()
    }

    @Test
    fun allowed_onFirstRun_whenEnabled() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.shouldRun(now = 0, anyServiceEnabled = true)).isTrue()
    }

    @Test
    fun singleFlight_blocksSecondRun_untilFinished() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.shouldRun(now = 0, anyServiceEnabled = true)).isTrue()
        gate.markStarted(now = 0)

        // A trigger arriving while the first run is in flight is rejected even though
        // the debounce window would otherwise allow it.
        assertThat(gate.shouldRun(now = 5000, anyServiceEnabled = true)).isFalse()

        gate.markFinished()
        // markFinished re-opens the gate (5000 is past the 1000ms window from start@0).
        assertThat(gate.shouldRun(now = 5000, anyServiceEnabled = true)).isTrue()
    }

    @Test
    fun debounce_blocksWithinWindow_allowsAfter() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.shouldRun(now = 0, anyServiceEnabled = true)).isTrue()
        gate.markStarted(now = 0)
        gate.markFinished()

        // Within the 1000ms window from the last *started* run: blocked.
        assertThat(gate.shouldRun(now = 999, anyServiceEnabled = true)).isFalse()
        // Exactly at the window boundary: allowed.
        assertThat(gate.shouldRun(now = 1000, anyServiceEnabled = true)).isTrue()
    }

    @Test
    fun debounce_measuredFromStart_notFinish() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        gate.markStarted(now = 0)
        gate.markFinished()
        gate.markStarted(now = 1000)
        gate.markFinished()

        // Window is measured from the most recent start (1000), not the first.
        assertThat(gate.shouldRun(now = 1500, anyServiceEnabled = true)).isFalse()
        assertThat(gate.shouldRun(now = 2000, anyServiceEnabled = true)).isTrue()
    }
}
