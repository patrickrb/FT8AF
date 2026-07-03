package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link UacAudioFormats}: UAC 1.0 FORMAT_TYPE descriptor parsing and the
 * alt-setting selection that prefers 48 kHz capture (issue #364). Descriptor blobs are built
 * byte-for-byte the way {@code UsbDeviceConnection.getRawDescriptors()} returns them.
 */
public class UacAudioFormatsTest {

    private static final int PREFERRED = 48000;
    private static final int DECODER = 12000;

    // --- descriptor builders -------------------------------------------------

    /** Standard interface descriptor (bLength 9, bDescriptorType 4). */
    private static byte[] iface(int id, int alt, int cls, int subclass) {
        return new byte[] {9, 4, (byte) id, (byte) alt, 1, (byte) cls, (byte) subclass, 0, 0};
    }

    private static byte[] audioStreamingIface(int id, int alt) {
        return iface(id, alt, 0x01, 0x02);
    }

    /** Class-specific AS FORMAT_TYPE_I descriptor with a discrete sample-rate table. */
    private static byte[] formatTypeI(int channels, int subframeSize, int bits, int... rates) {
        int len = 8 + 3 * rates.length;
        byte[] d = new byte[len];
        d[0] = (byte) len;
        d[1] = 0x24;             // CS_INTERFACE
        d[2] = 0x02;             // FORMAT_TYPE
        d[3] = 0x01;             // FORMAT_TYPE_I (PCM)
        d[4] = (byte) channels;
        d[5] = (byte) subframeSize;
        d[6] = (byte) bits;
        d[7] = (byte) rates.length;
        for (int i = 0; i < rates.length; i++) {
            put24(d, 8 + 3 * i, rates[i]);
        }
        return d;
    }

    /** FORMAT_TYPE_I with a continuous min..max range (bSamFreqType == 0). */
    private static byte[] formatTypeIContinuous(int channels, int subframeSize, int bits,
                                                int minRate, int maxRate) {
        byte[] d = new byte[14];
        d[0] = 14;
        d[1] = 0x24;
        d[2] = 0x02;
        d[3] = 0x01;
        d[4] = (byte) channels;
        d[5] = (byte) subframeSize;
        d[6] = (byte) bits;
        d[7] = 0;
        put24(d, 8, minRate);
        put24(d, 11, maxRate);
        return d;
    }

    private static void put24(byte[] d, int off, int v) {
        d[off] = (byte) (v & 0xFF);
        d[off + 1] = (byte) ((v >> 8) & 0xFF);
        d[off + 2] = (byte) ((v >> 16) & 0xFF);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            bos.write(p, 0, p.length);
        }
        return bos.toByteArray();
    }

    /** Typical CM108-style blob: iface 2, alt 0 = zero-bandwidth, alt 1 = 48/44.1 kHz mono. */
    private static byte[] cm108Style() {
        return concat(
                audioStreamingIface(2, 0),
                audioStreamingIface(2, 1),
                formatTypeI(1, 2, 16, 48000, 44100));
    }

    // --- parse ---------------------------------------------------------------

    @Test
    public void parseAttributesFormatToInterfaceAndAltSetting() {
        List<UacAudioFormats.StreamFormat> formats = UacAudioFormats.parse(cm108Style());
        assertThat(formats).hasSize(1);
        UacAudioFormats.StreamFormat f = formats.get(0);
        assertThat(f.interfaceId).isEqualTo(2);
        assertThat(f.altSetting).isEqualTo(1);
        assertThat(f.channels).isEqualTo(1);
        assertThat(f.subframeSize).isEqualTo(2);
        assertThat(f.bitResolution).isEqualTo(16);
        assertThat(f.continuous).isFalse();
        assertThat(f.discreteRates).isEqualTo(new int[] {48000, 44100});
        assertThat(f.supportsRate(48000)).isTrue();
        assertThat(f.supportsRate(44100)).isTrue();
        assertThat(f.supportsRate(96000)).isFalse();
    }

    @Test
    public void parseCollectsMultipleAltSettings() {
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeI(1, 2, 16, 44100),
                audioStreamingIface(1, 2),
                formatTypeI(2, 2, 16, 48000));
        List<UacAudioFormats.StreamFormat> formats = UacAudioFormats.parse(raw);
        assertThat(formats).hasSize(2);
        assertThat(formats.get(0).altSetting).isEqualTo(1);
        assertThat(formats.get(0).discreteRates).isEqualTo(new int[] {44100});
        assertThat(formats.get(1).altSetting).isEqualTo(2);
        assertThat(formats.get(1).channels).isEqualTo(2);
    }

    @Test
    public void parseReadsContinuousRange() {
        byte[] raw = concat(
                audioStreamingIface(3, 1),
                formatTypeIContinuous(1, 2, 16, 8000, 48000));
        List<UacAudioFormats.StreamFormat> formats = UacAudioFormats.parse(raw);
        assertThat(formats).hasSize(1);
        UacAudioFormats.StreamFormat f = formats.get(0);
        assertThat(f.continuous).isTrue();
        assertThat(f.minRate).isEqualTo(8000);
        assertThat(f.maxRate).isEqualTo(48000);
        assertThat(f.supportsRate(24000)).isTrue();
        assertThat(f.supportsRate(96000)).isFalse();
    }

    @Test
    public void parseIgnoresFormatsOutsideAudioStreamingInterfaces() {
        byte[] raw = concat(
                iface(0, 0, 0x01, 0x01),            // AudioControl, not AudioStreaming
                formatTypeI(1, 2, 16, 48000),
                iface(4, 0, 0x03, 0x00),            // HID
                formatTypeI(1, 2, 16, 44100));
        assertThat(UacAudioFormats.parse(raw)).isEmpty();
    }

    @Test
    public void parseSurvivesMalformedInput() {
        assertThat(UacAudioFormats.parse(null)).isEmpty();
        assertThat(UacAudioFormats.parse(new byte[0])).isEmpty();
        // Zero bLength must not loop forever.
        assertThat(UacAudioFormats.parse(new byte[] {0, 0, 0, 0})).isEmpty();
        // Truncated descriptor: bLength runs past the end of the blob.
        byte[] good = cm108Style();
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 2);
        assertThat(UacAudioFormats.parse(truncated)).isEmpty();
        // A valid descriptor before the truncation still parses.
        byte[] partly = concat(cm108Style(), new byte[] {20, 0x24});
        assertThat(UacAudioFormats.parse(partly)).hasSize(1);
    }

    // --- choose --------------------------------------------------------------

    @Test
    public void choosePicksAltSettingSupporting48k() {
        // Device offers alt 1 = 44.1 kHz only, alt 2 = 48 kHz: alt 2 must win even though
        // enumeration found alt 1 first.
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeI(1, 2, 16, 44100),
                audioStreamingIface(1, 2),
                formatTypeI(1, 2, 16, 48000));
        UacAudioFormats.Choice c = UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 1, PREFERRED, DECODER);
        assertThat(c).isNotNull();
        assertThat(c.altSetting).isEqualTo(2);
        assertThat(c.sampleRate).isEqualTo(48000);
        assertThat(c.channels).isEqualTo(1);
    }

    @Test
    public void chooseKeepsCurrentAltWhenItAlreadySupports48k() {
        // Both alts support 48 kHz: don't switch away from the one already selected.
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeI(1, 2, 16, 48000, 44100),
                audioStreamingIface(1, 2),
                formatTypeI(2, 2, 16, 48000));
        UacAudioFormats.Choice c = UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 2, PREFERRED, DECODER);
        assertThat(c).isNotNull();
        assertThat(c.altSetting).isEqualTo(2);
        assertThat(c.sampleRate).isEqualTo(48000);
    }

    @Test
    public void chooseFallsBackTo44100WhenNo48k() {
        // The issue-#364 device: 44.1 kHz only. Must return the true rate (for the rational
        // resampler downstream), not pretend 48 kHz works.
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeI(1, 2, 16, 44100));
        UacAudioFormats.Choice c = UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 1, PREFERRED, DECODER);
        assertThat(c).isNotNull();
        assertThat(c.altSetting).isEqualTo(1);
        assertThat(c.sampleRate).isEqualTo(44100);
    }

    @Test
    public void choosePrefersIntegerMultipleOfDecoderRateOverCloserRate() {
        // No 48 kHz. 24000 is an exact multiple of 12000 (integer FIR fast path) and must
        // beat 44100 even though 44100 is closer to the preferred rate.
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeI(1, 2, 16, 44100),
                audioStreamingIface(1, 2),
                formatTypeI(1, 2, 16, 24000));
        UacAudioFormats.Choice c = UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 1, PREFERRED, DECODER);
        assertThat(c).isNotNull();
        assertThat(c.altSetting).isEqualTo(2);
        assertThat(c.sampleRate).isEqualTo(24000);
    }

    @Test
    public void chooseSkipsNon16BitAltSettings() {
        // Alt 2 has 48 kHz but at 24-bit (subframe 3); the capture path is hardwired to
        // 16-bit PCM, so the 16-bit 44.1 kHz alt must win.
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeI(1, 2, 16, 44100),
                audioStreamingIface(1, 2),
                formatTypeI(1, 3, 24, 48000));
        UacAudioFormats.Choice c = UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 1, PREFERRED, DECODER);
        assertThat(c).isNotNull();
        assertThat(c.altSetting).isEqualTo(1);
        assertThat(c.sampleRate).isEqualTo(44100);
    }

    @Test
    public void choosePicks48kFromContinuousRange() {
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeIContinuous(1, 2, 16, 8000, 96000));
        UacAudioFormats.Choice c = UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 1, PREFERRED, DECODER);
        assertThat(c).isNotNull();
        assertThat(c.sampleRate).isEqualTo(48000);
    }

    @Test
    public void choosePicksDecoderMultipleFromContinuousRangeBelow48k() {
        // Range tops out at 44.1 kHz: the largest decoder multiple inside the range (36000)
        // beats clamping to 44100, because it keeps the integer FIR fast path.
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeIContinuous(1, 2, 16, 8000, 44100));
        UacAudioFormats.Choice c = UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 1, PREFERRED, DECODER);
        assertThat(c).isNotNull();
        assertThat(c.sampleRate).isEqualTo(36000);
    }

    @Test
    public void chooseReturnsNullWhenNothingUsable() {
        assertThat(UacAudioFormats.choose(null, 1, 0, PREFERRED, DECODER)).isNull();
        assertThat(UacAudioFormats.choose(
                UacAudioFormats.parse(new byte[0]), 1, 0, PREFERRED, DECODER)).isNull();
        // Formats exist but for a different interface.
        List<UacAudioFormats.StreamFormat> other = UacAudioFormats.parse(cm108Style());
        assertThat(UacAudioFormats.choose(other, 7, 0, PREFERRED, DECODER)).isNull();
        // Only non-16-bit formats.
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeI(1, 3, 24, 48000));
        assertThat(UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 1, PREFERRED, DECODER)).isNull();
    }

    @Test
    public void chooseHonorsChannelCountFromDescriptor() {
        byte[] raw = concat(
                audioStreamingIface(1, 1),
                formatTypeI(2, 2, 16, 48000));
        UacAudioFormats.Choice c = UacAudioFormats.choose(
                UacAudioFormats.parse(raw), 1, 1, PREFERRED, DECODER);
        assertThat(c).isNotNull();
        assertThat(c.channels).isEqualTo(2);
    }
}
