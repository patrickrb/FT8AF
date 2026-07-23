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

    // The reassembly buffer must not grow without bound. The remainder holds a
    // command still in progress (no terminator yet), so under normal operation it
    // stays tiny; but a stream that never sends 0xFD — a rig on the wrong baud,
    // line noise, or a misbehaving network peer — would otherwise accumulate every
    // byte forever and eventually OOM the app. The cap below (1 KiB) is orders of
    // magnitude above any real CI-V command, so these tests only exercise the
    // malformed-stream guard and never affect well-formed traffic.
    private static final int MAX_BUFFERED_BYTES = 1024;

    @Test
    public void terminatorlessStream_remainderStaysBounded() {
        // Simulate a rig streaming garbage that never contains a terminator across
        // many callbacks. Without the cap the carried buffer would grow to megabytes.
        byte[] buffered = new byte[0];
        byte[] noise = new byte[512]; // no 0xFD, no FE FE
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (byte) (i & 0x7F); // 0x00..0x7F, never 0xFD or 0xFE
        }
        for (int call = 0; call < 100; call++) {
            CivFrameSplitter.Result r = CivFrameSplitter.split(buffered, noise);
            assertThat(r.commands).isEmpty();
            assertThat(r.remainder.length).isAtMost(MAX_BUFFERED_BYTES);
            buffered = r.remainder;
        }
    }

    @Test
    public void overflow_resyncsToMostRecentPreamble() {
        // Garbage with no terminator, then a fresh command's preamble and start.
        byte[] garbage = new byte[1100]; // all 0x00: no FD, no FE
        byte[] partialNext = {(byte) 0xFE, (byte) 0xFE, (byte) 0xE0, (byte) 0xA4, (byte) 0x03};
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], concat(garbage, partialNext));

        assertThat(r.commands).isEmpty();
        // Everything before the last FE FE is unparseable and is dropped; the live
        // command-in-progress is preserved so the next chunk completes it.
        assertThat(r.remainder).isEqualTo(partialNext);
    }

    @Test
    public void overflow_resyncedFrameStillCompletesOnNextChunk() {
        byte[] garbage = new byte[1100];
        byte[] frame = freqFrame();
        byte[] frameFirst = java.util.Arrays.copyOfRange(frame, 0, 5);
        byte[] frameRest = java.util.Arrays.copyOfRange(frame, 5, frame.length);

        CivFrameSplitter.Result r1 = CivFrameSplitter.split(new byte[0], concat(garbage, frameFirst));
        assertThat(r1.commands).isEmpty();
        assertThat(r1.remainder).isEqualTo(frameFirst);

        CivFrameSplitter.Result r2 = CivFrameSplitter.split(r1.remainder, frameRest);
        assertThat(r2.commands).hasSize(1);
        assertThat(r2.commands.get(0)).isEqualTo(frame);
        assertThat(r2.remainder).hasLength(0);
    }

    @Test
    public void overflow_noPreambleDropsNoiseButKeepsTrailingPreambleByte() {
        byte[] noiseEndingInFe = new byte[1100];
        noiseEndingInFe[noiseEndingInFe.length - 1] = (byte) 0xFE; // possible split preamble
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], noiseEndingInFe);

        assertThat(r.commands).isEmpty();
        // Keep the lone trailing 0xFE so a preamble split across the read boundary
        // still reassembles; everything else was pure noise.
        assertThat(r.remainder).isEqualTo(new byte[]{(byte) 0xFE});
    }

    @Test
    public void overflow_pureNoiseDropsEverything() {
        byte[] noise = new byte[1100]; // all 0x00: no FD, no FE
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], noise);

        assertThat(r.commands).isEmpty();
        assertThat(r.remainder).hasLength(0);
    }

    @Test
    public void overflow_lonePreambleWithHugeTail_isHardCapped() {
        // Pathological: a preamble at the very start followed by a terminator-less
        // run longer than the cap. Resync alone can't shrink it, so the trailing
        // window clamp guarantees boundedness.
        byte[] buf = new byte[2200];
        buf[0] = (byte) 0xFE;
        buf[1] = (byte) 0xFE; // FE FE at index 0, nothing else notable, no FD
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], buf);

        assertThat(r.commands).isEmpty();
        assertThat(r.remainder.length).isEqualTo(MAX_BUFFERED_BYTES);
    }

    @Test
    public void largeButUnderCapPartial_isCarriedUnchanged() {
        // A big-but-legitimate partial command (still under the cap) must pass
        // through untouched — the guard only trims genuinely oversized buffers.
        byte[] partial = new byte[MAX_BUFFERED_BYTES];
        partial[0] = (byte) 0xFE;
        partial[1] = (byte) 0xFE; // valid-looking start, no terminator yet
        CivFrameSplitter.Result r = CivFrameSplitter.split(new byte[0], partial);

        assertThat(r.commands).isEmpty();
        assertThat(r.remainder).isEqualTo(partial);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
