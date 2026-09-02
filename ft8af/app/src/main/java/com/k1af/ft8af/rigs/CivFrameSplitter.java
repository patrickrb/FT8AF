package com.k1af.ft8af.rigs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reassembles a CI-V byte stream (Icom / Xiegu rigs) into whole commands.
 *
 * <p>CI-V commands are framed as {@code FE FE <to> <from> <cmd> [data...] FD}
 * and delivered by the serial/network transport in arbitrary chunks: a single
 * read may carry only part of a command, several whole commands (a rig commonly
 * echoes the request and then sends its reply back-to-back in one read), or a
 * whole command followed by the start of the next. Callers feed each received
 * chunk together with the bytes left over from the previous chunk; this groups
 * them into complete commands (each terminated by {@code 0xFD}) plus a trailing
 * remainder to carry into the next call.
 *
 * <p>Pure logic — no rig or Android state — so it is unit-testable and shared by
 * every CI-V rig ({@link IcomRig}, {@link XieGuRig}, {@link XieGu6100Rig}). Each
 * of those previously carried its own copy of this reassembly that (a) discarded
 * the accumulated buffer whenever a chunk arrived without a terminator, losing
 * every earlier fragment of a split command, and (b) appended two stray
 * {@code 0x00} bytes to the carried-over remainder after every frame, corrupting
 * the next command when it was reassembled from more than one chunk.
 *
 * <p>The carried-over remainder is also bounded: a stream that never delivers the
 * {@code 0xFD} terminator (a rig on the wrong baud, line noise, a misbehaving
 * network peer) would otherwise grow it without limit and eventually exhaust
 * memory. See {@link #boundRemainder}.
 */
final class CivFrameSplitter {
    /** CI-V end-of-message terminator. */
    private static final byte END_MARKER = (byte) 0xFD;
    /** CI-V preamble byte; a command opens with two of these ({@code FE FE}). */
    private static final byte PREAMBLE = (byte) 0xFE;

    /**
     * Hard cap on the carried-over remainder. A well-formed CI-V command this app
     * exchanges is a few to a few dozen bytes, so the reassembly buffer should
     * never approach this. It only matters when the terminator {@code 0xFD} never
     * arrives — a rig on the wrong baud, line noise, or a misbehaving network peer
     * streaming garbage — which would otherwise grow {@code dataBuffer} without
     * bound and eventually OOM the app. 1 KiB is orders of magnitude above any
     * real frame yet keeps memory firmly bounded.
     */
    private static final int MAX_BUFFERED_BYTES = 1024;

    private CivFrameSplitter() {
    }

    /** Result of {@link #split}: the complete commands and the trailing remainder. */
    static final class Result {
        /** Complete commands, each ending in {@code 0xFD}, in arrival order. */
        final List<byte[]> commands;
        /** Bytes after the last terminator — an incomplete command to buffer. */
        final byte[] remainder;

        Result(List<byte[]> commands, byte[] remainder) {
            this.commands = commands;
            this.remainder = remainder;
        }
    }

    /**
     * Append {@code incoming} to {@code buffered} and split the result into whole
     * CI-V commands plus a trailing remainder.
     *
     * @param buffered bytes left over from the previous call (never null; may be empty)
     * @param incoming newly received bytes (never null; may be empty)
     * @return the complete commands and the bytes to carry into the next call
     */
    static Result split(byte[] buffered, byte[] incoming) {
        byte[] combined = new byte[buffered.length + incoming.length];
        System.arraycopy(buffered, 0, combined, 0, buffered.length);
        System.arraycopy(incoming, 0, combined, buffered.length, incoming.length);

        List<byte[]> commands = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < combined.length; i++) {
            if (combined[i] == END_MARKER) {
                commands.add(Arrays.copyOfRange(combined, start, i + 1));
                start = i + 1;
            }
        }
        return new Result(commands, boundRemainder(Arrays.copyOfRange(combined, start, combined.length)));
    }

    /**
     * Keep the trailing remainder from growing without bound when the terminator
     * never arrives. A remainder holds a command still in progress (it contains no
     * {@code 0xFD}, or it would have been split off already), so under normal
     * operation it stays tiny. Only a malformed stream lets it exceed
     * {@link #MAX_BUFFERED_BYTES}; when it does, resynchronise to the most recent
     * {@code FE FE} preamble — the earliest point a valid command could still begin
     * — discarding the unparseable bytes before it. Bytes preceding that preamble
     * can never complete a frame (a valid one would have ended in {@code 0xFD} and
     * been emitted), so dropping them is lossless for well-formed traffic and only
     * ever trims garbage. If no preamble is present the buffer is pure noise: keep
     * at most a trailing {@code 0xFE} in case a preamble is split across the read
     * boundary. A final trailing-window clamp guarantees boundedness even in the
     * pathological case of a lone preamble followed by a huge terminator-less run.
     */
    private static byte[] boundRemainder(byte[] remainder) {
        if (remainder.length <= MAX_BUFFERED_BYTES) {
            return remainder;
        }
        for (int i = remainder.length - 2; i >= 0; i--) {
            if (remainder[i] == PREAMBLE && remainder[i + 1] == PREAMBLE) {
                // Resynchronise to this preamble. If the tail from here still
                // exceeds the cap (a lone preamble ahead of a huge terminator-less
                // run) keep only its trailing window. Compute the copy start first
                // so we allocate at most MAX_BUFFERED_BYTES bytes rather than the
                // full oversized remainder — this guard fires precisely in the
                // malformed-stream case, where the extra copy would hurt most.
                int from = Math.max(i, remainder.length - MAX_BUFFERED_BYTES);
                return Arrays.copyOfRange(remainder, from, remainder.length);
            }
        }
        if (remainder[remainder.length - 1] == PREAMBLE) {
            return new byte[]{PREAMBLE};
        }
        return new byte[0];
    }
}
