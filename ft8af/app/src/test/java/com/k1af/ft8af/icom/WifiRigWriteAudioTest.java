package com.k1af.ft8af.icom;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression coverage for the {@link WifiRig} RX-audio use-after-release crash.
 *
 * <p>For a Wi-Fi Icom/Xiegu rig, the UDP receive worker plays every incoming audio
 * packet via {@link WifiRig#writeAudio(byte[])}. A disconnect while audio is still
 * streaming — the user tapping disconnect, or a send-side network error routed through
 * {@code OnUdpSendIOException -> close() -> closeAudio()} — releases the AudioTrack on a
 * <em>different</em> thread. The old code did an unguarded {@code audioTrack.write(...)};
 * writing to a released AudioTrack throws {@link IllegalStateException}, and the receive
 * worker's loop catches only {@code IOException}, so it escaped and crashed the app.
 *
 * <p>The fix wraps the write in {@link WifiRig#writeAudio(byte[])} and snapshots the
 * (now {@code volatile}) field inside {@link WifiRig#writeAudioToTrack(byte[])}. These
 * tests drive that seam directly, so the released-track path is deterministic without
 * emulating AudioTrack's native lifecycle.
 *
 * <p>Plain JUnit + Truth: the only Android type touched is {@code android.util.Log},
 * which unit tests stub via {@code returnDefaultValues}. No AudioTrack is constructed.
 */
public class WifiRigWriteAudioTest {

    /**
     * Minimal concrete {@link WifiRig} whose abstract members are no-ops and whose
     * {@link #writeAudioToTrack(byte[])} seam is scriptable per test.
     */
    private static class TestWifiRig extends WifiRig {
        RuntimeException toThrow;
        final AtomicReference<byte[]> lastWritten = new AtomicReference<>();
        final AtomicInteger writeCount = new AtomicInteger();

        TestWifiRig() {
            super("192.0.2.1", 50001, "user", "pass");
        }

        @Override
        void writeAudioToTrack(byte[] audioData) {
            writeCount.incrementAndGet();
            lastWritten.set(audioData);
            if (toThrow != null) throw toThrow;
        }

        @Override
        public void start() {}

        @Override
        public void setPttOn(boolean on) {}

        @Override
        public void sendCivData(byte[] data) {}

        @Override
        public void sendWaveData(float[] data) {}

        @Override
        public void close() {}
    }

    @Test
    public void writeAudio_releasedTrackThrowsIllegalState_isSwallowed() {
        TestWifiRig rig = new TestWifiRig();
        rig.toThrow = new IllegalStateException("play() called on uninitialized AudioTrack");

        // Must not propagate: pre-fix this exact throw escaped the receive worker
        // (which catches only IOException) and crashed the app.
        rig.writeAudio(new byte[] {1, 2, 3, 4});

        assertThat(rig.writeCount.get()).isEqualTo(1);
    }

    @Test
    public void writeAudio_delegatesChunkToTrack() {
        TestWifiRig rig = new TestWifiRig();
        byte[] chunk = {10, 20, 30};

        rig.writeAudio(chunk);

        assertThat(rig.writeCount.get()).isEqualTo(1);
        assertThat(rig.lastWritten.get()).isSameInstanceAs(chunk);
    }

    @Test
    public void writeAudio_doesNotSwallowUnrelatedRuntimeExceptions() {
        TestWifiRig rig = new TestWifiRig();
        rig.toThrow = new IllegalArgumentException("bug we must not mask");

        try {
            rig.writeAudio(new byte[] {1});
            org.junit.Assert.fail("Expected IllegalArgumentException to propagate");
        } catch (IllegalArgumentException expected) {
            // Only IllegalStateException (released-track race) is swallowed; other
            // RuntimeExceptions must surface so real bugs aren't hidden.
        }
    }

    @Test
    public void writeAudioToTrack_nullTrack_returnsWithoutWriting() {
        // Real seam (not overridden) with audioTrack still null: the receive worker
        // races a disconnect that already nulled the track. Must be a clean no-op,
        // never an NPE.
        WifiRig rig = new WifiRig("192.0.2.2", 50001, "user", "pass") {
            @Override
            public void start() {}

            @Override
            public void setPttOn(boolean on) {}

            @Override
            public void sendCivData(byte[] data) {}

            @Override
            public void sendWaveData(float[] data) {}

            @Override
            public void close() {}
        };

        assertThat(rig.audioTrack).isNull();
        rig.writeAudioToTrack(new byte[] {1, 2, 3}); // no throw
        rig.writeAudio(new byte[] {1, 2, 3}); // no throw through the public entry either
    }
}
