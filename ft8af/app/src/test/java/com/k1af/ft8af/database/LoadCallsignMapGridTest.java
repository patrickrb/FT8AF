package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.database.sqlite.SQLiteDatabase;

import com.k1af.ft8af.GeneralVariables;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Covers {@link DatabaseOpr#loadCallsignMapGrid(SQLiteDatabase)}, the plain replacement
 * for the deprecated {@code GetCallsignMapGrid} AsyncTask (issue #455). The query and its
 * {@code LENGTH(grid) > 3} filter are exercised against an in-memory database so the
 * behaviour that used to live in {@code doInBackground} stays verified.
 */
@RunWith(RobolectricTestRunner.class)
public class LoadCallsignMapGridTest {

    private SQLiteDatabase newQslCallsignsDb() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE QslCallsigns (ID INTEGER PRIMARY KEY, callsign TEXT, grid TEXT)");
        return db;
    }

    @Test
    public void loads_callsign_grid_pairs_into_general_variables() {
        SQLiteDatabase db = newQslCallsignsDb();
        db.execSQL("INSERT INTO QslCallsigns (callsign, grid) VALUES ('LCMGT1', 'EM48')");

        DatabaseOpr.loadCallsignMapGrid(db);

        assertThat(GeneralVariables.getCallsignHasGrid("LCMGT1")).isTrue();
        assertThat(GeneralVariables.getCallsignHasGrid("LCMGT1", "EM48")).isTrue();
        db.close();
    }

    @Test
    public void skips_grids_of_three_or_fewer_characters() {
        SQLiteDatabase db = newQslCallsignsDb();
        // LENGTH(grid) > 3 in SQL, mirrored by GeneralVariables' length >= 4 guard.
        db.execSQL("INSERT INTO QslCallsigns (callsign, grid) VALUES ('LCMGT2', 'EM4')");

        DatabaseOpr.loadCallsignMapGrid(db);

        assertThat(GeneralVariables.getCallsignHasGrid("LCMGT2")).isFalse();
        db.close();
    }

    @Test
    public void handles_an_empty_table_without_error() {
        SQLiteDatabase db = newQslCallsignsDb();

        DatabaseOpr.loadCallsignMapGrid(db);

        assertThat(GeneralVariables.getCallsignHasGrid("LCMGT_ABSENT")).isFalse();
        db.close();
    }
}
