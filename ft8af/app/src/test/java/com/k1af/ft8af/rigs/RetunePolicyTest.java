package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link RetunePolicy} — the rate limit that stops {@code
 * MainViewModel.setOperationBand()} re-sending an unchanged dial to the rig about once a
 * second, all session (20,124 occurrences in the pulled 2026-07-30 log).
 *
 * <p>The load-bearing property is the ordering: correctness beats the rate limit. Every
 * "must not be throttled" case below is a way the operator could otherwise end up
 * transmitting on the wrong dial.
 */
public class RetunePolicyTest {

    private static final long FREQ = 14_074_000L;
    private static final long OTHER_FREQ = 7_074_000L;
    private static final long T0 = 1_700_000_000_000L;

    // ---------------------------------------------------------------
    // Must never be throttled
    // ---------------------------------------------------------------

    @Test
    public void firstPushOfSessionAlwaysGoesOut() {
        // On connect the rig may still be on its power-up frequency, and its cached freq
        // can match ours without the USB mode ever having been sent.
        assertThat(RetunePolicy.shouldRetune(
                FREQ, FREQ, RetunePolicy.NO_PUSH, T0, 0L)).isTrue();
    }

    @Test
    public void newDialGoesOutImmediately() {
        // A real band change. Delaying this leaves the operator on the old dial.
        assertThat(RetunePolicy.shouldRetune(
                OTHER_FREQ, FREQ, FREQ, T0 + 10, T0)).isTrue();
    }

    @Test
    public void rigOnAWrongFrequencyIsCorrectedImmediately() {
        // Front-panel move, or the rig dropped our command. Same target as last push,
        // but the rig is not there — push regardless of how recently we pushed.
        assertThat(RetunePolicy.shouldRetune(
                FREQ, OTHER_FREQ, FREQ, T0 + 10, T0)).isTrue();
    }

    @Test
    public void newDialWinsEvenWhenRigAlreadyReportsIt() {
        // The rig can report the new dial before we have pushed the mode for it.
        assertThat(RetunePolicy.shouldRetune(
                OTHER_FREQ, OTHER_FREQ, FREQ, T0 + 10, T0)).isTrue();
    }

    // ---------------------------------------------------------------
    // The loop this exists to kill
    // ---------------------------------------------------------------

    @Test
    public void redundantRepeatIsSuppressed() {
        // The observed loop: same dial, rig already there, ~1 s after the last push.
        assertThat(RetunePolicy.shouldRetune(
                FREQ, FREQ, FREQ, T0 + 1_050, T0)).isFalse();
    }

    @Test
    public void aFullSecondOfLoopIterationsIsAllSuppressed() {
        // 57 calls/min was the measured rate; none of them should reach the rig.
        for (long dt = 100; dt < RetunePolicy.REASSERT_INTERVAL_MS; dt += 1_050) {
            assertThat(RetunePolicy.shouldRetune(FREQ, FREQ, FREQ, T0 + dt, T0)).isFalse();
        }
    }

    // ---------------------------------------------------------------
    // The slow reassert heartbeat
    // ---------------------------------------------------------------

    @Test
    public void redundantRepeatIsReassertedAfterTheInterval() {
        assertThat(RetunePolicy.shouldRetune(
                FREQ, FREQ, FREQ, T0 + RetunePolicy.REASSERT_INTERVAL_MS, T0)).isTrue();
    }

    @Test
    public void justBeforeTheIntervalIsStillSuppressed() {
        assertThat(RetunePolicy.shouldRetune(
                FREQ, FREQ, FREQ, T0 + RetunePolicy.REASSERT_INTERVAL_MS - 1, T0)).isFalse();
    }

    @Test
    public void reassertIntervalIsFarBelowTheObservedLoopRate() {
        // Guards the constant: the loop ran at ~1.05 s, so anything in that neighbourhood
        // would fail to contain it.
        assertThat(RetunePolicy.REASSERT_INTERVAL_MS).isAtLeast(10_000L);
    }

    // ---------------------------------------------------------------
    // Suppression logging
    // ---------------------------------------------------------------

    @Test
    public void suppressionLogIsRateLimited() {
        assertThat(RetunePolicy.shouldLogSuppression(T0, T0)).isFalse();
        assertThat(RetunePolicy.shouldLogSuppression(
                T0 + RetunePolicy.SUPPRESSION_LOG_INTERVAL_MS - 1, T0)).isFalse();
        assertThat(RetunePolicy.shouldLogSuppression(
                T0 + RetunePolicy.SUPPRESSION_LOG_INTERVAL_MS, T0)).isTrue();
    }

    @Test
    public void firstSuppressionLogsImmediately() {
        // lastLogAt == 0 (never logged) must not wait out the interval.
        assertThat(RetunePolicy.shouldLogSuppression(T0, 0L)).isTrue();
    }

    // ---------------------------------------------------------------
    // Caller identification (the diagnostic half)
    // ---------------------------------------------------------------

    @Test
    public void callerOf_returnsFirstFrameAfterSelf() {
        StackTraceElement[] stack = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement("com.k1af.ft8af.MainViewModel", "setOperationBand", "MainViewModel.java", 1521),
                new StackTraceElement("com.k1af.ft8af.Mystery", "tick", "Mystery.java", 42),
                new StackTraceElement("java.util.TimerThread", "run", "Timer.java", 1),
        };
        assertThat(RetunePolicy.callerOf(stack, "com.k1af.ft8af.MainViewModel"))
                .isEqualTo("com.k1af.ft8af.Mystery.tick:42");
    }

    @Test
    public void callerOf_skipsConsecutiveSelfFrames() {
        // setOperationBand called from another MainViewModel method: report the first
        // frame OUTSIDE the class, not the internal hop.
        StackTraceElement[] stack = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement("com.k1af.ft8af.MainViewModel", "setOperationBand", "MainViewModel.java", 1521),
                new StackTraceElement("com.k1af.ft8af.MainViewModel", "lambda$onConnected$0", "MainViewModel.java", 297),
                new StackTraceElement("android.os.Handler", "handleCallback", "Handler.java", 1),
        };
        assertThat(RetunePolicy.callerOf(stack, "com.k1af.ft8af.MainViewModel"))
                .isEqualTo("android.os.Handler.handleCallback:1");
    }

    @Test
    public void callerOf_handlesMissingSelfAndNulls() {
        StackTraceElement[] noSelf = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
        };
        assertThat(RetunePolicy.callerOf(noSelf, "com.k1af.ft8af.MainViewModel")).isEqualTo("unknown");
        assertThat(RetunePolicy.callerOf(null, "com.k1af.ft8af.MainViewModel")).isEqualTo("unknown");
        assertThat(RetunePolicy.callerOf(noSelf, null)).isEqualTo("unknown");
        assertThat(RetunePolicy.callerOf(new StackTraceElement[0], "x")).isEqualTo("unknown");
    }
}
