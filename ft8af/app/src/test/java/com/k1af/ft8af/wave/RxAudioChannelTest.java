package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link RxAudioChannel}, the shared stereo -> mono fold used by
 * all three RX capture paths.
 */
public class RxAudioChannelTest {

    @Test
    public void clamp_keepsValidSelections() {
        assertThat(RxAudioChannel.clamp(RxAudioChannel.MIX)).isEqualTo(RxAudioChannel.MIX);
        assertThat(RxAudioChannel.clamp(RxAudioChannel.LEFT)).isEqualTo(RxAudioChannel.LEFT);
        assertThat(RxAudioChannel.clamp(RxAudioChannel.RIGHT)).isEqualTo(RxAudioChannel.RIGHT);
    }

    @Test
    public void clamp_foldsOutOfRangeToMix() {
        assertThat(RxAudioChannel.clamp(-1)).isEqualTo(RxAudioChannel.MIX);
        assertThat(RxAudioChannel.clamp(3)).isEqualTo(RxAudioChannel.MIX);
        assertThat(RxAudioChannel.clamp(Integer.MAX_VALUE)).isEqualTo(RxAudioChannel.MIX);
    }

    @Test
    public void parse_readsPersistedValues() {
        assertThat(RxAudioChannel.parse("0")).isEqualTo(RxAudioChannel.MIX);
        assertThat(RxAudioChannel.parse("1")).isEqualTo(RxAudioChannel.LEFT);
        assertThat(RxAudioChannel.parse("2")).isEqualTo(RxAudioChannel.RIGHT);
        assertThat(RxAudioChannel.parse(" 2 ")).isEqualTo(RxAudioChannel.RIGHT);
    }

    @Test
    public void parse_defaultsToMixOnGarbage() {
        // A corrupted/imported config value must not throw on the load path.
        assertThat(RxAudioChannel.parse(null)).isEqualTo(RxAudioChannel.MIX);
        assertThat(RxAudioChannel.parse("")).isEqualTo(RxAudioChannel.MIX);
        assertThat(RxAudioChannel.parse("left")).isEqualTo(RxAudioChannel.MIX);
        assertThat(RxAudioChannel.parse("7")).isEqualTo(RxAudioChannel.MIX);
        assertThat(RxAudioChannel.parse("1.0")).isEqualTo(RxAudioChannel.MIX);
    }

    @Test
    public void needsStereoCapture_onlyForSingleChannelSelections() {
        assertThat(RxAudioChannel.needsStereoCapture(RxAudioChannel.MIX)).isFalse();
        assertThat(RxAudioChannel.needsStereoCapture(RxAudioChannel.LEFT)).isTrue();
        assertThat(RxAudioChannel.needsStereoCapture(RxAudioChannel.RIGHT)).isTrue();
        // Garbage clamps to MIX, which is a mono open.
        assertThat(RxAudioChannel.needsStereoCapture(99)).isFalse();
    }

    @Test
    public void foldFrame_picksTheSelectedChannel() {
        assertThat(RxAudioChannel.foldFrame(1.0f, -1.0f, RxAudioChannel.LEFT)).isEqualTo(1.0f);
        assertThat(RxAudioChannel.foldFrame(1.0f, -1.0f, RxAudioChannel.RIGHT)).isEqualTo(-1.0f);
        assertThat(RxAudioChannel.foldFrame(1.0f, -1.0f, RxAudioChannel.MIX)).isEqualTo(0.0f);
        assertThat(RxAudioChannel.foldFrame(0.5f, 0.1f, RxAudioChannel.MIX)).isWithin(1e-6f).of(0.3f);
    }

    @Test
    public void foldPcmFrame_scalesInt16ToUnitFloat() {
        assertThat(RxAudioChannel.foldPcmFrame((short) 16384, (short) 0, RxAudioChannel.LEFT))
                .isWithin(1e-6f).of(0.5f);
        assertThat(RxAudioChannel.foldPcmFrame((short) 0, (short) -16384, RxAudioChannel.RIGHT))
                .isWithin(1e-6f).of(-0.5f);
        // Mix must match the pre-existing (l + r) * (0.5 / 32768) behaviour exactly.
        assertThat(RxAudioChannel.foldPcmFrame((short) 16384, (short) 8192, RxAudioChannel.MIX))
                .isWithin(1e-6f).of((16384 + 8192) * (0.5f / 32768.0f));
    }

    @Test
    public void foldToMono_halvesTheSampleCount() {
        float[] in = {1f, 2f, 3f, 4f, 5f, 6f};
        float[] out = new float[3];

        assertThat(RxAudioChannel.foldToMono(in, in.length, RxAudioChannel.LEFT, out)).isEqualTo(3);
        assertThat(out).usingExactEquality().containsExactly(1f, 3f, 5f).inOrder();

        assertThat(RxAudioChannel.foldToMono(in, in.length, RxAudioChannel.RIGHT, out)).isEqualTo(3);
        assertThat(out).usingExactEquality().containsExactly(2f, 4f, 6f).inOrder();

        assertThat(RxAudioChannel.foldToMono(in, in.length, RxAudioChannel.MIX, out)).isEqualTo(3);
        assertThat(out).usingExactEquality().containsExactly(1.5f, 3.5f, 5.5f).inOrder();
    }

    @Test
    public void foldToMono_usesOnlyTheValidPrefixOfTheBuffer() {
        // AudioRecord.read() fills a prefix of an oversized buffer; the stale
        // tail must not reach the decoder.
        float[] in = {1f, 2f, 3f, 4f, 99f, 99f};
        float[] out = new float[3];

        assertThat(RxAudioChannel.foldToMono(in, 4, RxAudioChannel.LEFT, out)).isEqualTo(2);
        assertThat(out[0]).isEqualTo(1f);
        assertThat(out[1]).isEqualTo(3f);
        assertThat(out[2]).isEqualTo(0f); // untouched
    }

    @Test
    public void foldToMono_dropsATornTrailingFrame() {
        // An odd sample count means a half-frame; pairing its left sample with
        // the next read's would swap the channels for the rest of the stream.
        float[] in = {1f, 2f, 3f};
        float[] out = new float[3];
        assertThat(RxAudioChannel.foldToMono(in, 3, RxAudioChannel.LEFT, out)).isEqualTo(1);
        assertThat(out[0]).isEqualTo(1f);
    }

    @Test
    public void foldToMono_handlesEmptyAndNullInputs() {
        float[] out = new float[4];
        assertThat(RxAudioChannel.foldToMono(null, 8, RxAudioChannel.LEFT, out)).isEqualTo(0);
        assertThat(RxAudioChannel.foldToMono(new float[] {1f, 2f}, 0, RxAudioChannel.LEFT, out))
                .isEqualTo(0);
        assertThat(RxAudioChannel.foldToMono(new float[] {1f, 2f}, 2, RxAudioChannel.LEFT, null))
                .isEqualTo(0);
    }

    @Test
    public void foldToMono_neverOverrunsTheDestination() {
        float[] in = {1f, 2f, 3f, 4f, 5f, 6f};
        float[] out = new float[2];
        assertThat(RxAudioChannel.foldToMono(in, in.length, RxAudioChannel.LEFT, out)).isEqualTo(2);
        assertThat(out).usingExactEquality().containsExactly(1f, 3f).inOrder();
    }

    @Test
    public void foldToMono_clampsSampleCountToTheBufferLength() {
        // A length larger than the array (a bad read result) must not throw.
        float[] in = {1f, 2f};
        float[] out = new float[4];
        assertThat(RxAudioChannel.foldToMono(in, 100, RxAudioChannel.RIGHT, out)).isEqualTo(1);
        assertThat(out[0]).isEqualTo(2f);
    }
}
