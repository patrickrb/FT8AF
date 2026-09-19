package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Verifies {@link DatabaseOpr#recountActivationQsos}, the dupe-aware replacement for
 * the blind {@code qso_count + 1} bump (issue #823). POTA does not credit a repeat
 * contact with the same station on the same band within an activation — mode is
 * irrelevant — so the counter must be a COUNT(DISTINCT call+band) over the
 * activation's QSO window, and recounting must also heal any drift a previous blind
 * bump left behind.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprPotaRecountTest {

    /** 2026-01-01 00:00:00 UTC — activation start; stamp 20260101000000. */
    private static final long STARTED_AT_MS = 1_767_225_600_000L;

    private DatabaseOpr opr;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        db = opr.getWritableDatabase();
    }

    @After
    public void tearDown() {
        opr.close();
    }

    private void insertActivation(String parkRef, long startedAtMs, Long endedAtMs, int qsoCount) {
        db.execSQL("INSERT INTO pota_activation(park_ref, started_at, ended_at, qso_count) "
                        + "VALUES (?, ?, ?, ?)",
                new Object[]{parkRef, startedAtMs, endedAtMs, qsoCount});
    }

    private void insertQso(String call, String band, String mode, String date, String time,
                           String mySig, String mySigInfo) {
        db.execSQL("INSERT INTO QSLTable(call, band, mode, qso_date, time_on, my_sig, my_sig_info) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                new Object[]{call, band, mode, date, time, mySig, mySigInfo});
    }

    private int storedCount(String parkRef) {
        Cursor c = db.rawQuery("SELECT qso_count FROM pota_activation WHERE park_ref = ?",
                new String[]{parkRef});
        try {
            assertThat(c.moveToFirst()).isTrue();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    @Test
    public void countsDistinctCallPlusBand_ignoringModeCaseAndDupes() {
        insertActivation("K-1234", STARTED_AT_MS, null, 0);
        // Counts: W1AW on 20m (first contact)...
        insertQso("W1AW", "20m", "FT8", "20260101", "010000", "POTA", "K-1234");
        // ...dupe: same station, same band, different mode.
        insertQso("W1AW", "20m", "FT4", "20260101", "020000", "POTA", "K-1234");
        // ...dupe: same station+band again, case/width variants of the same key.
        insertQso("w1aw", "20M", "FT8", "20260101", "030000", "POTA", "K-1234");
        // Counts: same station on another band is a fresh credit.
        insertQso("W1AW", "40m", "FT8", "20260101", "040000", "POTA", "K-1234");
        // Counts: different station.
        insertQso("K2XYZ", "20m", "FT8", "20260101", "050000", "POTA", "K-1234");

        int count = DatabaseOpr.recountActivationQsos(db, "K-1234");

        assertThat(count).isEqualTo(3);
        assertThat(storedCount("K-1234")).isEqualTo(3);
    }

    @Test
    public void excludesRowsOutsideTheActivationWindowOrPark() {
        insertActivation("K-1234", STARTED_AT_MS, null, 0);
        insertQso("W1AW", "20m", "FT8", "20260101", "010000", "POTA", "K-1234");
        // Before started_at: an earlier visit to the same park must not bleed in.
        insertQso("N0PRE", "20m", "FT8", "20251231", "230000", "POTA", "K-1234");
        // Different park ref and non-POTA my_sig rows don't belong to this activation.
        insertQso("K7OTH", "20m", "FT8", "20260101", "020000", "POTA", "K-9999");
        insertQso("K8SIG", "20m", "FT8", "20260101", "020000", "SOTA", "K-1234");
        // Odd-width imported time ("815" = 08:15) still lands inside the window.
        insertQso("K9IMP", "17m", "FT8", "20260101", "815", "POTA", "K-1234");

        int count = DatabaseOpr.recountActivationQsos(db, "K-1234");

        assertThat(count).isEqualTo(2);
        assertThat(storedCount("K-1234")).isEqualTo(2);
    }

    @Test
    public void recountHealsDriftFromOldBlindBumps() {
        // A pre-fix install could have qso_count inflated by dupes; one recount fixes it.
        insertActivation("K-1234", STARTED_AT_MS, null, 9);
        insertQso("W1AW", "20m", "FT8", "20260101", "010000", "POTA", "K-1234");
        insertQso("W1AW", "20m", "FT8", "20260101", "020000", "POTA", "K-1234");

        assertThat(DatabaseOpr.recountActivationQsos(db, "K-1234")).isEqualTo(1);
        assertThat(storedCount("K-1234")).isEqualTo(1);
    }

    @Test
    public void leavesEndedActivationsAloneAndReportsNoActiveRow() {
        insertActivation("K-1234", STARTED_AT_MS, STARTED_AT_MS + 3_600_000L, 5);
        insertQso("W1AW", "20m", "FT8", "20260101", "010000", "POTA", "K-1234");

        // No active (ended_at IS NULL) row for this ref: recount signals -1 and the
        // finished activation's stored history count is untouched.
        assertThat(DatabaseOpr.recountActivationQsos(db, "K-1234")).isEqualTo(-1);
        assertThat(storedCount("K-1234")).isEqualTo(5);
    }
}
