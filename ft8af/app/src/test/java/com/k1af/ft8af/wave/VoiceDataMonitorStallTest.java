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
}
