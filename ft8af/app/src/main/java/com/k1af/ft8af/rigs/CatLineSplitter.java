package com.k1af.ft8af.rigs;

import java.util.ArrayList;
import java.util.List;

/**
 * Reassembles a text-framed CAT stream (Yaesu / Kenwood / Elecraft / Flex-6000
 * class rigs) into whole commands.
 *
 * <p>These rigs frame every reply as {@code <ID><data><terminator>} — e.g.
 * {@code FA014074000;} or {@code RM6020;} — and the serial / USB / Bluetooth
 * transport delivers bytes in arbitrary chunks: a single read may carry only
 * part of a command, several whole commands (the rig commonly answers two
 * back-to-back polls — the meter poll deliberately sends the ALC and SWR
 * requests one after the other — so both replies arrive in one read), or a whole
 * command followed by the start of the next. Callers feed each received chunk
 * together with the bytes left over from the previous chunk; this groups them
 * into complete commands (each up to, but excluding, a {@code terminator}) plus a
 * trailing remainder to carry into the next call.
 *
 * <p>Pure logic — no rig or Android state — so it is unit-testable, and it is the
 * text-terminator sibling of {@link CivFrameSplitter} (which does the same job
 * for the binary CI-V protocol). Each string-terminator rig previously carried
 * its own reassembly that parsed only the <em>first</em> command in a read and
 * pushed the untouched remainder — <em>including</em> its retained terminator and
 * any further complete commands — back into the buffer. That dropped the second
 * (and later) command of a coalesced read, and the retained terminator then
 * poisoned the parse of the next read (the stale {@code ID} was parsed as the
 * command and the real command landed inside the data field and was discarded).
 */
final class CatLineSplitter {
    private CatLineSplitter() {
    }

    /** Result of {@link #split}: the complete commands and the trailing remainder. */
    static final class Result {
        /** Complete commands, terminator stripped, in arrival order. */
        final List<String> frames;
        /** Bytes after the last terminator — an incomplete command to buffer. */
        final String remainder;

        Result(List<String> frames, String remainder) {
            this.frames = frames;
            this.remainder = remainder;
        }
    }

    /**
     * Append {@code incoming} to {@code buffered} and split the result into whole
     * commands plus a trailing remainder.
     *
     * <p>Every complete command is returned, so a read that coalesces several
     * replies yields several frames rather than one; the terminator is stripped
     * from each frame (matching the pre-existing parse, which fed the command
     * without its terminator). An empty frame (two adjacent terminators, or a
     * leading terminator) is preserved so callers see it and treat it as an
     * unknown/no-op command exactly as before.
     *
     * @param buffered   bytes left over from the previous call (never null; may be empty)
     * @param incoming   newly received bytes (never null; may be empty)
     * @param terminator the end-of-command character (e.g. {@code ';'})
     * @return the complete commands and the bytes to carry into the next call
     */
    static Result split(String buffered, String incoming, char terminator) {
        String combined = buffered + incoming;
        List<String> frames = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = combined.indexOf(terminator, start)) >= 0) {
            frames.add(combined.substring(start, idx));
            start = idx + 1;
        }
        return new Result(frames, combined.substring(start));
    }
}
