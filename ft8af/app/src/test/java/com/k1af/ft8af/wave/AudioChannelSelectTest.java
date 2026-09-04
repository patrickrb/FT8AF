package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link AudioChannelSelect}, the shared stereo -> mono fold used by
 * all three RX capture paths.
 */
public class AudioChannelSelectTest {

    @Test
    public void clamp_keepsValidSelections() {
        assertThat(AudioChannelSelect.clamp(AudioChannelSelect.BOTH)).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelSelect.clamp(AudioChannelSelect.LEFT)).isEqualTo(AudioChannelSelect.LEFT);
        assertThat(AudioChannelSelect.clamp(AudioChannelSelect.RIGHT)).isEqualTo(AudioChannelSelect.RIGHT);
    }

    @Test
    public void clamp_foldsOutOfRangeToMix() {
        assertThat(AudioChannelSelect.clamp(-1)).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelSelect.clamp(3)).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelSelect.clamp(Integer.MAX_VALUE)).isEqualTo(AudioChannelSelect.BOTH);
    }

    @Test
    public void parse_readsPersistedValues() {
        assertThat(AudioChannelSelect.parse("0")).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelSelect.parse("1")).isEqualTo(AudioChannelSelect.LEFT);
        assertThat(AudioChannelSelect.parse("2")).isEqualTo(AudioChannelSelect.RIGHT);
        assertThat(AudioChannelSelect.parse(" 2 ")).isEqualTo(AudioChannelSelect.RIGHT);
    }

    @Test
    public void parse_defaultsToMixOnGarbage() {
        // A corrupted/imported config value must not throw on the load path.
        assertThat(AudioChannelSelect.parse(null)).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelSelect.parse("")).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelSelect.parse("left")).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelSelect.parse("7")).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelSelect.parse("1.0")).isEqualTo(AudioChannelSelect.BOTH);
    }

    @Test
    public void needsStereoCapture_onlyForSingleChannelSelections() {
        assertThat(AudioChannelSelect.needsStereoCapture(AudioChannelSelect.BOTH)).isFalse();
        assertThat(AudioChannelSelect.needsStereoCapture(AudioChannelSelect.LEFT)).isTrue();
        assertThat(AudioChannelSelect.needsStereoCapture(AudioChannelSelect.RIGHT)).isTrue();
        // Garbage clamps to BOTH, which is a mono open.
        assertThat(AudioChannelSelect.needsStereoCapture(99)).isFalse();
    }

    @Test
    public void foldFrame_picksTheSelectedChannel() {
        assertThat(AudioChannelSelect.foldFrame(1.0f, -1.0f, AudioChannelSelect.LEFT)).isEqualTo(1.0f);
        assertThat(AudioChannelSelect.foldFrame(1.0f, -1.0f, AudioChannelSelect.RIGHT)).isEqualTo(-1.0f);
        assertThat(AudioChannelSelect.foldFrame(1.0f, -1.0f, AudioChannelSelect.BOTH)).isEqualTo(0.0f);
        assertThat(AudioChannelSelect.foldFrame(0.5f, 0.1f, AudioChannelSelect.BOTH)).isWithin(1e-6f).of(0.3f);
    }

    @Test
    public void foldPcmFrame_scalesInt16ToUnitFloat() {
        assertThat(AudioChannelSelect.foldPcmFrame((short) 16384, (short) 0, AudioChannelSelect.LEFT))
                .isWithin(1e-6f).of(0.5f);
        assertThat(AudioChannelSelect.foldPcmFrame((short) 0, (short) -16384, AudioChannelSelect.RIGHT))
                .isWithin(1e-6f).of(-0.5f);
        // Mix must match the pre-existing (l + r) * (0.5 / 32768) behaviour exactly.
        assertThat(AudioChannelSelect.foldPcmFrame((short) 16384, (short) 8192, AudioChannelSelect.BOTH))
                .isWithin(1e-6f).of((16384 + 8192) * (0.5f / 32768.0f));
    }

    @Test
    public void foldToMono_halvesTheSampleCount() {
        float[] in = {1f, 2f, 3f, 4f, 5f, 6f};
        float[] out = new float[3];

        assertThat(AudioChannelSelect.foldToMono(in, in.length, AudioChannelSelect.LEFT, out)).isEqualTo(3);
        assertThat(out).usingExactEquality().containsExactly(1f, 3f, 5f).inOrder();

        assertThat(AudioChannelSelect.foldToMono(in, in.length, AudioChannelSelect.RIGHT, out)).isEqualTo(3);
        assertThat(out).usingExactEquality().containsExactly(2f, 4f, 6f).inOrder();

        assertThat(AudioChannelSelect.foldToMono(in, in.length, AudioChannelSelect.BOTH, out)).isEqualTo(3);
        assertThat(out).usingExactEquality().containsExactly(1.5f, 3.5f, 5.5f).inOrder();
    }

    @Test
    public void foldToMono_usesOnlyTheValidPrefixOfTheBuffer() {
        // AudioRecord.read() fills a prefix of an oversized buffer; the stale
        // tail must not reach the decoder.
        float[] in = {1f, 2f, 3f, 4f, 99f, 99f};
        float[] out = new float[3];

        assertThat(AudioChannelSelect.foldToMono(in, 4, AudioChannelSelect.LEFT, out)).isEqualTo(2);
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
        assertThat(AudioChannelSelect.foldToMono(in, 3, AudioChannelSelect.LEFT, out)).isEqualTo(1);
        assertThat(out[0]).isEqualTo(1f);
    }

    @Test
    public void foldToMono_handlesEmptyAndNullInputs() {
        float[] out = new float[4];
        assertThat(AudioChannelSelect.foldToMono(null, 8, AudioChannelSelect.LEFT, out)).isEqualTo(0);
        assertThat(AudioChannelSelect.foldToMono(new float[] {1f, 2f}, 0, AudioChannelSelect.LEFT, out))
                .isEqualTo(0);
        assertThat(AudioChannelSelect.foldToMono(new float[] {1f, 2f}, 2, AudioChannelSelect.LEFT, null))
                .isEqualTo(0);
    }

    @Test
    public void foldToMono_neverOverrunsTheDestination() {
        float[] in = {1f, 2f, 3f, 4f, 5f, 6f};
        float[] out = new float[2];
        assertThat(AudioChannelSelect.foldToMono(in, in.length, AudioChannelSelect.LEFT, out)).isEqualTo(2);
        assertThat(out).usingExactEquality().containsExactly(1f, 3f).inOrder();
    }

    @Test
    public void foldToMono_clampsSampleCountToTheBufferLength() {
        // A length larger than the array (a bad read result) must not throw.
        float[] in = {1f, 2f};
        float[] out = new float[4];
        assertThat(AudioChannelSelect.foldToMono(in, 100, AudioChannelSelect.RIGHT, out)).isEqualTo(1);
        assertThat(out[0]).isEqualTo(2f);
    }

    // ---- transmit side ----

    @Test
    public void needsStereoPlayback_onlyForASingleSide() {
        // BOTH must stay a mono open: that path is the historical TX pipeline and
        // is not worth disturbing (see the CLAUDE.md TX audio hazards).
        assertThat(AudioChannelSelect.needsStereoPlayback(AudioChannelSelect.BOTH)).isFalse();
        assertThat(AudioChannelSelect.needsStereoPlayback(AudioChannelSelect.LEFT)).isTrue();
        assertThat(AudioChannelSelect.needsStereoPlayback(AudioChannelSelect.RIGHT)).isTrue();
        assertThat(AudioChannelSelect.needsStereoPlayback(-5)).isFalse();
    }

    @Test
    public void writesChannel_picksTheSelectedSide() {
        assertThat(AudioChannelSelect.writesChannel(
                AudioChannelSelect.LEFT, AudioChannelSelect.CHANNEL_LEFT)).isTrue();
        assertThat(AudioChannelSelect.writesChannel(
                AudioChannelSelect.LEFT, AudioChannelSelect.CHANNEL_RIGHT)).isFalse();
        assertThat(AudioChannelSelect.writesChannel(
                AudioChannelSelect.RIGHT, AudioChannelSelect.CHANNEL_LEFT)).isFalse();
        assertThat(AudioChannelSelect.writesChannel(
                AudioChannelSelect.RIGHT, AudioChannelSelect.CHANNEL_RIGHT)).isTrue();
    }

    @Test
    public void writesChannel_bothDrivesEverySide() {
        assertThat(AudioChannelSelect.writesChannel(
                AudioChannelSelect.BOTH, AudioChannelSelect.CHANNEL_LEFT)).isTrue();
        assertThat(AudioChannelSelect.writesChannel(
                AudioChannelSelect.BOTH, AudioChannelSelect.CHANNEL_RIGHT)).isTrue();
    }

    @Test
    public void writesChannel_silencesChannelsWeDoNotDrive() {
        // A device with more than two channels: we only ever fill L and R, and
        // anything beyond them must be written as silence, not left as-is.
        assertThat(AudioChannelSelect.writesChannel(AudioChannelSelect.BOTH, 2)).isFalse();
        assertThat(AudioChannelSelect.writesChannel(AudioChannelSelect.LEFT, 2)).isFalse();
        assertThat(AudioChannelSelect.writesChannel(AudioChannelSelect.BOTH, -1)).isFalse();
    }

    @Test
    public void expandToStereo_duplicatesForBoth() {
        float[] mono = {0.25f, -0.5f};
        float[] out = new float[4];
        assertThat(AudioChannelSelect.expandToStereo(mono, 2, AudioChannelSelect.BOTH, out))
                .isEqualTo(4);
        assertThat(out).usingExactEquality()
                .containsExactly(0.25f, 0.25f, -0.5f, -0.5f).inOrder();
    }

    @Test
    public void expandToStereo_silencesTheExcludedSide() {
        float[] mono = {0.25f, -0.5f};
        float[] left = new float[4];
        assertThat(AudioChannelSelect.expandToStereo(mono, 2, AudioChannelSelect.LEFT, left))
                .isEqualTo(4);
        assertThat(left).usingExactEquality().containsExactly(0.25f, 0f, -0.5f, 0f).inOrder();

        float[] right = new float[4];
        assertThat(AudioChannelSelect.expandToStereo(mono, 2, AudioChannelSelect.RIGHT, right))
                .isEqualTo(4);
        assertThat(right).usingExactEquality().containsExactly(0f, 0.25f, 0f, -0.5f).inOrder();
    }

    @Test
    public void expandToStereo_writesSilenceOverStaleContents() {
        // The destination is reused across chunks, so the excluded channel must be
        // actively zeroed — a UAC device plays exactly the bytes it is handed, and
        // leftovers would go out as noise on the side meant to stay quiet.
        float[] out = {9f, 9f, 9f, 9f};
        AudioChannelSelect.expandToStereo(new float[] {1f, 2f}, 2, AudioChannelSelect.RIGHT, out);
        assertThat(out).usingExactEquality().containsExactly(0f, 1f, 0f, 2f).inOrder();
    }

    @Test
    public void expandToStereo_neverOverrunsTheDestination() {
        float[] mono = {1f, 2f, 3f};
        float[] out = new float[4]; // room for 2 frames only
        assertThat(AudioChannelSelect.expandToStereo(mono, 3, AudioChannelSelect.BOTH, out))
                .isEqualTo(4);
        assertThat(out).usingExactEquality().containsExactly(1f, 1f, 2f, 2f).inOrder();
    }

    @Test
    public void expandToStereo_toleratesEmptyAndNullInput() {
        float[] out = new float[4];
        assertThat(AudioChannelSelect.expandToStereo(null, 2, AudioChannelSelect.LEFT, out))
                .isEqualTo(0);
        assertThat(AudioChannelSelect.expandToStereo(new float[] {1f}, 0, AudioChannelSelect.LEFT, out))
                .isEqualTo(0);
        assertThat(AudioChannelSelect.expandToStereo(new float[] {1f}, 1, AudioChannelSelect.LEFT, null))
                .isEqualTo(0);
    }

    @Test
    public void parse_roundTripsBothConfigKeys() {
        // One class serves two persisted settings; the keys must stay distinct or
        // the RX choice would silently become the TX choice on load.
        assertThat(AudioChannelSelect.RX_CONFIG_KEY)
                .isNotEqualTo(AudioChannelSelect.TX_CONFIG_KEY);
    }
}
