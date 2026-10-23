package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Tests {@link AdifStreamReader} -- the streaming tokenizer behind the ADIF
 * import memory fix. A real 87 MB ADIF log could not be parsed under this
 * app's ~256 MB heap on a real device with the previous whole-file-then-split
 * approach; this reader never holds more than roughly one record's worth of
 * text in memory. Most tests use a deliberately tiny chunk size (the
 * package-private constructor) to actually exercise tags and multi-byte
 * characters splitting across a chunk boundary, not just the common case of
 * everything fitting in one read.
 */
public class AdifStreamReaderTest {

    private static InputStream utf8(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static List<HashMap<String, String>> readAll(AdifStreamReader reader) throws IOException {
        List<HashMap<String, String>> records = new ArrayList<>();
        HashMap<String, String> record;
        while ((record = reader.nextRecord()) != null) {
            records.add(record);
        }
        return records;
    }

    @Test
    public void singleRecord_afterHeader_parsesCorrectly() throws IOException {
        String adif = "Some header text<eoh><call:5>K1ABC<gridsquare:4>FN42<eor>";
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 3)) {
            List<HashMap<String, String>> records = readAll(reader);
            assertThat(records).hasSize(1);
            assertThat(records.get(0)).containsEntry("CALL", "K1ABC");
            assertThat(records.get(0)).containsEntry("GRIDSQUARE", "FN42");
        }
    }

    @Test
    public void multipleRecords_allParsedInOrder() throws IOException {
        String adif = "<eoh><call:5>K1ABC<eor><call:5>K2DEF<eor><call:5>K3GHI<eor>";
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 4)) {
            List<HashMap<String, String>> records = readAll(reader);
            assertThat(records).hasSize(3);
            assertThat(records.get(0)).containsEntry("CALL", "K1ABC");
            assertThat(records.get(1)).containsEntry("CALL", "K2DEF");
            assertThat(records.get(2)).containsEntry("CALL", "K3GHI");
        }
    }

    @Test
    public void noHeaderTerminator_yieldsNoRecords() throws IOException {
        // Matches ImportSharedLogs.parseLogRecords: a file with no <eoh> at all has no
        // valid body, rather than treating the whole file as a header to discard.
        String adif = "<call:5>K1ABC<eor>"; // no <eoh>
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 3)) {
            assertThat(reader.nextRecord()).isNull();
        }
    }

    @Test
    public void caseInsensitiveTags_match() throws IOException {
        String adif = "<EOH><call:5>K1ABC<EOR><call:5>K2DEF<Eor>";
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 3)) {
            List<HashMap<String, String>> records = readAll(reader);
            assertThat(records).hasSize(2);
        }
    }

    @Test
    public void tagSplitAcrossChunkBoundary_stillDetected() throws IOException {
        // chunkChars=1 forces the <eor> tag to be read one character at a time, across
        // several fillBuffer() calls -- exercising the "re-scan only the unconsumed tail"
        // bookkeeping rather than a single lucky read containing the whole tag.
        String adif = "<eoh><call:5>K1ABC<eor><call:5>K2DEF<eor>";
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 1)) {
            List<HashMap<String, String>> records = readAll(reader);
            assertThat(records).hasSize(2);
            assertThat(records.get(0)).containsEntry("CALL", "K1ABC");
            assertThat(records.get(1)).containsEntry("CALL", "K2DEF");
        }
    }

    @Test
    public void multiByteUtf8CharacterSplitAcrossChunkBoundary_decodesCorrectly() throws IOException {
        // "Café" has a 2-byte UTF-8 character ('é'); chunkChars=1 means InputStreamReader's
        // internal byte-to-char decoding (not this class's own buffering) must be relied on
        // to never split that character's bytes across two returned chars. Declared length
        // is 5 (UTF-8 BYTES: C+a+f+é(2)+-- see AdifFormat#utf8Length/sliceByUtf8Length),
        // not the 4-char string length.
        String adif = "<eoh><call:5>K1ABC<comment:5>Café<eor>";
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 1)) {
            HashMap<String, String> record = reader.nextRecord();
            assertThat(record).containsEntry("COMMENT", "Café");
        }
    }

    @Test
    public void strayAngleBracketInsideARecord_doesNotStopRecordTokenizationEarly() throws IOException {
        // A field value containing a literal '<' that isn't part of a real tag is a
        // pre-existing field-parsing limitation shared with the non-streaming parser
        // (parseSingleRecord splits on every '<', not just tag boundaries -- out of scope
        // here, since this reader deliberately reuses that method unchanged). What this
        // tests is specific to the STREAMING tokenizer: that literal '<' must not be
        // mistaken for the record-ending <eor> tag, so the real one after it is still
        // found and the next record is still correctly parsed.
        String adif = "<eoh><call:5>K1ABC<comment:11>5 < 10, ok!<eor><call:5>K2DEF<eor>";
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 2)) {
            List<HashMap<String, String>> records = readAll(reader);
            assertThat(records).hasSize(2);
            assertThat(records.get(1)).containsEntry("CALL", "K2DEF");
        }
    }

    @Test
    public void truncatedFinalRecord_withNoTrailingEor_isStillReturned() throws IOException {
        // Matches parseLogRecords' leniency: a record cut off mid-file (no closing <eor>) is
        // still parsed from whatever text remains, rather than silently dropped.
        String adif = "<eoh><call:5>K1ABC<eor><call:5>K2DEF"; // no trailing <eor>
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 3)) {
            List<HashMap<String, String>> records = readAll(reader);
            assertThat(records).hasSize(2);
            assertThat(records.get(1)).containsEntry("CALL", "K2DEF");
        }
    }

    @Test
    public void trailingWhitespaceAfterLastRecord_yieldsNoExtraRecord() throws IOException {
        String adif = "<eoh><call:5>K1ABC<eor>\n\n   ";
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 3)) {
            List<HashMap<String, String>> records = readAll(reader);
            assertThat(records).hasSize(1);
        }
    }

    @Test
    public void emptyStream_yieldsNoRecords() throws IOException {
        try (AdifStreamReader reader = new AdifStreamReader(utf8(""), 3)) {
            assertThat(reader.nextRecord()).isNull();
        }
    }

    @Test
    public void malformedRecord_isSkipped_subsequentRecordsStillParsed() throws IOException {
        // A field whose declared length isn't a number throws inside parseSingleRecord;
        // nextRecord() must drop just that record (matching parseLogRecords' per-record
        // catch) and keep going, not abort the whole stream.
        String adif = "<eoh><call:bad>K1ABC<eor><call:5>K2DEF<eor>";
        try (AdifStreamReader reader = new AdifStreamReader(utf8(adif), 3)) {
            List<HashMap<String, String>> records = readAll(reader);
            assertThat(records).hasSize(1);
            assertThat(records.get(0)).containsEntry("CALL", "K2DEF");
        }
    }

    @Test
    public void close_doesNotThrow() throws IOException {
        AdifStreamReader reader = new AdifStreamReader(utf8("<eoh><call:5>K1ABC<eor>"));
        reader.nextRecord();
        reader.close();
    }
}
