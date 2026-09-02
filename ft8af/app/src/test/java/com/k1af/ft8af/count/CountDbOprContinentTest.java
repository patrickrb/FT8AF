package com.k1af.ft8af.count;

import static com.google.common.truth.Truth.assertThat;

import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Exercises {@link CountDbOpr#queryWorkedContinents} — the grid -> DXCC entity ->
 * continent join behind the "Worked All Continents" (WAC) award. Uses an
 * in-memory SQLite database mirroring the three tables the query touches
 * ({@code dxcc_grid}, {@code dxccList}, {@code QSLTable}); Robolectric provides
 * real SQLite.
 */
@RunWith(RobolectricTestRunner.class)
public class CountDbOprContinentTest {

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE QSLTable (id INTEGER PRIMARY KEY, gridsquare TEXT)");
        db.execSQL("CREATE TABLE dxcc_grid (dxcc INTEGER, grid TEXT)");
        db.execSQL("CREATE TABLE dxccList (dxcc INTEGER, continent TEXT)");

        // DXCC entities -> continent (one blank + Antarctica to exercise filtering).
        insertDxcc(1, "EU");
        insertDxcc(2, "NA");
        insertDxcc(3, "AN");   // Antarctica: returned raw, dropped later by the reducer
        insertDxcc(4, "");     // blank continent: filtered out in SQL

        // 4-char grid -> DXCC entity.
        insertGrid(1, "IO91");
        insertGrid(2, "FN20");
        insertGrid(3, "AA00");
        insertGrid(4, "BB11");
    }

    @After
    public void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private void insertDxcc(int dxcc, String continent) {
        db.execSQL("INSERT INTO dxccList (dxcc, continent) VALUES (?, ?)",
                new Object[]{dxcc, continent});
    }

    private void insertGrid(int dxcc, String grid) {
        db.execSQL("INSERT INTO dxcc_grid (dxcc, grid) VALUES (?, ?)",
                new Object[]{dxcc, grid});
    }

    private void logQso(String gridsquare) {
        db.execSQL("INSERT INTO QSLTable (gridsquare) VALUES (?)", new Object[]{gridsquare});
    }

    @Test
    public void resolvesWorkedContinents_fromSixCharGrids() {
        logQso("IO91np"); // EU
        logQso("FN20xr"); // NA

        List<String> continents = CountDbOpr.queryWorkedContinents(db);

        assertThat(continents).containsExactly("EU", "NA");
    }

    @Test
    public void matchIsCaseInsensitive_onTheGridPrefix() {
        // Operators frequently log the locator lower-cased; the query upper-cases
        // the 4-char prefix before joining dxcc_grid.
        logQso("aa00"); // -> AA00 -> AN

        List<String> continents = CountDbOpr.queryWorkedContinents(db);

        assertThat(continents).containsExactly("AN");
    }

    @Test
    public void blankContinentAndUnmatchedGrids_areExcluded() {
        logQso("BB11aa"); // maps to a DXCC whose continent is blank -> excluded
        logQso("ZZ99zz"); // no dxcc_grid row -> dropped by the inner join

        List<String> continents = CountDbOpr.queryWorkedContinents(db);

        assertThat(continents).isEmpty();
    }

    @Test
    public void deduplicatesContinents_acrossManyContacts() {
        logQso("IO91aa"); // EU
        logQso("IO91bb"); // EU (same continent, different sub-square)
        logQso("FN20cc"); // NA

        List<String> continents = CountDbOpr.queryWorkedContinents(db);

        // GROUP BY continent collapses the duplicate EU contacts to one row.
        assertThat(continents).containsExactly("EU", "NA");
    }

    @Test
    public void emptyLog_returnsEmptyList() {
        assertThat(CountDbOpr.queryWorkedContinents(db)).isEmpty();
    }
}
