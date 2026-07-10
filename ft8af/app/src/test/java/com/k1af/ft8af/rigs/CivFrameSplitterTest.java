package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link CivFrameSplitter}, the shared CI-V (Icom /
 * Xiegu) stream reassembler.
 *
 * <p>CI-V frames are {@code FE FE <to> <from> <cmd> [data...] FD}. The transport
 * delivers bytes in arbitrary chunks, so a command can span several callbacks or
 * several commands can arrive in one. These tests pin the reassembly contract and
 * lock in the two regressions the old per-rig reassembly had:
 * <ul>
 *   <li>a chunk with no terminator dropped everything buffered so far, so a
 *       command split across callbacks lost its leading fragments; and</li>
 *   <li>every processed frame left two stray {@code 0x00} bytes in the carry-over
 *       buffer, corrupting the next command reassembled from more than one chunk.</li>
 * </ul>
 *
 * <p>No Android types are touched, so no Robolectric runner is needed.
 */
public class CivFrameSplitterTest {

    private static final byte FD = (byte) 0xFD;

    /** A read-frequency reply (14.074 MHz), the reference frame from IcomCommandTest. */
    private static byte[] freqFrame() {
        return new byte[]{
                (byte) 0xFE, (byte) 0xFE, (byte) 0xE0, (byte) 0xA4,
                (byte) 0x03,
                (byte) 0x00, (byte) 0x40, (byte) 0x07, (byte) 0x14, (byte) 0x00,
                FD};
    }

    @Test
    public void singleCompleteFrame_oneCommandNoRemainder() {
        byte[] frame = freqFrame();
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], frame);

        assertThat(r.commands).hasSize(1);
        assertThat(r.commands.get(0)).isEqualTo(frame);
        // Regression: a fully-consumed frame must leave an EMPTY remainder, not the
        // two stray 0x00 bytes the old reassembly appended after every frame.
        assertThat(r.remainder).hasLength(0);
    }

    @Test
    public void twoFramesInOneChunk_bothCommandsNoRemainder() {
        // A rig commonly echoes the request then sends its reply back-to-back in a
        // single read. The old code processed only the first and mangled the rest.
        byte[] echo = {(byte) 0xFE, (byte) 0xFE, (byte) 0xA4, (byte) 0xE0, (byte) 0x03, FD};
        byte[] reply = freqFrame();
        byte[] chunk = concat(echo, reply);

        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], chunk);

        assertThat(r.commands).hasSize(2);
        assertThat(r.commands.get(0)).isEqualTo(echo);
        assertThat(r.commands.get(1)).isEqualTo(reply);
        assertThat(r.remainder).hasLength(0);
    }

    @Test
    public void completeFramePlusPartialNext_carriesOnlyThePartial() {
        byte[] frame = freqFrame();
        byte[] partial = {(byte) 0xFE, (byte) 0xFE, (byte) 0xE0}; // start of next, no FD
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], concat(frame, partial));

        assertThat(r.commands).hasSize(1);
        assertThat(r.commands.get(0)).isEqualTo(frame);
        assertThat(r.remainder).isEqualTo(partial);
    }

    @Test
    public void chunkWithoutTerminator_bufferedWholeAndNoCommands() {
        byte[] partial = {(byte) 0xFE, (byte) 0xFE, (byte) 0xE0, (byte) 0xA4, (byte) 0x03};
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], partial);

        assertThat(r.commands).isEmpty();
        assertThat(r.remainder).isEqualTo(partial);
    }

    @Test
    public void splitAcrossTwoCallbacks_reassemblesWithoutLosingLeadingBytes() {
        // The core regression: split one frame at an arbitrary point across two
        // callbacks. The old reassembly discarded the first fragment when the
        // second chunk (also without a terminator until its end) arrived.
        byte[] frame = freqFrame();
        byte[] first = java.util.Arrays.copyOfRange(frame, 0, 4);
        byte[] second = java.util.Arrays.copyOfRange(frame, 4, frame.length);

        CivFrameSplitter.Result r1 = CivFrameSplitter.split(new byte[0], first);
        assertThat(r1.commands).isEmpty();
        assertThat(r1.remainder).isEqualTo(first);

        CivFrameSplitter.Result r2 = CivFrameSplitter.split(r1.remainder, second);
        assertThat(r2.commands).hasSize(1);
        assertThat(r2.commands.get(0)).isEqualTo(frame);
        assertThat(r2.remainder).hasLength(0);
    }

    @Test
    public void splitAcrossThreeCallbacks_reassemblesExactBytes() {
        byte[] frame = freqFrame();
        byte[] a = java.util.Arrays.copyOfRange(frame, 0, 3);
        byte[] b = java.util.Arrays.copyOfRange(frame, 3, 7);
        byte[] c = java.util.Arrays.copyOfRange(frame, 7, frame.length);

        byte[] buffered = new byte[0];
        for (byte[] chunk : new byte[][]{a, b}) {
            CivFrameSplitter.Result r = CivFrameSplitter.split(buffered, chunk);
            assertThat(r.commands).isEmpty(); // no FD until the last chunk
            buffered = r.remainder;
        }
        CivFrameSplitter.Result last = CivFrameSplitter.split(buffered, c);
        assertThat(last.commands).hasSize(1);
        assertThat(last.commands.get(0)).isEqualTo(frame);
        assertThat(last.remainder).hasLength(0);
    }

    @Test
    public void frameThenSplitNextFrame_noStrayBytesCorruptTheReassembly() {
        // End-to-end proof of both fixes together: process a complete frame, then
        // reassemble the NEXT frame across two chunks through the carried buffer.
        // The old code would have seeded that buffer with 0x00 0x00, corrupting it.
        byte[] frame1 = freqFrame();
        byte[] frame2 = freqFrame();
        byte[] f2first = java.util.Arrays.copyOfRange(frame2, 0, 5);
        byte[] f2second = java.util.Arrays.copyOfRange(frame2, 5, frame2.length);

        CivFrameSplitter.Result r1 = CivFrameSplitter.split(new byte[0], concat(frame1, f2first));
        assertThat(r1.commands).hasSize(1);
        assertThat(r1.commands.get(0)).isEqualTo(frame1);
        assertThat(r1.remainder).isEqualTo(f2first); // exactly the partial, no stray bytes

        CivFrameSplitter.Result r2 = CivFrameSplitter.split(r1.remainder, f2second);
        assertThat(r2.commands).hasSize(1);
        assertThat(r2.commands.get(0)).isEqualTo(frame2);
        assertThat(r2.remainder).hasLength(0);
    }

    @Test
    public void emptyInputs_yieldNoCommandsAndEmptyRemainder() {
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], new byte[0]);
        assertThat(r.commands).isEmpty();
        assertThat(r.remainder).hasLength(0);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
