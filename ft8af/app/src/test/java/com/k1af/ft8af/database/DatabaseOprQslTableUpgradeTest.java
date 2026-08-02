package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;


import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * The QSLTable upgrade path — that an <em>existing</em> database gains the columns
 * {@link DatabaseOpr#doInsertQSLData} writes.
 *
 * <p>This exists because of a shipped crash. {@code my_lat}/{@code my_lon} were added to
 * the {@code CREATE TABLE} and to the {@code alterTable} block, but the schema version was
 * left at 19 — and {@code onUpgrade} (the only thing that runs those ALTERs on an existing
 * database) fires solely when that number increases. The result was invisible in
 * development and fatal in the field: a fresh install got the columns from CREATE TABLE
 * and behaved perfectly, while every upgraded install kept the old table and died with
 * "table QSLTable has no column named my_lat" on the first logged QSO.
 *
 * <p>So the meaningful test is not "does a new database have the column" — that passed
 * throughout the bug. It is "does a database created by the <em>previous</em> version have
 * it after the helper opens it", which is what {@link #openingAnOlderDatabase_addsThePositionColumns}
 * checks by building a v19-era table by hand and then letting DatabaseOpr upgrade it.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprQslTableUpgradeTest {

    /** The QSLTable exactly as it stood before the position columns were added. */
    private static final String LEGACY_QSL_TABLE =
            "CREATE TABLE QSLTable (\n"
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + "isQSL INTEGER DEFAULT 0,\n"
                    + "isLotW_import INTEGER DEFAULT 0,\n"
                    + "isLotW_QSL INTEGER DEFAULT 0,\n"
                    + "synced_cloudlog INTEGER DEFAULT 0,\n"
                    + "synced_qrz INTEGER DEFAULT 0,\n"
                    + "call TEXT,\ngridsquare TEXT,\nmode TEXT,\nrst_sent TEXT,\n"
                    + "rst_rcvd TEXT,\nqso_date TEXT,\ntime_on TEXT,\nqso_date_off TEXT,\n"
                    + "time_off TEXT,\nband TEXT,\nfreq TEXT,\nstation_callsign TEXT,\n"
                    + "my_gridsquare TEXT,\ncomment TEXT,\nmy_sig TEXT,\nmy_sig_info TEXT,\n"
                    + "sig TEXT,\nsig_info TEXT)";

    private static Set<String> columnsOf(SQLiteDatabase db, String table) {
        Set<String> names = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                names.add(c.getString(nameIdx));
            }
        }
        return names;
    }

    @Test
    public void openingAnOlderDatabase_addsThePositionColumns() {
        Context context = ApplicationProvider.getApplicationContext();
        File dbFile = context.getDatabasePath("upgrade_probe.db");
        //noinspection ResultOfMethodCallIgnored
        dbFile.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        dbFile.delete();

        // Stand up a database as the previous release left it: the old table, stamped
        // with the old schema version so the helper sees an upgrade rather than a create.
        SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        legacy.execSQL(LEGACY_QSL_TABLE);
        legacy.setVersion(19);
        assertThat(columnsOf(legacy, "QSLTable")).doesNotContain("my_lat");
        legacy.close();

        DatabaseOpr opr = new DatabaseOpr(context, dbFile.getName(), null, DatabaseOpr.SCHEMA_VERSION);
        try {
            Set<String> columns = columnsOf(opr.getWritableDatabase(), "QSLTable");
            assertThat(columns).contains("my_lat");
            assertThat(columns).contains("my_lon");
            // The upgrade must not have dropped and recreated the table.
            assertThat(columns).contains("my_sig_info");
        } finally {
            opr.close();
            //noinspection ResultOfMethodCallIgnored
            dbFile.delete();
        }
    }

    @Test
    public void schemaVersionIsAheadOfTheReleaseThatShippedWithoutTheColumns() {
        // 19 is the version that shipped my_lat in CREATE TABLE but never ran the ALTER.
        // Anything at or below it leaves upgraded installs crashing on every QSO.
        assertThat(DatabaseOpr.SCHEMA_VERSION).isGreaterThan(19);
    }

    @Test
    public void upgradedTableCarriesEveryColumnTheInsertNames() {
        // Guards the next occurrence rather than just this one: doInsertQSLData writes
        // these column names, so an upgraded database missing any of them crashes the
        // app on the first logged QSO exactly as my_lat did. Adding a column to that
        // INSERT without a migration should fail here, not in a car.
        Context context = ApplicationProvider.getApplicationContext();
        File dbFile = context.getDatabasePath("upgrade_columns_probe.db");
        //noinspection ResultOfMethodCallIgnored
        dbFile.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        dbFile.delete();

        SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        legacy.execSQL(LEGACY_QSL_TABLE);
        legacy.setVersion(19);
        legacy.close();

        DatabaseOpr opr = new DatabaseOpr(context, dbFile.getName(), null, DatabaseOpr.SCHEMA_VERSION);
        try {
            Set<String> columns = columnsOf(opr.getWritableDatabase(), "QSLTable");
            for (String required : new String[]{
                    "call", "isQSL", "isLotW_import", "isLotW_QSL", "gridsquare", "mode",
                    "rst_sent", "rst_rcvd", "qso_date", "time_on", "qso_date_off", "time_off",
                    "band", "freq", "station_callsign", "my_gridsquare", "comment",
                    "my_sig", "my_sig_info", "sig", "sig_info", "my_lat", "my_lon"}) {
                assertThat(columns).contains(required);
            }
        } finally {
            opr.close();
            //noinspection ResultOfMethodCallIgnored
            dbFile.delete();
        }
    }
}
