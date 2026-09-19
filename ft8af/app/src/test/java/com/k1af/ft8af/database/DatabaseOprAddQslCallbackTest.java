package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.database.Cursor;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.log.QSLRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link DatabaseOpr#addQSL_Callsign(QSLRecord, Runnable)} runs its callback only after the
 * QSO row is in QSLTable. MainViewModel starts the Cloudlog/QRZ/World Radio League uploads
 * from that callback, because markQsoSynced UPDATEs the row: run any earlier and the upload
 * is never marked synced and gets sent again (Copilot review on PR #814).
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprAddQslCallbackTest {

    private DatabaseOpr opr;

    @Before
    public void setUp() {
        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 18);
    }

    @After
    public void tearDown() {
        opr.close();
    }

    @Test
    public void afterInsertRunsOnceTheRowIsInQslTable() throws Exception {
        QSLRecord record = new QSLRecord(
                0L, 15_000L, "K1ABC", "", "W1AW", "",
                -5, -10, "FT8", 14_074_000L, 1_500);
        final boolean[] called = {false};
        final int[] rowsSeenByCallback = {-1};

        opr.addQSL_Callsign(record, () -> {
            called[0] = true;
            try (Cursor c = opr.getDb().rawQuery(
                    "SELECT COUNT(*) FROM QSLTable WHERE [call] = ?", new String[]{"W1AW"})) {
                c.moveToFirst();
                rowsSeenByCallback[0] = c.getInt(0);
            }
        });

        // The insert runs on AsyncTask's background executor; its onPostExecute is posted
        // to the main looper, which Robolectric only runs when idled.
        long deadline = System.currentTimeMillis() + 5_000;
        while (!called[0] && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }

        assertThat(called[0]).isTrue();
        assertThat(rowsSeenByCallback[0]).isEqualTo(1);
    }
}
