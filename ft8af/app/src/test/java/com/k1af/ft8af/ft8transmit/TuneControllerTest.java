package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

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
        clock.now += 20_000;
        assertThat(c.shouldContinue()).isFalse();
        assertThat(c.lastStopReason()).isEqualTo(TuneController.STOP_TIMEOUT);
    }

    @Test
    public void elapsedAndRemaining_trackTheClock() {
        FakeClock clock = new FakeClock();
        TuneController c = new TuneController(clock);
        c.tryStart(10);
        clock.now += 3_000;
        assertThat(c.elapsedMs()).isEqualTo(3_000L);
        assertThat(c.remainingMs()).isEqualTo(7_000L);
        c.requestStop(TuneController.STOP_USER);
        assertThat(c.elapsedMs()).isEqualTo(0L);
        assertThat(c.remainingMs()).isEqualTo(0L);
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
}
