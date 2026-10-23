package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.log.QSLRecord;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link DatabaseOpr#doInsertQSLData} must report {@code record.isInvalid} as the
 * {@code hasWarning} flag through {@link AfterInsertQSLData#doAfterInsert}, NOT as the
 * {@code isInvalid} parameter -- the two mean different things. {@code isInvalid} means
 * the record was never written to the database at all (no callsign); a record flagged by
 * {@code QSLRecord.isInvalid} (e.g. an unparseable RST_SENT/RST_RCVD or FREQ) is still
 * written, with just that one field dropped to a fallback value, so it must still count
 * as added/updated AND be reported as a warning -- not instead of being counted.
 *
 * <p>An earlier version of this fix passed {@code record.isInvalid} as the {@code
 * isInvalid} parameter itself, which made both of this app's {@code AfterInsertQSLData}
 * consumers (the in-app import dialog, the web-logger) exclude a degraded-but-written
 * record from "added"/"updated" entirely, under-reporting what was actually written.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprInsertCallbackTest {

    private static QSLRecord record(String call, boolean hasFieldWarning) {
        QSLRecord r = new QSLRecord(1_700_000_000_000L, 1_700_000_010_000L
                , "K1ABC", "FN31", call, "IO91"
                , 59, -10, "FT8", 14_074_000L, 1500);
        r.isInvalid = hasFieldWarning;
        return r;
    }

    private static final class RecordingCallback implements AfterInsertQSLData {
        Boolean lastIsInvalid;
        Boolean lastIsNewQSL;
        Boolean lastHasWarning;
        int callCount;

        @Override
        public void doAfterInsert(boolean isInvalid, boolean isNewQSL, boolean hasWarning) {
            lastIsInvalid = isInvalid;
            lastIsNewQSL = isNewQSL;
            lastHasWarning = hasWarning;
            callCount++;
        }
    }

    @Test
    public void newInsert_cleanRecord_reportsNoWarning() {
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        RecordingCallback cb = new RecordingCallback();

        opr.doInsertQSLData(record("K1ABC", false), cb, false);

        assertThat(cb.callCount).isEqualTo(1);
        assertThat(cb.lastIsInvalid).isFalse();
        assertThat(cb.lastIsNewQSL).isTrue();
        assertThat(cb.lastHasWarning).isFalse();
    }

    @Test
    public void newInsert_recordWithDroppedField_stillCountsAsAddedAndFlagsWarning() {
        // The record still has a callsign (not the missing-callsign early-return case) --
        // QSLRecord itself flagged it (e.g. an unparseable RST) but it's otherwise
        // insertable (the one bad field already fell back to a sentinel), so it must be
        // both actually inserted AND reported as added=true, invalid=false, warning=true.
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        RecordingCallback cb = new RecordingCallback();

        boolean inserted = opr.doInsertQSLData(record("K2DEF", true), cb, false);

        assertThat(inserted).isTrue();
        assertThat(cb.callCount).isEqualTo(1);
        assertThat(cb.lastIsInvalid).isFalse();
        assertThat(cb.lastIsNewQSL).isTrue();
        assertThat(cb.lastHasWarning).isTrue();
    }

    @Test
    public void updateOfExistingRecord_withDroppedField_reportsUpdateNotInvalid() {
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        // First pass: insert the record cleanly.
        opr.doInsertQSLData(record("K3GHI", false), null, false);

        // Second pass: same key (call+qso_date+time_on+mode via the constructor's fixed
        // startTime/endTime), now flagged -- this hits the UPDATE branch, not INSERT.
        RecordingCallback cb = new RecordingCallback();
        opr.doInsertQSLData(record("K3GHI", true), cb, false);

        assertThat(cb.callCount).isEqualTo(1);
        assertThat(cb.lastIsInvalid).isFalse();
        assertThat(cb.lastIsNewQSL).isFalse();
        assertThat(cb.lastHasWarning).isTrue();
    }

    @Test
    public void missingCallsign_reportsInvalidNotWarning() {
        // The pre-existing invalid case (no callsign at all): never written, and
        // isInvalid must be true regardless of the hasFieldWarning value passed in.
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        RecordingCallback cb = new RecordingCallback();

        boolean inserted = opr.doInsertQSLData(record(null, true), cb, false);

        assertThat(inserted).isFalse();
        assertThat(cb.lastIsInvalid).isTrue();
        assertThat(cb.lastIsNewQSL).isTrue();
        assertThat(cb.lastHasWarning).isFalse();
    }
}
