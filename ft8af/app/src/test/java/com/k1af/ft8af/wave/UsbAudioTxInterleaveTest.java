package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Tests {@link UsbAudioDevice#interleavePcm16} — the mono float → int16 PCM
 * layout the USB-direct transmit path hands the OUT endpoint, including the TX
 * channel selection on a stereo device. A UAC device plays exactly the bytes it
 * is given, so this is checked at the byte level.
 */
public class UsbAudioTxInterleaveTest {

    private static short[] shorts(byte[] pcm) {
        ByteBuffer bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[pcm.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = bb.getShort();
        return out;
    }

    @Test
    public void mono_deviceCarriesEverySampleOnItsOneChannel() {
        byte[] pcm = UsbAudioDevice.interleavePcm16(
                new float[] {0.5f, -0.5f}, 1, AudioChannelSelect.RIGHT);
        // Selection cannot apply on a mono device: 2 samples -> 2 shorts.
        assertThat(pcm).hasLength(4);
        assertThat(shorts(pcm)).isEqualTo(new short[] {16384, -16384});
    }

    @Test
    public void stereo_bothDuplicatesOntoEachChannel() {
        byte[] pcm = UsbAudioDevice.interleavePcm16(
                new float[] {0.5f, -0.5f}, 2, AudioChannelSelect.BOTH);
        assertThat(pcm).hasLength(8);
        assertThat(shorts(pcm)).isEqualTo(new short[] {16384, 16384, -16384, -16384});
    }

    @Test
    public void stereo_leftSilencesTheRightChannel() {
        byte[] pcm = UsbAudioDevice.interleavePcm16(
                new float[] {0.5f, -0.5f}, 2, AudioChannelSelect.LEFT);
        assertThat(shorts(pcm)).isEqualTo(new short[] {16384, 0, -16384, 0});
    }

    @Test
    public void stereo_rightSilencesTheLeftChannel() {
        byte[] pcm = UsbAudioDevice.interleavePcm16(
                new float[] {0.5f, -0.5f}, 2, AudioChannelSelect.RIGHT);
        assertThat(shorts(pcm)).isEqualTo(new short[] {0, 16384, 0, -16384});
    }

    @Test
    public void littleEndian_asTheEndpointExpects() {
        byte[] pcm = UsbAudioDevice.interleavePcm16(new float[] {0.5f}, 1, AudioChannelSelect.BOTH);
        // 16384 = 0x4000 -> low byte first.
        assertThat(pcm).isEqualTo(new byte[] {0x00, 0x40});
    }

    @Test
    public void clipsToInt16Range() {
        byte[] pcm = UsbAudioDevice.interleavePcm16(
                new float[] {2f, -2f}, 1, AudioChannelSelect.BOTH);
        assertThat(shorts(pcm)).isEqualTo(new short[] {32767, -32768});
    }

    @Test
    public void garbageSelectionBehavesAsBoth() {
        byte[] pcm = UsbAudioDevice.interleavePcm16(new float[] {0.5f}, 2, 42);
        assertThat(shorts(pcm)).isEqualTo(new short[] {16384, 16384});
    }

    @Test
    public void emptyInputYieldsNoBytes() {
        assertThat(UsbAudioDevice.interleavePcm16(new float[0], 2, AudioChannelSelect.LEFT))
                .isEmpty();
    }

    // ---- enumeration-time channel guess ----

    @Test
    public void enumeratedChannels_absentDirectionIsUnknown() {
        assertThat(UsbAudioDevice.enumeratedChannels(false, 192))
                .isEqualTo(AudioChannelCapability.UNKNOWN);
        assertThat(UsbAudioDevice.enumeratedChannels(true, 0))
                .isEqualTo(AudioChannelCapability.UNKNOWN);
    }

    @Test
    public void enumeratedChannels_judgesAtTheProvisionalRate() {
        // Same heuristic open() starts from: 48 kHz, 16-bit, one packet per ms.
        assertThat(UsbAudioDevice.enumeratedChannels(true, 96)).isEqualTo(1);
        assertThat(UsbAudioDevice.enumeratedChannels(true, 192)).isEqualTo(2);
        assertThat(UsbAudioDevice.enumeratedChannels(true, 200)).isEqualTo(2); // CM108-style
    }
}
