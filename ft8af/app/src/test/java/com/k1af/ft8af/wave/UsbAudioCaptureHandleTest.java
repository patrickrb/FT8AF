package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests {@link UsbAudioDevice#claimCaptureHandleForStop(AtomicLong)} — the
 * atomic handoff that guarantees a native capture session is stopped (and its
 * libusb context freed) exactly once.
 *
 * <p>Two teardown drivers race for the same session: a natural capture retire
 * (the event thread's onCaptureStopped, which now leaves the handle set) and an
 * explicit {@code stopCapture()} on the reinit worker. If both called
 * {@code nativeStop} on the same pointer it would double {@code libusb_exit}/
 * free; if neither did, the context (and a pthread TLS key) leaked until
 * {@code libusb_init} aborted. Exactly one claimant must win.
 */
public class UsbAudioCaptureHandleTest {

    @Test
    public void firstClaimGetsHandle_secondGetsZero() {
        AtomicLong handle = new AtomicLong(0xCAFEBABEL);

        assertThat(UsbAudioDevice.claimCaptureHandleForStop(handle)).isEqualTo(0xCAFEBABEL);
        // Field cleared, so a second teardown driver stops nothing.
        assertThat(UsbAudioDevice.claimCaptureHandleForStop(handle)).isEqualTo(0);
        assertThat(handle.get()).isEqualTo(0);
    }

    @Test
    public void noHandle_claimsZero() {
        assertThat(UsbAudioDevice.claimCaptureHandleForStop(new AtomicLong(0))).isEqualTo(0);
    }

    @Test
    public void concurrentClaims_exactlyOneWinner() throws InterruptedException {
        // Model the retire/stopCapture race directly: many threads claim the same
        // handle at once; exactly one must get the non-zero pointer to nativeStop.
        for (int trial = 0; trial < 200; trial++) {
            final AtomicLong handle = new AtomicLong(0x1234L);
            final int threads = 8;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            final AtomicInteger winners = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (UsbAudioDevice.claimCaptureHandleForStop(handle) != 0) {
                        winners.incrementAndGet();
                    }
                    done.countDown();
                }).start();
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(winners.get()).isEqualTo(1);
        }
    }
}
