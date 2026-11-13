package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioFormat;

import com.k1af.ft8af.wave.AudioChannelCapability;
import com.k1af.ft8af.wave.AudioChannelSelect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link TxChannelLayout}: the channel layout an
 * {@code AudioTrack} transmit opens with and the frame arithmetic that follows
 * it. This is the load-bearing piece of the TX channel select — a mistake in
 * the samples-vs-frames accounting truncates the tail of every over, and a
 * mistake in the sink resolution halves the drive on a mono route.
 */
@RunWith(RobolectricTestRunner.class)
public class TxChannelLayoutTest {

    private static final int STEREO = 2;
    private static final int MONO = 1;

    // ---- resolve ----

    @Test
    public void resolve_bothIsTheHistoricalMonoOpen() {
        TxChannelLayout layout = TxChannelLayout.resolve(AudioChannelSelect.BOTH, true, STEREO);
        assertThat(layout.isStereo()).isFalse();
        assertThat(layout.channels).isEqualTo(1);
        assertThat(layout.channelMask).isEqualTo(AudioFormat.CHANNEL_OUT_MONO);
        assertThat(layout.selection).isEqualTo(AudioChannelSelect.BOTH);
    }

    @Test
    public void resolve_leftOrRightOnAStereoSinkOpensStereo() {
        TxChannelLayout left = TxChannelLayout.resolve(AudioChannelSelect.LEFT, true, STEREO);
        assertThat(left.isStereo()).isTrue();
        assertThat(left.channels).isEqualTo(2);
        assertThat(left.channelMask).isEqualTo(AudioFormat.CHANNEL_OUT_STEREO);
        assertThat(left.selection).isEqualTo(AudioChannelSelect.LEFT);

        TxChannelLayout right = TxChannelLayout.resolve(AudioChannelSelect.RIGHT, true, STEREO);
        assertThat(right.isStereo()).isTrue();
        assertThat(right.selection).isEqualTo(AudioChannelSelect.RIGHT);
    }

    @Test
    public void resolve_monoSinkCollapsesToMono() {
        // One channel on the device: nothing to pick, and a stereo open would
        // only be downmixed. Stay on the historical path.
        TxChannelLayout layout = TxChannelLayout.resolve(AudioChannelSelect.RIGHT, true, MONO);
        assertThat(layout.isStereo()).isFalse();
        assertThat(layout.selection).isEqualTo(AudioChannelSelect.BOTH);
    }

    @Test
    public void resolve_defaultSinkAlwaysMono() {
        // Android's Default output: we cannot see what it routes to, so a
        // one-sided stereo open could land on a mono route at half drive. The
        // stored selection is ignored there, whatever the (unknowable) count.
        TxChannelLayout layout = TxChannelLayout.resolve(
                AudioChannelSelect.LEFT, false, AudioChannelCapability.UNKNOWN);
        assertThat(layout.isStereo()).isFalse();
        assertThat(layout.selection).isEqualTo(AudioChannelSelect.BOTH);

        // Even a claimed stereo count does not override the Default rule.
        assertThat(TxChannelLayout.resolve(AudioChannelSelect.LEFT, false, STEREO).isStereo())
                .isFalse();
    }

    @Test
    public void resolve_explicitSinkWithUnknownCountHonoursTheChoice() {
        // A named device that reports no channel counts: the operator picked it
        // on purpose, so the selection stands.
        TxChannelLayout layout = TxChannelLayout.resolve(
                AudioChannelSelect.LEFT, true, AudioChannelCapability.UNKNOWN);
        assertThat(layout.isStereo()).isTrue();
    }

    @Test
    public void resolve_clampsGarbageToMono() {
        assertThat(TxChannelLayout.resolve(99, true, STEREO).isStereo()).isFalse();
    }

    // ---- buffer arithmetic ----

    @Test
    public void bufferBytes_monoIsTheHistoricalBudget() {
        TxChannelLayout mono = TxChannelLayout.resolve(AudioChannelSelect.BOTH, true, STEREO);
        // (12000 / 5) * 2 bytes = 4800 — the pre-existing ~200ms int16 budget.
        assertThat(mono.bufferBytes(12000, 2)).isEqualTo(4800);
        assertThat(mono.bufferBytes(12000, 4)).isEqualTo(9600);
    }

    @Test
    public void bufferBytes_stereoDoublesToKeepTheSameDuration() {
        TxChannelLayout stereo = TxChannelLayout.resolve(AudioChannelSelect.LEFT, true, STEREO);
        assertThat(stereo.bufferBytes(12000, 2)).isEqualTo(9600);
    }

    @Test
    public void samplesAndFrames_roundTripAtBothLayouts() {
        TxChannelLayout mono = TxChannelLayout.resolve(AudioChannelSelect.BOTH, true, STEREO);
        assertThat(mono.samplesForFrames(600)).isEqualTo(600);
        assertThat(mono.framesFromSamples(600)).isEqualTo(600);

        TxChannelLayout stereo = TxChannelLayout.resolve(AudioChannelSelect.LEFT, true, STEREO);
        assertThat(stereo.samplesForFrames(600)).isEqualTo(1200);
        // write() reports samples; the drain wait compares frames.
        assertThat(stereo.framesFromSamples(1200)).isEqualTo(600);
    }

    @Test
    public void zeroPad_isEightFramesAtEitherLayout() {
        assertThat(TxChannelLayout.resolve(AudioChannelSelect.BOTH, true, STEREO).zeroPad())
                .hasLength(8);
        assertThat(TxChannelLayout.resolve(AudioChannelSelect.LEFT, true, STEREO).zeroPad())
                .hasLength(16);
    }

    // ---- layOut ----

    @Test
    public void layOut_monoHandsTheChunkStraightBack() {
        TxChannelLayout mono = TxChannelLayout.resolve(AudioChannelSelect.BOTH, true, STEREO);
        float[] chunk = {0.1f, 0.2f};
        // Same instance, no copy: the historical single-buffer write.
        assertThat(mono.layOut(chunk, 2, null)).isSameInstanceAs(chunk);
    }

    @Test
    public void layOut_stereoFillsScratchWithTheExcludedSideSilenced() {
        TxChannelLayout right = TxChannelLayout.resolve(AudioChannelSelect.RIGHT, true, STEREO);
        float[] chunk = {0.1f, 0.2f};
        float[] scratch = {9f, 9f, 9f, 9f};
        float[] out = right.layOut(chunk, 2, scratch);
        assertThat(out).isSameInstanceAs(scratch);
        assertThat(out).usingExactEquality().containsExactly(0f, 0.1f, 0f, 0.2f).inOrder();
    }

    @Test
    public void layOut_stereoOnlyTouchesTheChunkLength() {
        // A short final chunk in a scratch buffer sized for the full one: only
        // the frames actually written matter, and the write length the caller
        // uses is samplesForFrames(count), not the scratch length.
        TxChannelLayout left = TxChannelLayout.resolve(AudioChannelSelect.LEFT, true, STEREO);
        float[] scratch = new float[8];
        left.layOut(new float[] {0.5f}, 1, scratch);
        assertThat(scratch[0]).isEqualTo(0.5f);
        assertThat(scratch[1]).isEqualTo(0f);
        assertThat(left.samplesForFrames(1)).isEqualTo(2);
    }
}
