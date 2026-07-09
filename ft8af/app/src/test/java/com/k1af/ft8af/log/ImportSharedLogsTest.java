package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Pure-JVM coverage for {@link ImportSharedLogs#parseLogRecords(String)} — the
 * ADIF field-length parser used by the "open shared .adi" import path.
 *
 * <p>Regression: the old clamp was {@code valueLen = values[1].length() - 1}
 * with no re-check before {@code substring}. When a field declared a length
 * longer than the value actually present (a truncated or hand-edited record)
 * this silently dropped the last character of the value; for a zero-length
 * value it made {@code substring(0, -1)} throw
 * {@link StringIndexOutOfBoundsException}, which — caught at record scope —
 * discarded the entire QSO. The sibling importer {@link LogFileImport} clamps
 * to {@code values[1].length()} and re-checks {@code > 0}; these tests pin
 * {@code ImportSharedLogs} to the same, correct behaviour.
 *
 * <p>{@code parseLogRecords} touches no Android types, so no Robolectric runner
 * is needed.
 */
public class ImportSharedLogsTest {

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
        // valid gridsquare) was lost. It must now skip only the empty field and
        // still return the record with the good field intact.
        ArrayList<HashMap<String, String>> records =
                ImportSharedLogs.parseLogRecords("<call:3>>X<gridsquare:4>FN42<eor>");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("GRIDSQUARE")).isEqualTo("FN42");
        assertThat(records.get(0)).doesNotContainKey("CALL");
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
}
