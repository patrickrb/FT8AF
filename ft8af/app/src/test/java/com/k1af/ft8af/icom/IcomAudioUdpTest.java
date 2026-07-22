package com.k1af.ft8af.icom;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression coverage for {@link IcomAudioUdp}'s TX-audio path.
 *
 * <p>The old code held a single shared {@code DoTXAudioRunnable}, mutated its
 * {@code audioData} field on every {@link IcomAudioUdp#sendTxAudioData} call, and
 * re-{@code execute()}d that same instance on a cached thread pool. A second TX (or
 * Tune) firing before the first ~12.6&nbsp;s run finished ran the <em>same</em>
 * Runnable on two pool threads concurrently — stomping the shared PCM buffer and
 * racing the unsynchronised {@code innerSeq}, garbling Icom-over-Wi-Fi audio. It also
 * left the pooled worker's interrupt flag set on exit.
 *
 * <p>The fix snapshots PCM per call into a fresh runnable and guards with an
 * {@code AtomicBoolean} so an overlapping submission is dropped instead of racing.
 * These tests drive the {@code submitTransmit} seam and the guard directly — no
 * socket, plain JUnit + Truth (the only Android type touched is {@code Log}, stubbed
 * via {@code returnDefaultValues}).
 */
public class IcomAudioUdpTest {

    /** Captures submissions instead of running them, so the guard stays "in flight". */
    private static class RecordingIcomAudioUdp extends IcomAudioUdp {
        final List<short[]> submitted = new ArrayList<>();

        @Override
        void submitTransmit(short[] pcm) {
            submitted.add(pcm);
        }
    }

    // ---- floatToPcm16 conversion ----

    @Test
    public void floatToPcm16_clampsAndScales() {
        short[] out = IcomAudioUdp.floatToPcm16(new float[] {0f, 1.0f, -1.0f, 0.5f, 2.0f, -3.0f});

        assertThat(out.length).isEqualTo(6);
        assertThat(out[0]).isEqualTo((short) 0);
        assertThat(out[1]).isEqualTo((short) 32767);     // +full scale
        assertThat(out[2]).isEqualTo((short) -32767);    // -full scale (clamped to -1.0)
        assertThat(out[3]).isEqualTo((short) 16383);     // 0.5 * 32767 truncated
        assertThat(out[4]).isEqualTo((short) 32767);     // >1.0 clamped, not overflowed
        assertThat(out[5]).isEqualTo((short) -32767);    // <-1.0 clamped, not overflowed
    }

    @Test
    public void floatToPcm16_emptyInput_returnsEmpty() {
        assertThat(IcomAudioUdp.floatToPcm16(new float[0])).hasLength(0);
    }

    // ---- overlap guard ----

    @Test
    public void sendTxAudioData_dropsOverlappingRequestWhileTransmitting() {
        RecordingIcomAudioUdp rig = new RecordingIcomAudioUdp();

        rig.sendTxAudioData(new float[10]);
        // First call acquired the guard and submitted exactly one transmission.
        assertThat(rig.submitted).hasSize(1);
        assertThat(rig.transmitting.get()).isTrue();
        assertThat(rig.submitted.get(0)).hasLength(10);

        // A second call while the first is still "in flight" must be dropped — not
        // submitted as a concurrent runnable that would garble the shared stream.
        rig.sendTxAudioData(new float[10]);
        assertThat(rig.submitted).hasSize(1);

        // Once the running transmission releases the guard, the next TX starts again.
        rig.transmitting.set(false);
        rig.sendTxAudioData(new float[10]);
        assertThat(rig.submitted).hasSize(2);
    }

    @Test
    public void sendTxAudioData_nullAudio_isNoOpAndLeavesGuardFree() {
        RecordingIcomAudioUdp rig = new RecordingIcomAudioUdp();

        rig.sendTxAudioData(null);

        assertThat(rig.submitted).isEmpty();
        assertThat(rig.transmitting.get()).isFalse();
    }

    @Test
    public void sendTxAudioData_dropDoesNotAdvanceSequence() {
        RecordingIcomAudioUdp rig = new RecordingIcomAudioUdp();
        short seqBefore = rig.innerSeq;

        rig.sendTxAudioData(new float[10]);   // acquires guard, no packets sent by the stub
        rig.sendTxAudioData(new float[10]);   // dropped

        // Neither the acquired-but-stubbed submit nor the dropped call touches innerSeq.
        assertThat(rig.innerSeq).isEqualTo(seqBefore);
    }

    // ---- guard is released when the real transmission finishes ----

    @Test
    public void realRun_releasesGuardWhenPttOff() throws Exception {
        // Use the real submitTransmit + cached pool. With PTT off, the runnable's
        // for-loop breaks before any sendTrackedPacket (so no socket is needed) and
        // the finally block must clear the guard so a later TX can start.
        IcomAudioUdp rig = new IcomAudioUdp();
        rig.isPttOn = false;

        rig.sendTxAudioData(new float[240]);

        long deadline = System.currentTimeMillis() + 2000;
        while (rig.transmitting.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(rig.transmitting.get()).isFalse();
    }
}
