package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Pure-logic coverage for {@link TrUSDXRig#indexOfByte(byte[], byte)}, the
 * delimiter-location step of the (tr)uSDX audio-over-CAT frame splitter.
 *
 * <p>The device multiplexes ';'-terminated ASCII CAT commands with raw 8-bit PCM
 * audio in a single byte stream. {@code onReceiveData} slices that {@code byte[]}
 * at each ';'. The historic code located the delimiter with
 * {@code new String(data).indexOf(";")} — a <em>char</em> offset — which drifts
 * away from the real <em>byte</em> offset whenever a &gt;= 0x80 audio sample
 * precedes the ';', mis-framing (and thus corrupting) the received audio. These
 * tests pin down the byte-exact behaviour.
 */
public class TrUSDXRigTest {

    @Test
    public void indexOfByte_findsDelimiterInPlainAsciiFrame() {
        byte[] data = "FA00014074000;".getBytes(StandardCharsets.US_ASCII);
        assertThat(TrUSDXRig.indexOfByte(data, (byte) ';')).isEqualTo(13);
    }

    @Test
    public void indexOfByte_returnsByteOffset_notCharOffset_whenHighSamplesPrecede() {
        // 0xC3 0xA9 is a valid UTF-8 pair that decodes to the single char 'é',
        // exactly the kind of collapse that raw audio samples cause.
        byte[] data = new byte[] {(byte) 0xC3, (byte) 0xA9, (byte) 0x40, (byte) ';'};

        // Byte-exact: the ';' really lives at byte offset 3.
        assertThat(TrUSDXRig.indexOfByte(data, (byte) ';')).isEqualTo(3);

        // The old char-index approach mis-locates it: the two-byte sequence
        // collapses to one char, so indexOf reports 2, not 3 — proving the bug
        // this fix removes.
        int charIdx = new String(data, StandardCharsets.UTF_8).indexOf(";");
        assertThat(charIdx).isEqualTo(2);
    }

    @Test
    public void indexOfByte_returnsFirstOccurrence() {
        byte[] data = new byte[] {(byte) 0x90, (byte) ';', 0x41, (byte) ';'};
        assertThat(TrUSDXRig.indexOfByte(data, (byte) ';')).isEqualTo(1);
    }

    @Test
    public void indexOfByte_returnsMinusOneWhenAbsent() {
        byte[] data = new byte[] {(byte) 0x80, (byte) 0xFF, 0x41};
        assertThat(TrUSDXRig.indexOfByte(data, (byte) ';')).isEqualTo(-1);
    }

    @Test
    public void indexOfByte_handlesEmptyInput() {
        assertThat(TrUSDXRig.indexOfByte(new byte[0], (byte) ';')).isEqualTo(-1);
    }

    @Test
    public void indexOfByte_findsDelimiterAtIndexZero() {
        byte[] data = new byte[] {(byte) ';', (byte) 0x80};
        assertThat(TrUSDXRig.indexOfByte(data, (byte) ';')).isEqualTo(0);
    }
}
