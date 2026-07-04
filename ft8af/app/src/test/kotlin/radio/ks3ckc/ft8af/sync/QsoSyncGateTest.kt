package radio.ks3ckc.ft8af.sync

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = false)).isFalse()
    }

    @Test
    fun allowed_onFirstRun_whenEnabled() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = true)).isTrue()
    }

    @Test
    fun deniedTryStart_doesNotMutateState() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        // Denied for no-service: must not mark anything started, so a later enabled
        // trigger at the same instant is still allowed.
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = false)).isFalse()
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = true)).isTrue()
    }

    @Test
    fun singleFlight_blocksSecondRun_untilFinished() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = true)).isTrue()

        // A trigger arriving while the first run is in flight is rejected even though
        // the debounce window would otherwise allow it.
        assertThat(gate.tryStart(now = 5000, anyServiceEnabled = true)).isFalse()

        gate.markFinished()
        // markFinished re-opens the gate (5000 is past the 1000ms window from start@0).
        assertThat(gate.tryStart(now = 5000, anyServiceEnabled = true)).isTrue()
    }

    @Test
    fun twoRacingTriggers_onlyOneWins() {
        // The TOCTOU case from issue #399: app-start and network-available fire at the
        // same instant. With check-and-mark in one atomic call, exactly one may win.
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = true)).isTrue()
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = true)).isFalse()
    }

    @Test
    fun twoRacingTriggers_concurrentThreads_onlyOneWins() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        val ready = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val threads = (1..2).map {
            Thread {
                ready.await()
                if (gate.tryStart(now = 0, anyServiceEnabled = true)) wins.incrementAndGet()
            }.apply { start() }
        }
        ready.countDown()
        threads.forEach { it.join() }
        assertThat(wins.get()).isEqualTo(1)
    }

    @Test
    fun debounce_blocksWithinWindow_allowsAfter() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = true)).isTrue()
        gate.markFinished()

        // Within the 1000ms window from the last *started* run: blocked.
        assertThat(gate.tryStart(now = 999, anyServiceEnabled = true)).isFalse()
        // Exactly at the window boundary: allowed.
        assertThat(gate.tryStart(now = 1000, anyServiceEnabled = true)).isTrue()
    }

    @Test
    fun debounce_measuredFromStart_notFinish() {
        val gate = QsoSyncGate(minIntervalMs = 1000)
        assertThat(gate.tryStart(now = 0, anyServiceEnabled = true)).isTrue()
        gate.markFinished()
        assertThat(gate.tryStart(now = 1000, anyServiceEnabled = true)).isTrue()
        gate.markFinished()

        // Window is measured from the most recent start (1000), not the first.
        assertThat(gate.tryStart(now = 1500, anyServiceEnabled = true)).isFalse()
        assertThat(gate.tryStart(now = 2000, anyServiceEnabled = true)).isTrue()
    }
}
