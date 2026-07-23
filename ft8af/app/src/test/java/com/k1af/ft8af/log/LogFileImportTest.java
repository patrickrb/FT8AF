package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.html.ImportTaskList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Drive {@link LogFileImport} against on-disk ADIF fixtures. The production
 * constructor takes a file path (it reads via FileInputStream), so we copy
 * each classpath resource into a TemporaryFolder file and feed the path in.
 *
 * Robolectric is here for {@code android.util.Log}; the import code itself
 * is plain Java.
 */
@RunWith(RobolectricTestRunner.class)
public class LogFileImportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ImportTaskList.ImportTask task;

    @Before
    public void setUp() {
        // Session id is opaque to the import path; any value works.
        task = new ImportTaskList.ImportTask(0);
    }

    @Test
    public void wellFormedAdif_parsesAllRecords() throws IOException {
        File f = fixture("adif/sample-wsjtx.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        ArrayList<HashMap<String, String>> records = imp.getLogRecords();
        assertThat(records).hasSize(3);
    }

    @Test
    public void wellFormedAdif_uppercasesFieldKeys() throws IOException {
        File f = fixture("adif/sample-wsjtx.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        HashMap<String, String> first = imp.getLogRecords().get(0);
        // Production code uppercases the field name before put() (line 96).
        assertThat(first).containsKey("CALL");
        assertThat(first).containsKey("MODE");
        assertThat(first).containsKey("BAND");
        // Lowercase keys should not exist.
        assertThat(first).doesNotContainKey("call");
    }

    @Test
    public void wellFormedAdif_extractsValuesUsingDeclaredLength() throws IOException {
        File f = fixture("adif/sample-wsjtx.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        HashMap<String, String> first = imp.getLogRecords().get(0);
        assertThat(first.get("CALL")).isEqualTo("K1ABC");
        assertThat(first.get("GRIDSQUARE")).isEqualTo("FN42");
        assertThat(first.get("MODE")).isEqualTo("FT8");
    }

    @Test
    public void getLogBody_returnsContentAfterEoh() throws IOException {
        File f = fixture("adif/sample-wsjtx.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        String body = imp.getLogBody();
        // The header is stripped; first surviving content should be the
        // first record marker.
        assertThat(body).doesNotContain("<adif_ver");
        assertThat(body).contains("<call:5>K1ABC");
    }

    @Test
    public void malformedAdif_skipsBadRecordsAndReportsErrorCount() throws IOException {
        File f = fixture("adif/sample-malformed.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        ArrayList<HashMap<String, String>> records = imp.getLogRecords();

        // The fixture has 4 pieces between <eor> markers:
        //   1. a valid K1ABC record               -> should be parsed
        //   2. a free-text line with no '<' tag   -> short-circuits at `s.contains("<")`
        //   3. <call:notanumber>BADREC...         -> NumberFormatException, counted in errorLines
        //   4. a valid W1AW record                -> should be parsed
        // The K1ABC record is added before iteration even reaches the bad
        // record, so the good records on either side survive.
        assertThat(records).hasSize(2);
        assertThat(records.get(0).get("CALL")).isEqualTo("K1ABC");
        assertThat(records.get(1).get("CALL")).isEqualTo("W1AW");
        assertThat(imp.getErrorCount()).isEqualTo(1);
    }

    @Test
    public void fieldValueContainingGreaterThan_isPreservedEndToEnd() throws IOException {
        // A field value may legally contain '>' (ADIF slices by the declared byte
        // length, not by the next '>'). The old parser truncated it at the first
        // interior '>'. Drive the full constructor -> getLogRecords path.
        File f = tmp.newFile("gt.adi");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("header<eoh><call:5>K1ABC<comment:11>hello>world<eor>"
                    .getBytes(StandardCharsets.UTF_8));
        }
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        HashMap<String, String> first = imp.getLogRecords().get(0);
        assertThat(first.get("CALL")).isEqualTo("K1ABC");
        assertThat(first.get("COMMENT")).isEqualTo("hello>world");
    }

    @Test
    public void emptyFile_returnsEmptyRecordList() throws IOException {
        File empty = tmp.newFile("empty.adi");
        // Write a header-only file with no <eoh> — getLogBody returns "".
        try (FileOutputStream out = new FileOutputStream(empty)) {
            out.write("header only, no eoh marker".getBytes(StandardCharsets.UTF_8));
        }
        LogFileImport imp = new LogFileImport(task, empty.getAbsolutePath());
        assertThat(imp.getLogRecords()).isEmpty();
    }

    @Test
    public void wellFormedAdif_getFileContext_returnsWholeFile() throws IOException {
        File f = fixture("adif/sample-wsjtx.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        // getFileContext returns the entire file including the header section.
        String ctx = imp.getFileContext();
        assertThat(ctx).contains("<adif_ver:5>3.1.0");
        assertThat(ctx).contains("<eoh>");
        assertThat(ctx).contains("DL1AA");
    }

    @Test
    public void wellFormedAdif_extractsAllRecordsValues() throws IOException {
        File f = fixture("adif/sample-wsjtx.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        ArrayList<HashMap<String, String>> records = imp.getLogRecords();
        // Second and third records carry distinct callsigns/grids.
        assertThat(records.get(1).get("CALL")).isEqualTo("VE3XY");
        assertThat(records.get(1).get("GRIDSQUARE")).isEqualTo("FN03");
        assertThat(records.get(2).get("CALL")).isEqualTo("DL1AA");
        assertThat(records.get(2).get("GRIDSQUARE")).isEqualTo("JO62");
    }

    @Test
    public void wellFormedAdif_capturesNumericFreqAndReports() throws IOException {
        File f = fixture("adif/sample-wsjtx.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        HashMap<String, String> first = imp.getLogRecords().get(0);
        // freq:8 declared length → "14.07415" is exactly 8 chars.
        assertThat(first.get("FREQ")).isEqualTo("14.07415");
        assertThat(first.get("RST_SENT")).isEqualTo("-08");
        assertThat(first.get("RST_RCVD")).isEqualTo("-12");
        // The fixture declares <station_callsign:5> for the 4-char value "W1AW";
        // the length-prefixed parser honours the declared length, so assert the
        // stable prefix rather than the exact (length-mismatched) slice.
        assertThat(first.get("STATION_CALLSIGN")).startsWith("W1A");
    }

    @Test
    public void wellFormedAdif_noErrors() throws IOException {
        File f = fixture("adif/sample-wsjtx.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        imp.getLogRecords();
        assertThat(imp.getErrorCount()).isEqualTo(0);
        assertThat(imp.getErrorLines()).isEmpty();
    }

    @Test
    public void malformedAdif_errorLinesHtmlEscapesAngleBrackets() throws IOException {
        File f = fixture("adif/sample-malformed.adi");
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        imp.getLogRecords();
        // The bad record is stored in errorLines with '<' escaped to "&lt;".
        HashMap<Integer, String> errors = imp.getErrorLines();
        assertThat(errors).hasSize(1);
        String badContent = errors.values().iterator().next();
        // Only '<' is escaped (replace("<","&lt;")); '>' is left intact.
        assertThat(badContent).contains("&lt;call:notanumber>BADREC");
        assertThat(badContent).doesNotContain("<call:notanumber>");
    }

    @Test
    public void getLogBody_returnsEmptyWhenNoEoh() throws IOException {
        File f = tmp.newFile("noeoh.adi");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("<call:5>K1ABC<eor>".getBytes(StandardCharsets.UTF_8));
        }
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        // No <eoh> marker → split produces a single element → body is "".
        assertThat(imp.getLogBody()).isEmpty();
        assertThat(imp.getLogRecords()).isEmpty();
    }

    @Test
    public void getLogBody_caseInsensitiveEohMarker() throws IOException {
        File f = tmp.newFile("upper.adi");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("header<EOH><call:5>K1ABC<eor>".getBytes(StandardCharsets.UTF_8));
        }
        LogFileImport imp = new LogFileImport(task, f.getAbsolutePath());
        // The split regex [<][Ee][Oo][Hh][>] matches upper-case <EOH> too.
        assertThat(imp.getLogBody()).contains("K1ABC");
        assertThat(imp.getLogRecords()).hasSize(1);
    }

    @Test
    public void readFully_readsWholeStreamAcrossShortReads() throws IOException {
        // A stream that dribbles out one byte per read() call, exactly the case
        // the old single-read-sized-by-available() code truncated. The payload is
        // larger than one read would ever return here.
        String payload = repeat("<call:5>K1ABC<eor>\n", 500);
        InputStream drip = new DripInputStream(payload.getBytes(StandardCharsets.UTF_8), 1);
        assertThat(LogFileImport.readFully(drip)).isEqualTo(payload);
    }

    @Test
    public void readFully_decodesUtf8Regardless() throws IOException {
        // Multibyte content must survive even when the stream hands back small chunks.
        String payload = "<comment:9>café テスト<eor>";
        InputStream drip = new DripInputStream(payload.getBytes(StandardCharsets.UTF_8), 3);
        assertThat(LogFileImport.readFully(drip)).isEqualTo(payload);
    }

    @Test
    public void readFully_emptyStreamReturnsEmptyString() throws IOException {
        InputStream drip = new DripInputStream(new byte[0], 4);
        assertThat(LogFileImport.readFully(drip)).isEmpty();
    }

    @Test(expected = IOException.class)
    public void readFully_throwsWhenExceedingCap() throws IOException {
        // A stream longer than MAX_IMPORT_BYTES must be rejected (defensive OOM guard on the
        // web-logger HTTP-upload path), not read unbounded. This synthetic stream reports
        // just over the cap without allocating a full copy up front.
        InputStream oversize = new InputStream() {
            private long remaining = (long) LogFileImport.MAX_IMPORT_BYTES + 1;

            @Override
            public int read() {
                return remaining-- > 0 ? 0 : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, remaining);
                remaining -= n;
                return n; // bytes left as-is; only the count matters for the cap
            }
        };
        LogFileImport.readFully(oversize);
    }

    @Test
    public void largeAdif_isNotTruncated() throws IOException {
        // End-to-end through the constructor: a file whose record body is much larger
        // than a single read is typically willing to return must parse every record.
        StringBuilder sb = new StringBuilder("header<eoh>\n");
        int count = 4000;
        for (int i = 0; i < count; i++) {
            sb.append("<call:5>K1ABC<eor>\n");
        }
        File big = tmp.newFile("big.adi");
        try (FileOutputStream out = new FileOutputStream(big)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        LogFileImport imp = new LogFileImport(task, big.getAbsolutePath());
        assertThat(imp.getLogRecords()).hasSize(count);
        // No NUL padding leaked in from a short read.
        assertThat(imp.getFileContext()).doesNotContain("\u0000");
    }

    // ---- parseRecord(String): pure per-record field parsing ----

    @Test
    public void parseRecord_extractsFieldsByDeclaredLength() {
        HashMap<String, String> r = LogFileImport.parseRecord("<call:5>K1ABC<mode:3>FT8");
        assertThat(r.get("CALL")).isEqualTo("K1ABC");
        assertThat(r.get("MODE")).isEqualTo("FT8");
    }

    @Test
    public void parseRecord_uppercasesKeys() {
        HashMap<String, String> r = LogFileImport.parseRecord("<call:5>K1ABC");
        assertThat(r).containsKey("CALL");
        assertThat(r).doesNotContainKey("call");
    }

    @Test
    public void parseRecord_valueContainingGreaterThan_isPreserved() {
        // Regression: the old field.split(">") kept only the token before the
        // second '>', truncating "hello>world" to "hello".
        HashMap<String, String> r = LogFileImport.parseRecord("<comment:11>hello>world");
        assertThat(r.get("COMMENT")).isEqualTo("hello>world");
    }

    @Test
    public void parseRecord_greaterThanInEarlierValue_doesNotCorruptLaterFields() {
        HashMap<String, String> r =
                LogFileImport.parseRecord("<comment:5>a>b>c<call:5>K1ABC");
        assertThat(r.get("COMMENT")).isEqualTo("a>b>c");
        assertThat(r.get("CALL")).isEqualTo("K1ABC");
    }

    @Test
    public void parseRecord_declaredLengthLongerThanValue_keepsWholeValue() {
        // <station_callsign:5> declared for the 4-char value "W1AW".
        HashMap<String, String> r = LogFileImport.parseRecord("<station_callsign:5>W1AW");
        assertThat(r.get("STATION_CALLSIGN")).isEqualTo("W1AW");
    }

    @Test
    public void parseRecord_typeQualifiedHeader_usesLengthNotType() {
        // ADIF allows <NAME:LEN:TYPE>; the length is ttt[1], the type is ignored.
        HashMap<String, String> r = LogFileImport.parseRecord("<freq:8:N>14.07415");
        assertThat(r.get("FREQ")).isEqualTo("14.07415");
    }

    @Test(expected = NumberFormatException.class)
    public void parseRecord_nonNumericLength_throwsForCallerToCount() {
        // getLogRecords wraps this in a try/catch that records the bad line; the
        // helper itself surfaces the parse failure rather than swallowing it.
        LogFileImport.parseRecord("<call:notanumber>BADREC");
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /** InputStream that returns at most {@code maxPerRead} bytes per read() call. */
    private static final class DripInputStream extends InputStream {
        private final byte[] data;
        private final int maxPerRead;
        private int pos = 0;

        DripInputStream(byte[] data, int maxPerRead) {
            this.data = data;
            this.maxPerRead = maxPerRead;
        }

        @Override
        public int read() {
            return pos < data.length ? (data[pos++] & 0xff) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, maxPerRead), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }

        // Deliberately misreport remaining bytes, like a content:// stream can.
        @Override
        public int available() {
            return 0;
        }
    }

    private File fixture(String resource) throws IOException {
        File out = tmp.newFile(new File(resource).getName());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).isNotNull();
            Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }
}
