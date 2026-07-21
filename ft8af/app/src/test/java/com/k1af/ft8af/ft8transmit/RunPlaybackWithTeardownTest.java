package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link FT8TransmitSignal#runPlaybackWithTeardown} — the guard
 * that keeps a thrown AudioTrack failure from (a) escaping the top-level-catch-less
 * TX worker (whole-app crash) and (b) stranding PTT keyed + audio focus held +
 * the track leaked. The contract: teardown runs exactly once whether the body
 * returns normally or throws, and a body exception never propagates.
 *
 * <p>Pure JVM (no Robolectric): the method takes plain {@link Runnable}s and its
 * only Android touch is {@code Log.e}, which {@code returnDefaultValues} stubs.
 */
public class RunPlaybackWithTeardownTest {

    @Test
    public void bodyCompletes_runsBodyThenTeardownExactlyOnce() {
        AtomicInteger bodyRuns = new AtomicInteger();
        AtomicInteger teardownRuns = new AtomicInteger();

        FT8TransmitSignal.runPlaybackWithTeardown(
                bodyRuns::incrementAndGet, teardownRuns::incrementAndGet);

        assertThat(bodyRuns.get()).isEqualTo(1);
        assertThat(teardownRuns.get()).isEqualTo(1);
    }

    @Test
    public void bodyThrows_runsTeardownAndSwallowsTheException() {
        AtomicInteger teardownRuns = new AtomicInteger();

        // A throwing body must NOT propagate: the real body runs on the
        // doTransmitThreadPool worker, whose DoTransmitRunnable has no
        // top-level try/catch, so an escaping exception crashes the app.
        FT8TransmitSignal.runPlaybackWithTeardown(() -> {
            throw new IllegalStateException("AudioTrack died mid-write");
        }, teardownRuns::incrementAndGet);

        // If the exception were not swallowed this test method would fail with
        // that IllegalStateException instead of reaching the assertion.
        assertThat(teardownRuns.get()).isEqualTo(1);
    }

    @Test
    public void bodyThrows_stillTearsDownEvenWhenBodyDidPartialWork() {
        AtomicInteger teardownRuns = new AtomicInteger();
        AtomicInteger bodyProgress = new AtomicInteger();

        FT8TransmitSignal.runPlaybackWithTeardown(() -> {
            bodyProgress.incrementAndGet(); // e.g. focus acquired / PTT keyed
            throw new IllegalArgumentException("bad audio route");
        }, teardownRuns::incrementAndGet);

        assertThat(bodyProgress.get()).isEqualTo(1);
        assertThat(teardownRuns.get()).isEqualTo(1);
    }

    @Test
    public void teardownRunsOncePerCall_notCumulatively() {
        AtomicInteger teardownRuns = new AtomicInteger();

        FT8TransmitSignal.runPlaybackWithTeardown(() -> { }, teardownRuns::incrementAndGet);
        FT8TransmitSignal.runPlaybackWithTeardown(() -> {
            throw new RuntimeException("second call throws");
        }, teardownRuns::incrementAndGet);

        // One teardown per invocation regardless of body outcome.
        assertThat(teardownRuns.get()).isEqualTo(2);
    }
}
