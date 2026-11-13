package com.k1af.ft8af.log

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cloudlog in the catch-up sync: a Station ID or API key rejection refuses every row the
 * same way, so the batch stops after the first one instead of sending the whole backlog;
 * any other failure (one bad row, no signal) keeps going. Real (Robolectric) in-memory
 * SQLite; the server is a fake [ThirdPartyService.CloudlogUploader].
 */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyServiceCloudlogBatchTest {
    private lateinit var db: SQLiteDatabase
    private var savedCloudlog = false
    private var savedQrz = false
    private var savedWrl = false
    private lateinit var savedUploader: ThirdPartyService.CloudlogUploader
    private var calls = 0

    @Before
    fun setUp() {
        savedCloudlog = GeneralVariables.enableCloudlog
        savedQrz = GeneralVariables.enableQRZ
        savedWrl = GeneralVariables.enableWRL
        savedUploader = ThirdPartyService.cloudlogUploader
        GeneralVariables.enableCloudlog = true
        GeneralVariables.enableQRZ = false
        GeneralVariables.enableWRL = false
        db = SQLiteDatabase.create(null)
        db.execSQL(
            """
            CREATE TABLE QSLTable (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                synced_cloudlog INTEGER DEFAULT 0,
                synced_qrz INTEGER DEFAULT 0,
                synced_wrl INTEGER DEFAULT 0,
                call TEXT, gridsquare TEXT, mode TEXT, rst_sent TEXT, rst_rcvd TEXT,
                qso_date TEXT, time_on TEXT, qso_date_off TEXT, time_off TEXT, band TEXT,
                freq TEXT, station_callsign TEXT, my_gridsquare TEXT, comment TEXT
            )
            """.trimIndent(),
        )
        for (call in listOf("W1AW", "K2XX", "N3YY")) {
            val v = ContentValues()
            v.put("call", call)
            v.put("mode", "FT8")
            v.put("qso_date", "20260805")
            v.put("time_on", "143500")
            v.put("band", "20m")
            db.insert("QSLTable", null, v)
        }
    }

    @After
    fun tearDown() {
        db.close()
        GeneralVariables.enableCloudlog = savedCloudlog
        GeneralVariables.enableQRZ = savedQrz
        GeneralVariables.enableWRL = savedWrl
        ThirdPartyService.cloudlogUploader = savedUploader
    }

    private fun failWith(failure: String) {
        ThirdPartyService.cloudlogUploader =
            ThirdPartyService.CloudlogUploader { _, why ->
                calls++
                why?.append(failure)
                false
            }
    }

    @Test
    fun `a station id rejection stops the batch after the first row`() {
        val station =
            "HTTP 401: {\"status\":\"failed\",\"reason\":\"station id does not belong to the API key owner.\"}"
        failWith(station)

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(calls).isEqualTo(1)
        assertThat(result.cloudlogError).isEqualTo(station)
        assertThat(result.cloudlogOk).isEqualTo(0)
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(3)
    }

    @Test
    fun `an api key rejection stops the batch too`() {
        failWith("HTTP 401: {\"status\":\"failed\",\"reason\":\"missing or wrong api key\"}")

        ThirdPartyService.syncAllQSOs(db, null)

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `other failures keep trying every row`() {
        failWith("Unknown host: station.example.com")

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(calls).isEqualTo(3)
        assertThat(result.cloudlogError).isEqualTo("Unknown host: station.example.com")
    }

    @Test
    fun `accepted rows are counted and marked synced`() {
        ThirdPartyService.cloudlogUploader =
            ThirdPartyService.CloudlogUploader { _, _ ->
                calls++
                true
            }

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(calls).isEqualTo(3)
        assertThat(result.cloudlogOk).isEqualTo(3)
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(0)
    }
}
