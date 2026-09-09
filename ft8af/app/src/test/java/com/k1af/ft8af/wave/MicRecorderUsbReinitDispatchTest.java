package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Locks the concurrency invariant behind the USB-attach crash fix so it can
 * never regress: a USB capture-stopped event must hand its backoff +
 * {@code reinitialize()} to a dedicated serialized worker, never run it on the
 * calling thread.
 *
 * <p>onCaptureStopped() fires on the native libusb event thread. Running the
 * reinit inline there parked that thread inside the native event loop while an
 * in-flight {@code nativeStop()}'s {@code join()} waited on it, so the next
 * {@code nativeStart()}/{@code libusb_init()} overlapped the dying context's
 * {@code libusb_exit()} and the process aborted on a destroyed libusb mutex
 * ("pthread_mutex_lock called on a destroyed mutex") — the crash-on-USB-attach
 * loop. These tests exercise the exact executor and dispatch seam the fix uses.
 */
public class MicRecorderUsbReinitDispatchTest {

    @Test
    public void dispatch_runsWorkOffTheCallingThread() throws Exception {
        ExecutorService ex = MicRecorder.newUsbReinitExecutor();
        try {
            Thread caller = Thread.currentThread();
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            MicRecorder.dispatchUsbReinit(ex, () -> {
                ranOn.set(Thread.currentThread());
                done.countDown();
            });

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ranOn.get()).isNotNull();
            // The whole point: the reinit ran somewhere other than the caller
            // (the native event thread), so nativeStop()'s join() can complete.
            assertThat(ranOn.get()).isNotSameInstanceAs(caller);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    public void dispatch_neverRunsInlineBeforeReturning() {
        // A busy worker is modelled by an executor that queues but never runs.
        // dispatch must return without having executed the reinit on the caller;
        // if it ran inline (the bug), this flag would already be set.
        final boolean[] ranInline = { false };
        Executor busyWorker = command -> { /* queued, not run — worker is busy */ };
        MicRecorder.dispatchUsbReinit(busyWorker, () -> ranInline[0] = true);
        assertThat(ranInline[0]).isFalse();
    }

    @Test
    public void executor_usesADedicatedDaemonThread() throws Exception {
        ExecutorService ex = MicRecorder.newUsbReinitExecutor();
        try {
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            ex.execute(() -> {
                ranOn.set(Thread.currentThread());
                done.countDown();
            });
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            // Daemon so a stuck reinit never holds up process exit; named so it
            // is identifiable in a tombstone if it ever does misbehave.
            assertThat(ranOn.get().isDaemon()).isTrue();
            assertThat(ranOn.get().getName()).isEqualTo("USB-Audio-Reinit");
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    public void executor_serializesStackedReinits() throws Exception {
        // Two capture-stopped events in quick succession (a composite adapter
        // fires several around one cable event) must reinit one-at-a-time —
        // never two concurrent reinitialize()s driving two overlapping libusb
        // contexts. A single-thread executor guarantees it; prove no overlap.
        ExecutorService ex = MicRecorder.newUsbReinitExecutor();
        try {
            AtomicInteger concurrent = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            CountDownLatch both = new CountDownLatch(2);
            Runnable task = () -> {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                both.countDown();
            };

            MicRecorder.dispatchUsbReinit(ex, task);
            MicRecorder.dispatchUsbReinit(ex, task);

            assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maxConcurrent.get()).isEqualTo(1);
        } finally {
            ex.shutdownNow();
        }
    }
}
