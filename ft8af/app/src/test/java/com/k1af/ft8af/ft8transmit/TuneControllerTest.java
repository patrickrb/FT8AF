package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

/**
 * Coverage for {@link TuneController} — the safety state machine behind the
 * Tune carrier (issue #408). Plain JUnit with an injected fake clock, so the
 * hard max-on timeout is proven deterministically.
 */
public class TuneControllerTest {

    /** Manually-advanced clock. */
    private static final class FakeClock implements TuneController.Clock {
        long now = 1_000_000L;

        @Override
        public long nowMs() {
            return now;
        }
    }

    @Test
    public void startArmsTheSession() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        assertThat(c.isActive()).isFalse();
        assertThat(c.tryStart(10)).isTrue();
        assertThat(c.isActive()).isTrue();
        assertThat(c.shouldContinue()).isTrue();
        assertThat(c.remainingMs()).isEqualTo(10_000L);
    }

    @Test
    public void doubleStart_isRefused() {
        TuneController c = new TuneController(new FakeClock());
        assertThat(c.tryStart(10)).isTrue();
        assertThat(c.tryStart(10)).isFalse();
    }

    @Test
    public void timeout_stopsExactlyAtTheDeadline() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markCarrierOn();

        clock.now += 9_999;
        assertThat(c.shouldContinue()).isTrue();

        clock.now += 1; // exactly the deadline
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.isActive()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void userStop_winsOverALaterTimeout() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.requestStop(TuneController.STOP_USER);
        assertThat(c.isActive()).isFalse();

        // A later poll (or defensive stop) must not overwrite the first reason.
        clock.now += 60_000;
        assertThat(c.shouldContinue()).isFalse();
        c.requestStop(TuneController.STOP_ERROR);
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_USER);
    }

    @Test
    public void txStart_stopsTheSession() {
        TuneController c = new TuneController(new FakeClock());
        c.tryStart(10);
        c.requestStop(TuneController.STOP_TX);
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TX);
    }

    @Test
    public void stopWhenIdle_isANoOp() {
        TuneController c = new TuneController(new FakeClock());
        c.requestStop(TuneController.STOP_USER); // nothing running
        assertThat(c.lastStopReason()).isNull();
        assertThat(c.isActive()).isFalse();
    }

    @Test
    public void restartAfterStop_getsAFreshDeadlineAndReason() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.requestStop(TuneController.STOP_USER);

        clock.now += 5_000;
        assertThat(c.tryStart(20)).isTrue();
        assertThat(c.remainingMs()).isEqualTo(20_000L);
        c.markCarrierOn();
        clock.now += 20_000;
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void elapsedAndRemaining_trackTheClock() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markCarrierOn();
        clock.now += 3_000;
        assertThat(c.elapsedMs()).isEqualTo(3_000L);
        assertThat(c.remainingMs()).isEqualTo(7_000L);
        c.requestStop(TuneController.STOP_USER);
        assertThat(c.elapsedMs()).isEqualTo(0L);
        assertThat(c.remainingMs()).isEqualTo(0L);
    }

    @Test
    public void deadline_countsFromCarrierOnNotFromStart() {
        // #982: PTT/audio setup must not eat into the operator's tune time.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long t0 = clock.now;
        c.tryStart(5);
        clock.now = t0 + 1_500;
        assertThat(c.markCarrierOn()).isEqualTo(1_500L);

        clock.now = t0 + 6_400;
        assertThat(c.shouldContinue()).isTrue();
        clock.now = t0 + 6_500; // 5 s after carrier on
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void setupGrace_boundsASessionThatNeverReachesCarrierOn() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long t0 = clock.now;
        c.tryStart(5);
        clock.now = t0 + 5_000 + TuneController.SETUP_GRACE_MS - 1;
        assertThat(c.shouldContinue()).isTrue();
        clock.now = t0 + 5_000 + TuneController.SETUP_GRACE_MS;
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void carrierOnAfterTheSetupGrace_timesOutInsteadOfRearming() {
        // The worker doesn't poll during setup; a late carrier-on must not
        // hand a hung setup a fresh max-on.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(5);
        clock.now += 5_000 + TuneController.SETUP_GRACE_MS;
        assertThat(c.markCarrierOn()).isEqualTo(-1L);
        assertThat(c.isActive()).isFalse();
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void slowSetupWithinTheProvisionalDeadline_neverExtendsTheSession() {
        // Setup took longer than the grace but carrier-on still beat the
        // provisional deadline: the carrier gets what is left of it, never a
        // fresh max-on that would keep PTT keyed past max-on plus the grace.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long t0 = clock.now;
        c.tryStart(5);
        clock.now = t0 + 6_900;
        assertThat(c.markCarrierOn()).isEqualTo(6_900L);
        assertThat(c.remainingMs()).isEqualTo(100L);
        clock.now = t0 + 5_000 + TuneController.SETUP_GRACE_MS - 1;
        assertThat(c.shouldContinue()).isTrue();
        clock.now = t0 + 5_000 + TuneController.SETUP_GRACE_MS;
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void setupAtTheGraceLimit_stillGetsTheFullMaxOn() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(5);
        clock.now += TuneController.SETUP_GRACE_MS;
        c.markCarrierOn();
        assertThat(c.remainingMs()).isEqualTo(5_000L);
    }

    @Test
    public void remaining_isTheFullMaxOnRightAfterCarrierOn() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(5);
        clock.now += 1_200;
        // Setup time never shows on the chip, and the grace never inflates it.
        assertThat(c.remainingMs()).isEqualTo(5_000L);
        c.markCarrierOn();
        assertThat(c.remainingMs()).isEqualTo(5_000L);
        assertThat(c.elapsedMs()).isEqualTo(0L);
    }

    @Test
    public void secondCarrierOn_doesNotPushTheDeadlineOut() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long t0 = clock.now;
        c.tryStart(5);
        c.markCarrierOn();
        clock.now = t0 + 3_000;
        assertThat(c.markCarrierOn()).isEqualTo(-1L);
        assertThat(c.remainingMs()).isEqualTo(2_000L);
        clock.now = t0 + 5_000;
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void carrierOn_whenIdleIsANoOp() {
        TuneController c = new TuneController(new FakeClock());
        assertThat(c.markCarrierOn()).isEqualTo(-1L);
        assertThat(c.isActive()).isFalse();
    }

    @Test
    public void restart_needsItsOwnCarrierOn() {
        // A new session must not inherit the previous session's carrier-on.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(5);
        c.markCarrierOn();
        c.requestStop(TuneController.STOP_USER);
        long t1 = clock.now;
        c.tryStart(5);
        clock.now = t1 + 500;
        assertThat(c.markCarrierOn()).isEqualTo(500L);
        clock.now = t1 + 5_499;
        assertThat(c.shouldContinue()).isTrue();
    }

    @Test
    public void maxOnSeconds_isClampedToTheLegalRange() {
        assertThat(TuneController.clampMaxOnSeconds(10)).isEqualTo(10);
        assertThat(TuneController.clampMaxOnSeconds(TuneController.MIN_MAX_ON_SECONDS))
                .isEqualTo(TuneController.MIN_MAX_ON_SECONDS);
        assertThat(TuneController.clampMaxOnSeconds(TuneController.MAX_MAX_ON_SECONDS))
                .isEqualTo(TuneController.MAX_MAX_ON_SECONDS);
        // Below the minimum but positive: clamp up.
        assertThat(TuneController.clampMaxOnSeconds(1))
                .isEqualTo(TuneController.MIN_MAX_ON_SECONDS);
        // Zero/negative (corrupted config): fall back to the default, never "forever".
        assertThat(TuneController.clampMaxOnSeconds(0))
                .isEqualTo(TuneController.DEFAULT_MAX_ON_SECONDS);
        assertThat(TuneController.clampMaxOnSeconds(-5))
                .isEqualTo(TuneController.DEFAULT_MAX_ON_SECONDS);
        // Above the ceiling: clamp down.
        assertThat(TuneController.clampMaxOnSeconds(600))
                .isEqualTo(TuneController.MAX_MAX_ON_SECONDS);
    }

    @Test
    public void tryStart_usesTheClampedTimeout() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(600); // clamps to 60 s
        assertThat(c.remainingMs()).isEqualTo(TuneController.MAX_MAX_ON_SECONDS * 1000L);
    }

    @Test
    public void swrHalt_notSuppressedBeforeAnyTune() {
        TuneController c = new TuneController(new FakeClock());
        assertThat(c.suppressesSwrHalt(false)).isFalse();
    }

    @Test
    public void swrHalt_suppressedAfterStopUntilPttDrops() {
        // #943: the operator's stop clears active immediately, but the carrier is
        // still up while the tone ramps out and PTT is released.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markKeyed();
        assertThat(c.suppressesSwrHalt(false)).isTrue();

        c.requestStop(TuneController.STOP_USER);
        assertThat(c.isActive()).isFalse();
        clock.now += TuneController.KEYUP_TAIL_MAX_MS - 1; // a slow key-up must not open a gap
        assertThat(c.suppressesSwrHalt(false)).isTrue();
    }

    @Test
    public void swrHalt_suppressedForTheSettleWindowAfterKeyUp() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markKeyed();
        c.requestStop(TuneController.STOP_USER);
        c.markUnkeyed();

        clock.now += TuneController.SWR_SETTLE_MS - 1;
        assertThat(c.suppressesSwrHalt(false)).isTrue();

        clock.now += 1; // window over: an FT8 over's high SWR halts again
        assertThat(c.suppressesSwrHalt(false)).isFalse();
    }

    @Test
    public void swrHalt_timeoutStopAlsoCovered() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markKeyed();
        c.markCarrierOn();
        clock.now += 10_000;
        assertThat(c.shouldContinue()).isFalse(); // timeout fired
        assertThat(c.suppressesSwrHalt(false)).isTrue();
    }

    @Test
    public void swrHalt_stuckKeyUpLapsesAfterTheTailCap() {
        // A teardown hung on a dead CAT link must not leave protection off.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markKeyed();
        c.requestStop(TuneController.STOP_USER);
        clock.now += TuneController.KEYUP_TAIL_MAX_MS;
        assertThat(c.suppressesSwrHalt(false)).isFalse();
    }

    @Test
    public void swrHalt_neverSuppressedWhileAnFt8OverIsInProgress() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markKeyed();
        assertThat(c.suppressesSwrHalt(true)).isFalse();
        c.requestStop(TuneController.STOP_TX);
        c.markUnkeyed();
        assertThat(c.suppressesSwrHalt(true)).isFalse();
    }

    @Test
    public void swrHalt_revokedByAnFt8KeyDownDuringTheTail() {
        // An FT8 over that preempts a tune keys while the tune is still ramping
        // out; its readings must halt, and no settle window may follow.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markKeyed();
        c.requestStop(TuneController.STOP_TX);
        c.revokeSwrExemption();
        assertThat(c.suppressesSwrHalt(false)).isFalse();
        c.markUnkeyed();
        assertThat(c.suppressesSwrHalt(false)).isFalse();
    }

    @Test
    public void swrHalt_revocationClearedByTheNextTune() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.revokeSwrExemption();
        assertThat(c.tryStart(10)).isTrue();
        c.markKeyed();
        assertThat(c.suppressesSwrHalt(false)).isTrue();
    }

    @Test
    public void swrHalt_oldWorkerKeyUpDoesNotEndANewTunesTail() {
        // Stop then Tune again quickly: worker B keys while worker A is still in
        // its teardown. A's key-up must not end B's exemption.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        c.markKeyed(); // A
        c.requestStop(TuneController.STOP_USER);
        assertThat(c.tryStart(10)).isTrue();
        c.markKeyed(); // B
        c.markUnkeyed(); // A finishes
        c.requestStop(TuneController.STOP_USER); // B stopped, still ramping out
        clock.now += TuneController.SWR_SETTLE_MS + 1;
        assertThat(c.suppressesSwrHalt(false)).isTrue();

        c.markUnkeyed(); // B finishes: its own settle window starts now
        clock.now += TuneController.SWR_SETTLE_MS - 1;
        assertThat(c.suppressesSwrHalt(false)).isTrue();
        clock.now += 1;
        assertThat(c.suppressesSwrHalt(false)).isFalse();
    }

    @Test
    public void swrHalt_unmatchedKeyUpOpensNoWindow() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.markUnkeyed();
        assertThat(c.suppressesSwrHalt(false)).isFalse();
    }

    // --- Session scoping (issue #982 part 2: stop, then a quick re-tap) ---

    @Test
    public void sessions_getDistinctIds_andDoubleStartGetsNone() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long a = c.tryStartSession(10);
        assertThat(a).isNotEqualTo(TuneController.NO_SESSION);
        assertThat(c.tryStartSession(10)).isEqualTo(TuneController.NO_SESSION);
        c.requestStop(TuneController.STOP_USER);
        long b = c.tryStartSession(10);
        assertThat(b).isNotEqualTo(TuneController.NO_SESSION);
        assertThat(b).isNotEqualTo(a);
        assertThat(c.ownsSession(b)).isTrue();
        assertThat(c.ownsSession(a)).isFalse();
        assertThat(c.ownsSession(TuneController.NO_SESSION)).isFalse();
    }

    @Test
    public void staleStop_isANoOpOnTheNewSession() {
        // The old worker's finally runs requestStop(ERROR) after the re-tap.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long a = c.tryStartSession(5);
        c.markCarrierOn(a);
        c.requestStop(TuneController.STOP_USER);
        long b = c.tryStartSession(5);

        c.requestStop(a, TuneController.STOP_ERROR);

        assertThat(c.isActive()).isTrue();
        assertThat(c.shouldContinue(b)).isTrue();
        assertThat(c.lastStopReason()).isNull();
        assertThat(c.stopReason(a)).isEqualTo(TuneController.STOP_USER);
        assertThat(c.stopReason(b)).isNull();
    }

    @Test
    public void staleWorker_cannotContinueOrMarkCarrierOn() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long a = c.tryStartSession(5);
        c.requestStop(TuneController.STOP_USER);
        long b = c.tryStartSession(5);

        // The old worker, still ramping out, must see "stop", not B's session.
        assertThat(c.shouldContinue(a)).isFalse();
        assertThat(c.markCarrierOn(a)).isEqualTo(-1L);
        // ...and it didn't start B's clock: B still gets its own carrier-on.
        clock.now += 300;
        assertThat(c.markCarrierOn(b)).isEqualTo(300L);
    }

    @Test
    public void restart_getsTheFullMaxOnFromItsOwnCarrierOn() {
        // The reporter's case: 5 s set, stop at ~2 s, re-tap within 0.5 s.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long a = c.tryStartSession(5);
        clock.now += 200;
        c.markCarrierOn(a);
        clock.now += 2_000;
        c.requestStop(TuneController.STOP_USER);
        clock.now += 400;
        long b = c.tryStartSession(5);
        c.requestStop(a, TuneController.STOP_ERROR); // old teardown, late
        clock.now += 300;
        c.markCarrierOn(b);
        long carrierOn = clock.now;

        clock.now = carrierOn + 4_999;
        assertThat(c.shouldContinue(b)).isTrue();
        clock.now = carrierOn + 5_000;
        assertThat(c.shouldContinue(b)).isFalse();
        assertThat(c.stopReason(b)).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void unscopedStop_stillStopsTheCurrentSession() {
        // Operator stop and FT8-TX stop target whatever session is running.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long a = c.tryStartSession(5);
        c.requestStop(TuneController.STOP_USER);
        long b = c.tryStartSession(5);
        c.requestStop(TuneController.STOP_TX);
        assertThat(c.shouldContinue(b)).isFalse();
        assertThat(c.stopReason(b)).isEqualTo(TuneController.STOP_TX);
        assertThat(c.stopReason(a)).isEqualTo(TuneController.STOP_USER);
    }

    @Test
    public void stopReason_isNullForASessionTwoBack() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        long a = c.tryStartSession(5);
        c.requestStop(TuneController.STOP_USER);
        c.tryStartSession(5);
        c.requestStop(TuneController.STOP_USER);
        c.tryStartSession(5);
        assertThat(c.stopReason(a)).isNull();
    }

    @Test
    public void awaitPreviousWorker_noPreviousOrSelf_returnsTrue() {
        assertThat(TuneController.awaitPreviousWorker(null, 10)).isTrue();
        assertThat(TuneController.awaitPreviousWorker(Thread.currentThread(), 10)).isTrue();
    }

    @Test
    public void awaitPreviousWorker_stuckWorker_timesOut() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread stuck = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
        });
        stuck.start();
        try {
            assertThat(TuneController.awaitPreviousWorker(stuck, 50)).isFalse();
        } finally {
            release.countDown();
            stuck.join(1_000);
        }
    }

    @Test
    public void restartRace_newWorkerKeysOnlyAfterOldWorkerUnkeys() throws Exception {
        // Real threads, same order as FT8TransmitSignal.playTuneTone: the old
        // worker is stopped but still tearing down when the re-tap starts B.
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        List<String> wire = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch rampOut = new CountDownLatch(1);

        long a = c.tryStartSession(5);
        c.markKeyed();
        wire.add("keyDown A");
        Thread oldWorker = new Thread(() -> {
            try {
                rampOut.await(); // blocked in the ramp-out write
            } catch (InterruptedException ignored) {
            }
            c.requestStop(a, TuneController.STOP_ERROR);
            wire.add("keyUp A");
            c.markUnkeyed();
        });
        oldWorker.start();

        c.requestStop(TuneController.STOP_USER); // operator stop
        long b = c.tryStartSession(5); // quick re-tap, A still alive
        assertThat(b).isNotEqualTo(TuneController.NO_SESSION);

        Thread newWorker = new Thread(() -> {
            if (TuneController.awaitPreviousWorker(oldWorker, 2_000)
                    && c.shouldContinue(b)) {
                c.markKeyed();
                wire.add("keyDown B");
            }
        });
        newWorker.start();
        Thread.sleep(50);
        assertThat(wire).containsExactly("keyDown A"); // B waits for A

        rampOut.countDown();
        newWorker.join(2_000);
        oldWorker.join(2_000);

        assertThat(wire).containsExactly("keyDown A", "keyUp A", "keyDown B").inOrder();
        assertThat(c.isActive()).isTrue();
        assertThat(c.ownsSession(b)).isTrue();
        assertThat(c.stopReason(a)).isEqualTo(TuneController.STOP_USER);
        assertThat(c.suppressesSwrHalt(false)).isTrue();
    }
}
