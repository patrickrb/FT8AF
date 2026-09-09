package com.k1af.ft8af.log;

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
    public void mapConstructor_mfskSubmodeResolvesToFt4() {
        // The bug: FT4/FT2 are exported as MODE=MFSK + SUBMODE=FT4 (both by FT8AF and
        // by WSJT-X/JTDX/pota.app). Reading only MODE stored "MFSK", losing the FT4
        // distinction and breaking mode-keyed dedup, band/mode filtering and re-export.
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("MODE", "MFSK");
        map.put("SUBMODE", "FT4");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.getMode()).isEqualTo("FT4");
    }

    @Test
    public void mapConstructor_readsManualQslUnderTheAppPrefixedName() {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put(AdifRecord.APP_QSL_MANUAL, "Y");

        assertThat(new QSLRecord(map).isQSL).isTrue();
    }

    @Test
    public void mapConstructor_stillReadsTheLegacyBareQslManualName() {
        // ADIF files written by FT8AF before the APP_ prefix — and by other loggers that
        // copied the bare name — must keep importing with their confirmation flag intact.
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put(AdifRecord.LEGACY_QSL_MANUAL, "Y");

        assertThat(new QSLRecord(map).isQSL).isTrue();
    }

    @Test
    public void mapConstructor_manualQslDefaultsToFalseAndHonoursN() {
        HashMap<String, String> plain = new HashMap<>();
        plain.put("CALL", "W1AW");
        plain.put("COMMENT", "imported");
        assertThat(new QSLRecord(plain).isQSL).isFalse();

        HashMap<String, String> explicitN = new HashMap<>();
        explicitN.put("CALL", "W1AW");
        explicitN.put("COMMENT", "imported");
        explicitN.put(AdifRecord.APP_QSL_MANUAL, "N");
        assertThat(new QSLRecord(explicitN).isQSL).isFalse();
    }

    @Test
    public void mapConstructor_conformantNameWinsWhenBothArePresent() {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put(AdifRecord.LEGACY_QSL_MANUAL, "Y");
        map.put(AdifRecord.APP_QSL_MANUAL, "N");

        assertThat(new QSLRecord(map).isQSL).isFalse();
    }

    @Test
    public void mapConstructor_mfskSubmodeResolvesToFt2() {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("MODE", "MFSK");
        map.put("SUBMODE", "FT2");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.getMode()).isEqualTo("FT2");
    }

    @Test
    public void mapConstructor_plainMfskWithoutSubmodeStaysMfsk() {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("MODE", "MFSK");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.getMode()).isEqualTo("MFSK");
    }

    @Test
    public void mapConstructor_ft8ModeUnaffectedBySubmodeResolution() {
        // Regression guard: a first-class MODE (FT8) is stored verbatim.
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("MODE", "FT8");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.getMode()).isEqualTo("FT8");
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
    public void mapConstructor_uhfFreq_preservesExactHz() {
        // 432.174 MHz (70cm). A 24-bit float mantissa cannot hold this many
        // significant Hz exactly, so the old float-based parse stored 432174016
        // (+16 Hz). Import must round-trip the exact dial frequency.
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("FREQ", "432.174000");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.isInvalid).isFalse();
        assertThat(r.getBandFreq()).isEqualTo(432_174_000L);
    }

    @Test
    public void mapConstructor_microwaveFreq_preservesExactHz() {
        // 1296.174 MHz (23cm). Float precision drifts even further here
        // (old value 1296173952, -48 Hz).
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("FREQ", "1296.174000");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.isInvalid).isFalse();
        assertThat(r.getBandFreq()).isEqualTo(1_296_174_000L);
    }

    @Test
    public void mapConstructor_hfFreq_unchanged() {
        // HF stays within float's exact-integer range; behaviour is preserved.
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("COMMENT", "imported");
        map.put("FREQ", "14.074000");

        QSLRecord r = new QSLRecord(map);

        assertThat(r.isInvalid).isFalse();
        assertThat(r.getBandFreq()).isEqualTo(14_074_000L);
    }

    @Test
    public void toStringAndHtml_includeTypeHeader() {
        QSLRecord r = new QSLRecord(EPOCH, PLUS_15S, "K1ABC", "", "W1AW", "",
                -5, -10, "FT8", 14_074_000L, 1500);
        assertThat(r.toString()).contains("QSLRecord{");
        assertThat(r.toHtmlString()).contains("QSLRecord{");
    }
}
