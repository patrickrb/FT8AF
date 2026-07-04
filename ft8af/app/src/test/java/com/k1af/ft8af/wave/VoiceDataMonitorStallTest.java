package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;

/**
 * Coverage for the one-shot {@code VoiceDataMonitor} stall watchdog. The regression these
 * guard against: a capture stall mid-slot (USB drop, dead audio server) left a one-shot decode
 * monitor registered forever with a partially-filled buffer — the cycle silently produced no
 * decode and the monitor leaked. The watchdog force-delivers the zero-padded partial buffer
 * exactly once, and must never race or duplicate a normal completion.
 */
public class VoiceDataMonitorStallTest {

    private static final int DURATION_MS = 100; // 1200 samples at the 12 kHz recorder rate

    private static class RecordingCallback implements OnGetVoiceDataDone {
        final ArrayList<float[]> deliveries = new ArrayList<>();

        @Override
        public void onGetDone(float[] data) {
            deliveries.add(data);
        }
    }

    private static HamRecorder.VoiceDataMonitor newMonitor(boolean oneShot, RecordingCallback cb) {
        HamRecorder.VoiceDataMonitor m =
                new HamRecorder.VoiceDataMonitor(DURATION_MS, null, oneShot, cb);
        m.voiceDataMonitor = m;
        return m;
    }

    private static void feed(HamRecorder.VoiceDataMonitor m, int samples, float value) {
        float[] data = new float[samples];
        java.util.Arrays.fill(data, value);
        m.onHamRecord.OnReceiveData(data, samples);
    }

    @Test
    public void stalledPartialBufferIsDeliveredOnceZeroPadded() {
        RecordingCallback cb = new RecordingCallback();
        HamRecorder.VoiceDataMonitor m = newMonitor(true, cb);

        feed(m, 500, 0.25f); // capture stalls at 500 of 1200 samples
        assertThat(cb.deliveries).isEmpty();
        assertThat(m.collectedSamples()).isEqualTo(500);

        assertThat(m.forceCompleteAfterStall(null)).isTrue();
        assertThat(cb.deliveries).hasSize(1);
        float[] delivered = cb.deliveries.get(0);
        assertThat(delivered.length).isEqualTo(1200);
        assertThat(delivered[499]).isEqualTo(0.25f); // collected prefix preserved
        assertThat(delivered[500]).isEqualTo(0f);    // stalled remainder zero-padded
        assertThat(delivered[1199]).isEqualTo(0f);
    }

    @Test
    public void secondForceCompleteIsANoOp() {
        RecordingCallback cb = new RecordingCallback();
        HamRecorder.VoiceDataMonitor m = newMonitor(true, cb);
        feed(m, 10, 0.1f);

        assertThat(m.forceCompleteAfterStall(null)).isTrue();
        assertThat(m.forceCompleteAfterStall(null)).isFalse();
        assertThat(cb.deliveries).hasSize(1);
    }

    @Test
    public void normalCompletionBlocksTheWatchdog() {
        RecordingCallback cb = new RecordingCallback();
        HamRecorder.VoiceDataMonitor m = newMonitor(true, cb);

        feed(m, 1200, 0.5f); // buffer fills normally
        assertThat(cb.deliveries).hasSize(1);

        // The watchdog firing afterwards must not deliver a second time.
        assertThat(m.forceCompleteAfterStall(null)).isFalse();
        assertThat(cb.deliveries).hasSize(1);
    }

    @Test
    public void loopingMonitorsAreNeverForceCompleted() {
        RecordingCallback cb = new RecordingCallback();
        HamRecorder.VoiceDataMonitor m = newMonitor(false, cb);
        feed(m, 500, 0.25f);

        assertThat(m.forceCompleteAfterStall(null)).isFalse();
        assertThat(cb.deliveries).isEmpty();
    }

    @Test
    public void lateAudioCannotMutateADeliveredBuffer() {
        // After the watchdog delivers a stalled buffer, late-arriving audio must
        // not keep writing into the array the consumer is now reading.
        RecordingCallback cb = new RecordingCallback();
        HamRecorder.VoiceDataMonitor m = newMonitor(true, cb);
        feed(m, 500, 0.25f);
        assertThat(m.forceCompleteAfterStall(null)).isTrue();

        feed(m, 700, 0.75f); // late audio arrives after delivery
        float[] delivered = cb.deliveries.get(0);
        assertThat(delivered[500]).isEqualTo(0f); // still the zero padding
        assertThat(m.collectedSamples()).isEqualTo(500); // nothing appended
        assertThat(cb.deliveries).hasSize(1); // and no second delivery
    }

    @Test
    public void loopingMonitorStillLoopsAfterFill() {
        // Sanity that the completion-guard refactor didn't break the looping
        // (waterfall) path: it must keep delivering on every fill.
        RecordingCallback cb = new RecordingCallback();
        HamRecorder.VoiceDataMonitor m = newMonitor(false, cb);
        feed(m, 1200, 0.5f);
        feed(m, 1200, 0.5f);
        assertThat(cb.deliveries).hasSize(2);
    }

    // ---- capture-thread vs watchdog-thread races (issue #404) ----------------
    // forceCompleteAfterStall used to read dataCount/voiceData with no
    // happens-before against the capture thread's writes. Both sides now
    // synchronize on the monitor's buffer lock: the watchdog waits out an
    // in-flight chunk copy and decides the winner on the true count before the
    // buffer is handed out.

    @Test
    public void concurrentFillAndWatchdog_exactlyOneDelivery_neverTorn() throws Exception {
        // Race a chunked fill (capture thread) against the watchdog firing at an
        // arbitrary point. Whatever the interleaving: exactly one delivery, and the
        // delivered buffer is a clean prefix of real samples followed by zeros —
        // never a zero hole inside the collected prefix.
        for (int round = 0; round < 200; round++) {
            RecordingCallback cb = new RecordingCallback();
            HamRecorder.VoiceDataMonitor m = newMonitor(true, cb);

            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            Thread capture = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int fed = 0; fed < 1200; fed += 100) {
                    feed(m, 100, 0.25f); // 12 chunks of 100 samples
                }
            });
            Thread watchdog = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                m.forceCompleteAfterStall(null);
            });
            capture.start();
            watchdog.start();
            go.countDown();
            capture.join();
            watchdog.join();

            assertThat(cb.deliveries).hasSize(1);
            float[] delivered = cb.deliveries.get(0);
            // Find the end of the real-sample prefix; everything after must be zero.
            int prefixEnd = delivered.length;
            while (prefixEnd > 0 && delivered[prefixEnd - 1] == 0f) {
                prefixEnd--;
            }
            for (int i = 0; i < prefixEnd; i++) {
                assertThat(delivered[i]).isEqualTo(0.25f);
            }
            // The prefix must be whole chunks: the watchdog may not snapshot a
            // half-copied chunk.
            assertThat(prefixEnd % 100).isEqualTo(0);
        }
    }

    @Test
    public void watchdogRacingFinalChunk_fullBufferIsNeverDeliveredShort() throws Exception {
        // The issue's exact scenario: the buffer is one chunk from full when the
        // watchdog fires. If the fill wins the lock, the watchdog must observe the
        // completed count (not a stale one) and stand down; if the watchdog wins,
        // the final chunk must not mutate the delivered buffer afterwards.
        for (int round = 0; round < 200; round++) {
            RecordingCallback cb = new RecordingCallback();
            HamRecorder.VoiceDataMonitor m = newMonitor(true, cb);
            feed(m, 1100, 0.25f); // all but the final 100 samples

            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            Thread capture = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                feed(m, 100, 0.25f); // the final chunk
            });
            Thread watchdog = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                m.forceCompleteAfterStall(null);
            });
            capture.start();
            watchdog.start();
            go.countDown();
            capture.join();
            watchdog.join();

            assertThat(cb.deliveries).hasSize(1);
            float[] delivered = cb.deliveries.get(0);
            // Either outcome is legal, but the collected prefix must be intact and
            // stable: at least the pre-fed 1100 samples, and if the fill won, all 1200.
            for (int i = 0; i < 1100; i++) {
                assertThat(delivered[i]).isEqualTo(0.25f);
            }
            boolean fullDelivery = delivered[1199] == 0.25f;
            boolean shortDelivery = delivered[1199] == 0f;
            assertThat(fullDelivery || shortDelivery).isTrue();
        }
    }
}
