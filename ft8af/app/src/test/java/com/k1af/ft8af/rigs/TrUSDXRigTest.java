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

    // ---------------------------------------------------------------------
    // waveStartOffset(): where the "US" stream-start marker's audio payload
    // begins within the current ';'-terminated segment. The 2-byte "US"
    // marker can straddle a serial-read boundary, so part of it may have
    // arrived (and been buffered) in a previous read.
    // ---------------------------------------------------------------------

    @Test
    public void waveStartOffset_wholeMarkerInThisSegment() {
        // leftover empty, segment = "US" + audio: payload starts after the
        // 2-byte marker.
        assertThat(TrUSDXRig.waveStartOffset(0, 10)).isEqualTo(2);
    }

    @Test
    public void waveStartOffset_markerSplitOneByteInLeftover() {
        // Previous read left a lone 'U'; this segment starts with 'S' + audio,
        // so only the 'S' (1 byte) of the marker is in this segment.
        assertThat(TrUSDXRig.waveStartOffset(1, 5)).isEqualTo(1);
    }

    @Test
    public void waveStartOffset_wholeMarkerInLeftover() {
        // Both marker bytes arrived previously; the whole segment is audio.
        assertThat(TrUSDXRig.waveStartOffset(2, 5)).isEqualTo(0);
        assertThat(TrUSDXRig.waveStartOffset(3, 5)).isEqualTo(0);
    }

    @Test
    public void waveStartOffset_isClampedToSegmentLength_neverProducesFromGreaterThanTo() {
        // The crash case: the marker straddles the boundary and this segment is
        // shorter than the un-consumed marker (e.g. leftover "U", segment "S",
        // or an empty segment). The start must clamp to the segment length so
        // Arrays.copyOfRange(segment, start, segment.length) gets from <= to.
        assertThat(TrUSDXRig.waveStartOffset(1, 1)).isEqualTo(1); // "S" only
        assertThat(TrUSDXRig.waveStartOffset(2, 0)).isEqualTo(0); // empty segment
        assertThat(TrUSDXRig.waveStartOffset(0, 0)).isEqualTo(0); // empty segment, no leftover
        assertThat(TrUSDXRig.waveStartOffset(0, 1)).isEqualTo(1); // only 'U' of "US" here
    }

    @Test
    public void waveStartOffset_resultAlwaysYieldsValidCopyOfRange() {
        // Exhaustively confirm the helper never returns a start that would make
        // Arrays.copyOfRange throw (from < 0, from > to, or from > length).
        for (int leftover = 0; leftover <= 4; leftover++) {
            for (int segLen = 0; segLen <= 4; segLen++) {
                int start = TrUSDXRig.waveStartOffset(leftover, segLen);
                assertThat(start).isAtLeast(0);
                assertThat(start).isAtMost(segLen);
                // Sanity: this is exactly the copyOfRange the caller performs.
                byte[] segment = new byte[segLen];
                byte[] wave = java.util.Arrays.copyOfRange(segment, start, segLen);
                assertThat(wave.length).isEqualTo(segLen - start);
            }
        }
    }

    @Test
    public void waveStartOffset_oldHardcodedOffsetWouldThrow_provingTheBug() {
        // Before the fix the caller sliced Arrays.copyOfRange(segment, 2, len)
        // unconditionally. When the "US" marker straddles a read boundary the
        // segment can be shorter than 2, and copyOfRange(from=2, to<2) throws
        // IllegalArgumentException ("2 > to") — the crash this fix removes.
        try {
            java.util.Arrays.copyOfRange(new byte[1], 2, 1);
            throw new AssertionError("expected IllegalArgumentException from the old offset");
        } catch (IllegalArgumentException expected) {
            // exactly the pre-fix crash
        }
    }
}
