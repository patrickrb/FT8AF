package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Unit tests for {@link AdifLogFile}: the append-only ft8af_log.adi writer and the
 * {@link QSLRecord} → {@link AdifRecord} mapping.
 *
 * <p>{@link AdifLogFile#appendRecord} is deliberately Android-free (plain {@link File} I/O),
 * so it is exercised here directly with a temp file. Plain JUnit — the unmocked
 * {@code android.util.Log} calls in {@link QSLRecord} return defaults.
 */
public class AdifLogFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static AdifRecord record(String call) {
        return new AdifRecord().call(call).mode("FT8").qsoDate("20231114").timeOn("221320")
                .comment("QSO by FT8AF");
    }

    private String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void freshFile_getsHeaderThenRecord() throws Exception {
        File f = new File(tmp.getRoot(), AdifLogFile.FILE_NAME);
        AdifLogFile.appendRecord(f, record("W1AW"));

        String text = read(f);
        assertThat(text).startsWith(AdifRecord.HEADER);
        assertThat(text).contains("<eoh>");
        assertThat(text).contains("<call:4>W1AW ");
        assertThat(text).endsWith("<eor>\n");
    }

    @Test
    public void header_writtenOnceNotReEmittedOnSecondAppend() throws Exception {
        File f = new File(tmp.getRoot(), AdifLogFile.FILE_NAME);
        AdifLogFile.appendRecord(f, record("W1AW"));
        AdifLogFile.appendRecord(f, record("K1ABC"));

        String text = read(f);
        // Exactly one header/<eoh>, and both records preserved (append mode).
        assertThat(count(text, "<eoh>")).isEqualTo(1);
        assertThat(count(text, "<eor>")).isEqualTo(2);
        assertThat(text).contains("<call:4>W1AW ");
        assertThat(text).contains("<call:5>K1ABC ");
        // The first record precedes the second.
        assertThat(text.indexOf("W1AW")).isLessThan(text.indexOf("K1ABC"));
    }

    @Test
    public void fileDeletedBetweenRecords_headerIsRecreated() throws Exception {
        File f = new File(tmp.getRoot(), AdifLogFile.FILE_NAME);
        AdifLogFile.appendRecord(f, record("W1AW"));
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        AdifLogFile.appendRecord(f, record("K1ABC"));

        String text = read(f);
        assertThat(text).startsWith(AdifRecord.HEADER);
        assertThat(count(text, "<eoh>")).isEqualTo(1);
        assertThat(count(text, "<eor>")).isEqualTo(1);
        assertThat(text).contains("<call:5>K1ABC ");
        assertThat(text).doesNotContain("W1AW");
    }

    @Test
    public void preExistingEmptyFile_getsHeader() throws Exception {
        // A file that exists but is 0 bytes (e.g. truncated, or re-created empty by append
        // mode after a delete race) must still receive the header. appendRecord decides from
        // the opened stream's channel size, so exists()==true with size 0 still emits it.
        File f = new File(tmp.getRoot(), AdifLogFile.FILE_NAME);
        //noinspection ResultOfMethodCallIgnored
        f.createNewFile();
        assertThat(f.exists()).isTrue();
        assertThat(f.length()).isEqualTo(0);

        AdifLogFile.appendRecord(f, record("W1AW"));

        String text = read(f);
        assertThat(text).startsWith(AdifRecord.HEADER);
        assertThat(count(text, "<eoh>")).isEqualTo(1);
        assertThat(count(text, "<eor>")).isEqualTo(1);
    }

    @Test
    public void fromQslRecord_formatsReportsAndStripsHashedCall() {
        QSLRecord r = new QSLRecord(0L, 15_000L, "K1ABC", "", "<DK4RH>", "",
                5, -3, "FT8", 14_074_000L, 1500);
        String out = AdifLogFile.fromQslRecord(r).build();

        // Hashed call stripped; reports in WSJT-X +05 / -03 form.
        assertThat(out).contains("<call:5>DK4RH ");
        assertThat(out).contains("<rst_sent:3>+05 ");
        assertThat(out).contains("<rst_rcvd:3>-03 ");
        assertThat(out).contains("<station_callsign:5>K1ABC ");
        assertThat(out).contains("<band:3>20m ");
        assertThat(out).contains("<freq:9>14.074000 ");
        // On-air QSO -> not SWL.
        assertThat(out).contains("<QSL_RCVD:1>N ");
        assertThat(out).doesNotContain("<swl:");
    }

    @Test
    public void fromQslRecord_passesNoReportSentinelsThrough() {
        QSLRecord r = new QSLRecord(0L, 15_000L, "K1ABC", "", "W1AW", "",
                -100, -120, "FT8", 14_074_000L, 1500);
        String out = AdifLogFile.fromQslRecord(r).build();
        assertThat(out).contains("<rst_sent:4>-100 ");
        assertThat(out).contains("<rst_rcvd:4>-120 ");
    }

    @Test
    public void fromQslRecord_emitsPotaFieldsWhenSet() {
        QSLRecord r = new QSLRecord(0L, 15_000L, "K1ABC", "", "W1AW", "",
                5, -3, "FT8", 14_074_000L, 1500);
        r.setMySig("POTA");
        r.setMySigInfo("K-1234");
        String out = AdifLogFile.fromQslRecord(r).build();
        assertThat(out).contains("<MY_SIG:4>POTA ");
        assertThat(out).contains("<MY_SIG_INFO:6>K-1234 ");
        assertThat(out).doesNotContain("<SIG:");
    }

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
