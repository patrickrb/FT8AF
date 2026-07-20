package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Pure-JVM coverage for the ADIF field-length handling in the shared-log
 * importer (the VIEW/SEND intent path that lets other apps hand a {@code .adi}
 * file to FT8AF). Exercises two extracted static helpers directly:
 * {@link ImportSharedLogs#parseLogRecords(String)} (whole-body record parsing)
 * and {@link ImportSharedLogs#extractFieldValue(String, int)} (the per-field
 * value clamp), neither of which touches Android types, so no Robolectric
 * runner is needed.
 *
 * <p>Regression guarded: the old clamp was {@code valueLen = values[1].length() - 1}
 * with no re-check before {@code substring}. When a field declared a length
 * longer than the value actually present (a truncated or hand-edited record)
 * this silently dropped the last character of the value; for a zero-length
 * value it made {@code substring(0, -1)} throw
 * {@link StringIndexOutOfBoundsException}, which — caught at record scope —
 * discarded the entire QSO. The fix clamps to {@code Math.min(declaredLen,
 * raw.length())} (mirroring {@link LogFileImport}); these tests pin that
 * behaviour.
 */
public class ImportSharedLogsTest {

    // ---- parseLogRecords(String): whole-body record parsing ----

    @Test
    public void wellFormedRecord_extractsValuesByDeclaredLength() {
        ArrayList<HashMap<String, String>> records =
                ImportSharedLogs.parseLogRecords("<call:5>K1ABC<gridsquare:4>FN42<eor>");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("CALL")).isEqualTo("K1ABC");
        assertThat(records.get(0).get("GRIDSQUARE")).isEqualTo("FN42");
    }

    @Test
    public void declaredLengthLongerThanValue_keepsWholeValue() {
        // Regression: value "K1" (2 chars) under a declared length of 5. The old
        // code clamped to length()-1 == 1 and returned "K", silently dropping
        // the last character. It must now return the full "K1".
        ArrayList<HashMap<String, String>> records =
                ImportSharedLogs.parseLogRecords("<call:5>K1<eor>");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("CALL")).isEqualTo("K1");
    }

    @Test
    public void zeroLengthValue_doesNotDiscardRecordOrCrash() {
        // A stray '>' yields an empty value token under a positive declared
        // length. The old code computed valueLen = -1 and threw
        // StringIndexOutOfBoundsException from substring(0, -1); because the
        // exception was caught at record scope, the WHOLE record (including the
        // valid gridsquare) was lost. It must now keep the record with the good
        // field intact. extractFieldValue clamps the empty value to "" rather
        // than omitting the field, so CALL is present but empty.
        ArrayList<HashMap<String, String>> records =
                ImportSharedLogs.parseLogRecords("<call:3>>X<gridsquare:4>FN42<eor>");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("GRIDSQUARE")).isEqualTo("FN42");
        assertThat(records.get(0).get("CALL")).isEqualTo("");
    }

    @Test
    public void fieldKeysAreUppercased() {
        HashMap<String, String> first =
                ImportSharedLogs.parseLogRecords("<call:5>K1ABC<mode:3>FT8<eor>").get(0);
        assertThat(first).containsKey("CALL");
        assertThat(first).containsKey("MODE");
        assertThat(first).doesNotContainKey("call");
    }

    @Test
    public void multipleRecords_allParsed() {
        ArrayList<HashMap<String, String>> records = ImportSharedLogs.parseLogRecords(
                "<call:5>K1ABC<eor><call:4>W1AW<eor>");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).get("CALL")).isEqualTo("K1ABC");
        assertThat(records.get(1).get("CALL")).isEqualTo("W1AW");
    }

    @Test
    public void emptyOrTaglessBody_returnsNoRecords() {
        assertThat(ImportSharedLogs.parseLogRecords("")).isEmpty();
        assertThat(ImportSharedLogs.parseLogRecords("just some free text\n")).isEmpty();
    }

    @Test
    public void pathologicalFieldLength_isSkipped() {
        // A field claiming a multi-megabyte length is ignored (MAX_ADIF_FIELD_LEN
        // guard) without dropping the surrounding, valid field.
        ArrayList<HashMap<String, String>> records = ImportSharedLogs.parseLogRecords(
                "<comment:9999999>x<call:5>K1ABC<eor>");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("CALL")).isEqualTo("K1ABC");
        assertThat(records.get(0)).doesNotContainKey("COMMENT");
    }

    // ---- extractFieldValue(String, int): per-field value clamp ----

    @Test
    public void valueMatchesDeclaredLength_isReturnedVerbatim() {
        assertThat(ImportSharedLogs.extractFieldValue("W1AW", 4)).isEqualTo("W1AW");
    }

    @Test
    public void valueLongerThanDeclared_isTrimmedToDeclaredLength() {
        // The raw slice runs up to the next '<', so it usually carries trailing
        // whitespace/newline; the declared length trims it back to the real value.
        assertThat(ImportSharedLogs.extractFieldValue("W1AW\n", 4)).isEqualTo("W1AW");
        assertThat(ImportSharedLogs.extractFieldValue("FT8   ", 3)).isEqualTo("FT8");
    }

    @Test
    public void valueShorterThanDeclared_keepsEveryCharacter() {
        // Regression: the old `values[1].length() - 1` clamp dropped the last
        // character of a truncated field, turning "FN31" into "FN3".
        assertThat(ImportSharedLogs.extractFieldValue("FN31", 6)).isEqualTo("FN31");
        assertThat(ImportSharedLogs.extractFieldValue("FN3", 4)).isEqualTo("FN3");
    }

    @Test
    public void declaredLengthOneLongerThanValue_keepsWholeValue() {
        // The exact case LogFileImportTest documents for the twin parser:
        // <station_callsign:5> declared for the 4-char value "W1AW".
        assertThat(ImportSharedLogs.extractFieldValue("W1AW", 5)).isEqualTo("W1AW");
    }

    @Test
    public void singleCharacterValueShorterThanDeclared_isNotEmptied() {
        // Old clamp turned this into "" (length 1 - 1 = 0); the value must survive.
        assertThat(ImportSharedLogs.extractFieldValue("X", 5)).isEqualTo("X");
    }
}
