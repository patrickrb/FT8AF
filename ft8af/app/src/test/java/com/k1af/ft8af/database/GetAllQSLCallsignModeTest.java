package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.database.sqlite.SQLiteDatabase;

import com.k1af.ft8af.FT8Common;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.timer.UtcTimer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * End-to-end coverage for the "same mode only" refinement wired into
 * {@link DatabaseOpr.GetAllQSLCallsign}: with an in-memory QSLTable we verify
 * that {@link GeneralVariables#workedSameMode} restricts the WORKED (current
 * band), NEW_BAND (other band) and TODAY worked lists to the current operating
 * mode. Robolectric because it drives real SQLite.
 */
@RunWith(RobolectricTestRunner.class)
public class GetAllQSLCallsignModeTest {

    private SQLiteDatabase db;
    private long savedBand;
    private int savedMode;

    @Before
    public void setUp() {
        savedBand = GeneralVariables.band;
        savedMode = GeneralVariables.operatingMode;
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE QSLTable (id INTEGER PRIMARY KEY, [call] TEXT, band TEXT, "
                + "mode TEXT, qso_date TEXT, gridsquare TEXT, sig TEXT, sig_info TEXT)");

        // Operating on 20m / FT8.
        GeneralVariables.band = 14074000L;
        GeneralVariables.operatingMode = FT8Common.FT8_MODE;
        GeneralVariables.workedSameMode = false;

        String today = UtcTimer.getYYYYMMDD(UtcTimer.getSystemTime());

        insert("ONBAND_FT8", "20m", "FT8", "20200101");
        insert("ONBAND_FT4", "20m", "FT4", "20200101");
        insert("OTHER_FT8", "40m", "FT8", "20200101");
        insert("OTHER_FT4", "40m", "FT4", "20200101");
        insert("TODAY_FT8", "15m", "FT8", today);
        insert("TODAY_FT4", "15m", "FT4", today);
    }

    @After
    public void tearDown() {
        if (db != null) {
            db.close();
        }
        // GetAllQSLCallsign.get() populates process-global lists, and band/mode/toggle are
        // global too. Restore every one of them so this class is self-contained and can't
        // leak worked stations (or an FT8-only filter) into whatever test runs next.
        GeneralVariables.workedSameMode = false;
        GeneralVariables.QSL_Callsign_list.clear();
        GeneralVariables.QSL_Callsign_list_other_band.clear();
        GeneralVariables.QSL_Callsign_list_today.clear();
        GeneralVariables.band = savedBand;
        GeneralVariables.operatingMode = savedMode;
    }

    private void insert(String call, String band, String mode, String date) {
        db.execSQL("INSERT INTO QSLTable ([call], band, mode, qso_date, gridsquare) VALUES "
                + "(?, ?, ?, ?, ?)", new Object[]{call, band, mode, date, "EM48"});
    }

    @Test
    public void sameModeOffLoadsAllModes() {
        DatabaseOpr.GetAllQSLCallsign.get(db);

        assertThat(GeneralVariables.QSL_Callsign_list)
                .containsExactly("ONBAND_FT8", "ONBAND_FT4");
        assertThat(GeneralVariables.QSL_Callsign_list_other_band)
                .containsExactly("OTHER_FT8", "OTHER_FT4", "TODAY_FT8", "TODAY_FT4");
        assertThat(GeneralVariables.QSL_Callsign_list_today)
                .containsExactly("TODAY_FT8", "TODAY_FT4");
    }

    @Test
    public void loadPublishesCopyOnWriteList() {
        DatabaseOpr.GetAllQSLCallsign.get(db);

        // The worked list is read from decode/UI threads and the NanoHTTPD web-logbook worker
        // while this background reload swaps it wholesale; publishing a CopyOnWriteArrayList (not
        // a plain ArrayList) is what keeps those readers on a stable snapshot. See the field note
        // in GeneralVariables and LogHttpServer.successfulCallsignBlock.
        assertThat(GeneralVariables.QSL_Callsign_list)
                .isInstanceOf(java.util.concurrent.CopyOnWriteArrayList.class);
    }

    @Test
    public void sameModeOnDropsOtherModeAcrossAllLists() {
        GeneralVariables.workedSameMode = true;

        DatabaseOpr.GetAllQSLCallsign.get(db);

        // Only FT8 contacts survive while operating FT8.
        assertThat(GeneralVariables.QSL_Callsign_list).containsExactly("ONBAND_FT8");
        assertThat(GeneralVariables.QSL_Callsign_list_other_band)
                .containsExactly("OTHER_FT8", "TODAY_FT8");
        assertThat(GeneralVariables.QSL_Callsign_list_today).containsExactly("TODAY_FT8");
    }
}
