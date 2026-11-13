package com.k1af.ft8af.log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Streams raw ADIF records out of an {@link InputStream} without ever
 * buffering the whole file in memory. The previous approach (read everything
 * into a {@code ByteArrayOutputStream}, decode to one big {@code String},
 * split by regex) needed several times the raw file size in peak heap -- the
 * buffer, the decoded string, every per-record and per-field split copy, and
 * the fully parsed record list, all alive at once -- which a real 87 MB ADIF
 * log could not fit under this app's ~256 MB heap on a real device (a 50 MB,
 * then 100 MB, size cap was a stopgap for this; see {@link ImportSharedLogs}).
 *
 * <p>Reads in small chunks via an {@link InputStreamReader} (so a multi-byte
 * UTF-8 character split across a chunk boundary still decodes correctly --
 * decoding raw byte chunks independently would corrupt it), and scans only
 * the unconsumed tail of what's been read so far for the next {@code <eoh>}/
 * {@code <eor>} tag (case-insensitive, matching the non-streaming parsers'
 * {@code [Ee][Oo][Hh]}-style regexes). Peak memory is bounded by roughly one
 * record's worth of text, not the whole file.
 */
final class AdifStreamReader implements Closeable {
    private static final int CHUNK_CHARS = 8192;
    private static final String EOH_TAG = "<eoh>";
    private static final String EOR_TAG = "<eor>";

    private final Reader reader;
    private final int chunkChars;
    private final StringBuilder buf = new StringBuilder();
    // buf[0, scanned) has already been confirmed to hold no match for the tag
    // currently being searched for -- re-scanning it on every chunk read
    // would make a record with many stray '<' characters (e.g. a COMMENT
    // field) quadratic in the number of chunks it spans.
    private int scanned = 0;
    private boolean eof = false;
    private boolean headerSkipped = false;

    AdifStreamReader(InputStream in) {
        this(in, CHUNK_CHARS);
    }

    /** Visible for tests: a small chunk size exercises tag/char splitting across reads. */
    AdifStreamReader(InputStream in, int chunkChars) {
        this.reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        this.chunkChars = chunkChars;
    }

    /**
     * The next parsed record, or null at end of stream (including when the
     * stream never contains an {@code <eoh>} header terminator at all --
     * matching {@code ImportSharedLogs.parseLogRecords}' empty-body result
     * for the same case, rather than treating the whole file as the header
     * to discard). A chunk with no field tags at all (e.g. trailing
     * whitespace after the last real record) is skipped, and a record whose
     * fields fail to parse is dropped -- both mirroring {@code
     * parseLogRecords}' existing behavior.
     */
    HashMap<String, String> nextRecord() throws IOException {
        if (!headerSkipped) {
            headerSkipped = true;
            if (!skipPastHeader()) {
                buf.setLength(0); // no <eoh> found: there is no valid body to read
                return null;
            }
        }
        while (true) {
            String raw = nextChunkUpTo(EOR_TAG);
            if (raw == null) return null;
            if (!raw.contains("<")) continue;
            try {
                return ImportSharedLogs.parseSingleRecord(raw);
            } catch (Exception e) {
                // Discard just this record, matching parseLogRecords' per-record catch.
            }
        }
    }

    /** True once {@code <eoh>} has been found and discarded (along with everything before it); false at EOF with no {@code <eoh>} ever found. */
    private boolean skipPastHeader() throws IOException {
        while (true) {
            int searchFrom = Math.max(0, scanned - (EOH_TAG.length() - 1));
            int idx = indexOfIgnoreCase(buf, EOH_TAG, searchFrom);
            if (idx >= 0) {
                buf.delete(0, idx + EOH_TAG.length());
                scanned = 0;
                return true;
            }
            scanned = buf.length();
            if (eof) return false;
            fillBuffer();
        }
    }

    /**
     * Everything before the next occurrence of {@code tagLower} (consumed
     * along with the tag), reading more of the stream as needed. At end of
     * stream with no further tag found, returns whatever text is left
     * (possibly a final record missing its trailing {@code <eor>} -- a
     * truncated file -- matching {@code parseLogRecords}' leniency there),
     * or null once there is nothing left at all.
     */
    private String nextChunkUpTo(String tagLower) throws IOException {
        while (true) {
            int searchFrom = Math.max(0, scanned - (tagLower.length() - 1));
            int idx = indexOfIgnoreCase(buf, tagLower, searchFrom);
            if (idx >= 0) {
                String result = buf.substring(0, idx);
                buf.delete(0, idx + tagLower.length());
                scanned = 0;
                return result;
            }
            scanned = buf.length();
            if (eof) {
                if (buf.length() == 0) return null;
                String result = buf.toString();
                buf.setLength(0);
                scanned = 0;
                return result;
            }
            fillBuffer();
        }
    }

    private void fillBuffer() throws IOException {
        char[] chunk = new char[chunkChars];
        int n = reader.read(chunk);
        if (n < 0) {
            eof = true;
            return;
        }
        buf.append(chunk, 0, n);
    }

    /** Case-insensitive {@code indexOf}, scanning only from {@code fromIndex} onward. */
    private static int indexOfIgnoreCase(StringBuilder haystack, String needleLower, int fromIndex) {
        int n = needleLower.length();
        int limit = haystack.length() - n;
        for (int i = Math.max(fromIndex, 0); i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < n; j++) {
                if (Character.toLowerCase(haystack.charAt(i + j)) != needleLower.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
