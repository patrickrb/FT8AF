package com.k1af.ft8af.ft8listener;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Coverage for the asymmetric analysis gate + late-abort policy that lets the late
 * full-slot pass share the (not thread-safe) native cross-slot cache with the next slot's
 * early decode WITHOUT ever stalling it: the early path always blocks (bounded by one
 * candidate), the late path gives up quickly under contention and its abort flag then
 * cancels the rest of the late work.
 */
public class LateAnalysisGateTest {

    private static final long JOIN_TIMEOUT_MS = 5_000;

    // ---- AnalysisGate ------------------------------------------------------------------

    @Test
    public void lateTryAcquire_succeedsWhenUncontended() {
        LateDecode.AnalysisGate gate = new LateDecode.AnalysisGate();
        assertThat(gate.tryAcquireForLate()).isTrue();
        gate.release();
    }

    @Test
    public void lateTryAcquire_failsWhileEarlyHoldsGate_thenSucceedsAfterRelease()
            throws Exception {
        LateDecode.AnalysisGate gate = new LateDecode.AnalysisGate();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch releaseNow = new CountDownLatch(1);
        Thread early = new Thread(() -> {
            gate.acquireBlocking();
            held.countDown();
            try {
                releaseNow.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.release();
        });
        early.start();
        assertThat(held.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        // Contended: the early path holds the gate longer than the late timeout, so the
        // late pass must give up (this is its cue to abort remaining candidates) ...
        assertThat(gate.tryAcquireForLate()).isFalse();

        releaseNow.countDown();
        early.join(JOIN_TIMEOUT_MS);
        assertThat(early.isAlive()).isFalse();

        // ... and once the gate is free again a late acquire succeeds.
        assertThat(gate.tryAcquireForLate()).isTrue();
        gate.release();
    }

    @Test
    public void earlyAcquire_waitsOutLateHolder_neverGivesUp() throws Exception {
        LateDecode.AnalysisGate gate = new LateDecode.AnalysisGate();
        // Late pass holds the gate (one candidate in flight).
        assertThat(gate.tryAcquireForLate()).isTrue();

        CountDownLatch acquired = new CountDownLatch(1);
        Thread early = new Thread(() -> {
            gate.acquireBlocking();
            acquired.countDown();
            gate.release();
        });
        early.start();

        // The early path has no timeout: well past the late path's trylock window it is
        // still (correctly) waiting rather than failing.
        assertThat(acquired.await(
                5 * LateDecode.AnalysisGate.LATE_TRYLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isFalse();

        // The late holder releases between candidates; the early path then proceeds.
        gate.release();
        assertThat(acquired.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        early.join(JOIN_TIMEOUT_MS);
        assertThat(early.isAlive()).isFalse();
    }

    @Test
    public void lateTryAcquire_whileInterrupted_returnsFalseAndKeepsInterruptFlag() {
        LateDecode.AnalysisGate gate = new LateDecode.AnalysisGate();
        Thread.currentThread().interrupt();
        try {
            // A late decode thread interrupted mid-pass must treat it like contention
            // (abort), not crash — and the interrupt status must survive for the caller.
            assertThat(gate.tryAcquireForLate()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();// clear the flag so it can't leak into other tests
        }
    }

    // ---- LateAbort ---------------------------------------------------------------------

    @Test
    public void lateAbort_startsUntripped() {
        assertThat(new LateDecode.LateAbort().tripped()).isFalse();
    }

    @Test
    public void lateAbort_tripIsSticky() {
        // Once contention is detected the WHOLE remaining late pass (including the deep
        // pass) stays cancelled; nothing may reset the flag.
        LateDecode.LateAbort abort = new LateDecode.LateAbort();
        abort.trip();
        assertThat(abort.tripped()).isTrue();
        abort.trip();
        assertThat(abort.tripped()).isTrue();
    }

    @Test
    public void lateAbort_visibleAcrossThreads() throws Exception {
        // The flag is tripped inside analysisDecode's gate path but read by the candidate
        // loop; with the late pass and next slot's early decode on different threads the
        // write must publish. (volatile — this guards against regression to a plain field.)
        LateDecode.LateAbort abort = new LateDecode.LateAbort();
        Thread tripper = new Thread(abort::trip);
        tripper.start();
        tripper.join(JOIN_TIMEOUT_MS);
        assertThat(tripper.isAlive()).isFalse();
        assertThat(abort.tripped()).isTrue();
    }
}
