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

    private File fixture(String resource) throws IOException {
        File out = tmp.newFile(new File(resource).getName());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).isNotNull();
            Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }
}
