package radio.ks3ckc.ft8af.ui.rateprompt

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Log snapshot queries against an in-memory copy of the QSLTable / dxcc_grid schema. */
@RunWith(RobolectricTestRunner::class)
class RatePromptLogStatsQueryTest {

    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE QSLTable (call TEXT, band TEXT, gridsquare TEXT)")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createDxccGrid() {
        db.execSQL("CREATE TABLE dxcc_grid (dxcc INTEGER, grid TEXT)")
        db.execSQL("INSERT INTO dxcc_grid VALUES (291, 'FN42'), (291, 'EM10'), (1, 'FN03'), (230, 'JO62')")
    }

    private fun qso(call: String, band: String, grid: String) {
        db.execSQL("INSERT INTO QSLTable VALUES (?, ?, ?)", arrayOf(call, band, grid))
    }

    @Test
    fun `empty log is all zeros`() {
        createDxccGrid()
        assertThat(queryRatePromptLogStats(db)).isEqualTo(RatePromptLogStats(0, 0, 0))
    }

    @Test
    fun `counts QSOs, case-insensitive distinct bands, and distinct entities`() {
        createDxccGrid()
        qso("K1ABC", "20m", "fn42")
        qso("W5XYZ", "20M", "EM10ab")
        qso("VE3AA", "40m", "FN03")
        qso("DL1AA", "40m", "JO62")
        qso("N0GRID", "", "")

        assertThat(queryRatePromptLogStats(db))
            .isEqualTo(RatePromptLogStats(qsoCount = 5, bandsWorked = 2, dxccEntities = 3))
    }

    @Test
    fun `missing dxcc_grid table degrades to zero entities`() {
        qso("K1ABC", "20m", "FN42")
        assertThat(queryRatePromptLogStats(db))
            .isEqualTo(RatePromptLogStats(qsoCount = 1, bandsWorked = 1, dxccEntities = 0))
    }
}
