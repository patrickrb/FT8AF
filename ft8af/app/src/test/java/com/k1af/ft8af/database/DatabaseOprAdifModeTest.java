package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.database.MatrixCursor;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Verifies the mode/submode ADIF mapping in {@link DatabaseOpr#downQSLTable}, the logbook's
 * ADIF text export. FT2/FT4 are ADIF submodes of MFSK, so a bare {@code <mode>FT2}/{@code
 * <mode>FT4} is rejected as invalid by pota.app and other ADIF consumers; they must be exported
 * as {@code MODE=MFSK} + {@code SUBMODE}, while FT8 stays a standalone mode.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprAdifModeTest {

    /** Columns downQSLTable reads unconditionally; comment must be non-null (it is dereferenced). */
    private static final String[] COLUMNS = {
            "call", "isLotW_QSL", "isQSL", "gridsquare", "mode", "rst_sent", "rst_rcvd",
            "qso_date", "time_on", "qso_date_off", "time_off", "band", "freq",
            "station_callsign", "my_gridsquare", "comment"
    };

    private DatabaseOpr opr;

    @Before
    public void setUp() {
        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 19);
    }

    @After
    public void tearDown() {
        opr.close();
    }

    private String exportWithMode(String mode) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        cursor.addRow(new Object[]{
                "W1AW", 0, 0, null, mode, null, null,
                null, null, null, null, null, null,
                null, null, "QSO by FT8AF"
        });
        return opr.downQSLTable(cursor, false);
    }

    @Test
    public void ft2ExportsAsMfskSubmode() {
        String adif = exportWithMode("FT2");
        assertThat(adif).contains("<mode:4>MFSK <submode:3>FT2 ");
        assertThat(adif).doesNotContain("<mode:3>FT2 ");
    }

    @Test
    public void ft4ExportsAsMfskSubmode() {
        String adif = exportWithMode("FT4");
        assertThat(adif).contains("<mode:4>MFSK <submode:3>FT4 ");
        assertThat(adif).doesNotContain("<mode:3>FT4 ");
    }

    @Test
    public void ft8StaysStandaloneMode() {
        String adif = exportWithMode("FT8");
        assertThat(adif).contains("<mode:3>FT8 ");
        assertThat(adif).doesNotContain("submode");
    }
}
