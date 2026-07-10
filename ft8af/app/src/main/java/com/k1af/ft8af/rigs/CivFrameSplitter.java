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
 */
final class CivFrameSplitter {
    /** CI-V end-of-message terminator. */
    private static final byte END_MARKER = (byte) 0xFD;

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
        return new Result(commands, Arrays.copyOfRange(combined, start, combined.length));
    }
}
