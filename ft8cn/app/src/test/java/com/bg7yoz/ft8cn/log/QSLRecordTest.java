package com.bg7yoz.ft8cn.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.HashMap;

/**
 * Coverage for the two readily-testable {@link QSLRecord} constructors and the
 * accessor/formatting surface:
 *   - the full-args "successful QSO" constructor (time formatting via UtcTimer,
 *     wavelength via BaseRigOperation, distance via MaidenheadGrid), and
 *   - the ADIF {@code HashMap} import constructor (field extraction + numeric
 *     parsing + error flags).
 *
 * Plain JUnit is enough: the unmocked {@code android.util.Log} calls return
 * defaults (testOptions.unitTests.returnDefaultValues = true), and every test
 * supplies COMMENT so the constructor never reaches the R.string resource branch.
 * Times are anchored to GMT epoch values so the assertions are timezone-stable.
 */
public class QSLRecordTest {

    private static final long EPOCH = 0L;            // 1970-01-01 00:00:00 UTC
    private static final long PLUS_15S = 15_000L;    // +15 s

    @Test
    public void fullConstructor_formatsTimesBandAndDefaultComment() {
        QSLRecord r = new QSLRecord(EPOCH, PLUS_15S, "K1ABC", "", "W1AW", "",
                -5, -10, "FT8", 14_074_000L, 1500);

        assertThat(r.getStartTime()).isEqualTo("19700101-000000");
        assertThat(r.getEndTime()).isEqualTo("19700101-000015");
        assertThat(r.getBandLength()).isEqualTo("20m");
        assertThat(r.getBandFreq()).isEqualTo(14_074_000L);
        assertThat(r.getMode()).isEqualTo("FT8");
        assertThat(r.getMyCallsign()).isEqualTo("K1ABC");
        assertThat(r.getToCallsign()).isEqualTo("W1AW");
        assertThat(r.getSendReport()).isEqualTo(-5);
        assertThat(r.getReceivedReport()).isEqualTo(-10);
        assertThat(r.getWavFrequency()).isEqualTo(1500);
        // Both grids empty -> distance branch skipped -> plain comment.
        assertThat(r.getComment()).isEqualTo("QSO by FT8AF");
    }

    @Test
    public void fullConstructor_withGrids_addsDistanceToComment() {
        QSLRecord r = new QSLRecord(EPOCH, PLUS_15S, "K1ABC", "FN31pr", "G0XYZ", "IO91wm",
                -5, -10, "FT8", 14_074_000L, 1500);
        // Non-empty grids drive MaidenheadGrid.getDistStrEN, which prefixes the comment.
        assertThat(r.getComment()).startsWith("Distance:");
        assertThat(r.getComment()).contains("QSO by FT8AF");
    }

    @Test
    public void swlQSOInfo_formatsDirectionArrow() {
        QSLRecord r = new QSLRecord(EPOCH, PLUS_15S, "K1ABC", "", "W1AW", "",
                -5, -10, "FT8", 14_074_000L, 1500);
        assertThat(r.swlQSOInfo()).isEqualTo("QSO of SWL:W1AW<--K1ABC");
    }

    @Test
    public void mapConstructor_extractsCoreFields() {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("STATION_CALLSIGN", "K1ABC");
        map.put("BAND", "20m");
        map.put("FREQ", "14.074");
        map.put("MODE", "FT8");
        map.put("QSO_DATE", "20231114");
        map.put("TIME_ON", "221320");
        map.put("TIME_OFF", "221335");
        map.put("RST_SENT", "-05");
        map.put("RST_RCVD", "-10");
        map.put("GRIDSQUARE", "IO91wm");
        map.put("MY_GRIDSQUARE", "FN31pr");
        map.put("COMMENT", "imported");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.isLotW_import).isTrue();
        assertThat(r.isInvalid).isFalse();
        assertThat(r.getToCallsign()).isEqualTo("W1AW");
        assertThat(r.getMyCallsign()).isEqualTo("K1ABC");
        assertThat(r.getBandLength()).isEqualTo("20m");
        assertThat(r.getBandFreq()).isEqualTo(14_074_000L); // 14.074 MHz -> Hz
        assertThat(r.getMode()).isEqualTo("FT8");
        assertThat(r.getQso_date()).isEqualTo("20231114");
        assertThat(r.getTime_on()).isEqualTo("221320");
        assertThat(r.getTime_off()).isEqualTo("221335");
        assertThat(r.getSendReport()).isEqualTo(-5);
        assertThat(r.getReceivedReport()).isEqualTo(-10);
        assertThat(r.getToMaidenGrid()).isEqualTo("IO91wm");
        assertThat(r.getMyMaidenGrid()).isEqualTo("FN31pr");
        assertThat(r.getComment()).isEqualTo("imported");
    }

    @Test
    public void mapConstructor_qslAndPotaFields() {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("QSL_RCVD", "Y");
        map.put("MY_SIG", "POTA");
        map.put("MY_SIG_INFO", "K-1234");
        map.put("SIG", "POTA");
        map.put("SIG_INFO", "K-5678");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.isLotW_QSL).isTrue();
        assertThat(r.getMySig()).isEqualTo("POTA");
        assertThat(r.getMySigInfo()).isEqualTo("K-1234");
        assertThat(r.getSig()).isEqualTo("POTA");
        assertThat(r.getSigInfo()).isEqualTo("K-5678");
    }

    @Test
    public void mapConstructor_missingReports_defaultToMinus120() {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.getReceivedReport()).isEqualTo(-120);
        assertThat(r.getSendReport()).isEqualTo(-120);
    }

    @Test
    public void mapConstructor_badFreq_flagsInvalid() {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("FREQ", "not-a-number");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.isInvalid).isTrue();
        assertThat(r.errorMSG).contains("freq");
    }

    @Test
    public void toStringAndHtml_includeTypeHeader() {
        QSLRecord r = new QSLRecord(EPOCH, PLUS_15S, "K1ABC", "", "W1AW", "",
                -5, -10, "FT8", 14_074_000L, 1500);
        assertThat(r.toString()).contains("QSLRecord{");
        assertThat(r.toHtmlString()).contains("QSLRecord{");
    }
}
