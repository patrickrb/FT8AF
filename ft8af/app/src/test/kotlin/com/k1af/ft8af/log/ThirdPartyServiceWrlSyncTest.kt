package com.k1af.ft8af.log

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * World Radio League in the catch-up sync and the post-QSO path (issue #800): rows go up
 * one contact per request, get `synced_wrl` set only when WRL accepted them, and an
 * account-wide refusal stops the batch instead of repeating per row. Real (Robolectric)
 * in-memory SQLite; WRL itself is a fake [WrlApi.Transport].
 */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyServiceWrlSyncTest {

    private lateinit var db: SQLiteDatabase
    private var savedCloudlog = false
    private var savedQrz = false
    private var savedWrl = false
    private var savedKey: String? = null
    private var savedLogbook: String? = null
    private lateinit var savedTransport: WrlApi.Transport
    private lateinit var savedSleeper: WrlApi.Sleeper
    private lateinit var savedSaver: WrlApi.LogbookIdSaver
    private val bodies = mutableListOf<String?>()
    private val keys = mutableListOf<String>()
    private val requests = mutableListOf<String>()
    private val savedLogbookIds = mutableListOf<String>()

    @Before
    fun setUp() {
        savedCloudlog = GeneralVariables.enableCloudlog
        savedQrz = GeneralVariables.enableQRZ
        savedWrl = GeneralVariables.enableWRL
        savedKey = GeneralVariables.wrlApiKey
        savedLogbook = GeneralVariables.wrlLogbookId
        savedTransport = WrlApi.transport
        savedSleeper = WrlApi.sleeper
        savedSaver = WrlApi.logbookIdSaver
        GeneralVariables.enableCloudlog = false
        GeneralVariables.enableQRZ = false
        GeneralVariables.enableWRL = true
        GeneralVariables.wrlApiKey = "wrl_live_test"
        // A saved logbook, so most tests see only contact POSTs; the lookup tests clear it.
        GeneralVariables.wrlLogbookId = "lb-saved"
        WrlApi.sleeper = WrlApi.Sleeper { }
        WrlApi.logbookIdSaver = WrlApi.LogbookIdSaver { savedLogbookIds.add(it) }
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
                freq TEXT, station_callsign TEXT, my_gridsquare TEXT, comment TEXT,
                my_sig TEXT, my_sig_info TEXT, sig TEXT, sig_info TEXT
            )
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        GeneralVariables.enableCloudlog = savedCloudlog
        GeneralVariables.enableQRZ = savedQrz
        GeneralVariables.enableWRL = savedWrl
        GeneralVariables.wrlApiKey = savedKey
        GeneralVariables.wrlLogbookId = savedLogbook
        WrlApi.transport = savedTransport
        WrlApi.sleeper = savedSleeper
        WrlApi.logbookIdSaver = savedSaver
    }

    /** Fake WRL for flows that read the logbook list before posting contacts. */
    private fun route(logbooksJson: String) {
        WrlApi.transport = WrlApi.Transport { method, url, key, body ->
            keys.add(key)
            requests.add("$method ${url.removePrefix(WrlApi.BASE_URL)}")
            if (method == "GET") {
                WrlApi.Response(200, """{"data":$logbooksJson,"meta":null,"error":null}""", null)
            } else {
                bodies.add(body)
                WrlApi.Response(201, okBody, null)
            }
        }
    }

    private fun logbook(id: String) = """{"id":"$id","name":"$id","isLocked":false}"""

    @Test
    fun `with no logbook saved the account's only logbook is used and remembered`() {
        GeneralVariables.wrlLogbookId = ""
        insertQso("W1AW")
        insertQso("K2XX")
        route("[${logbook("lb-only")}]")

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(result.wrlOk).isEqualTo(2)
        assertThat(bodies.map { JSONObject(it!!).getString("logbookId") }).containsExactly("lb-only", "lb-only")
        // Looked up once, then remembered for the rest of the batch and for next time.
        assertThat(requests.count { it.startsWith("GET") }).isEqualTo(1)
        assertThat(savedLogbookIds).containsExactly("lb-only")
        assertThat(GeneralVariables.wrlLogbookId).isEqualTo("lb-only")
    }

    @Test
    fun `an account with no logbook is not posted to and says so`() {
        GeneralVariables.wrlLogbookId = ""
        insertQso("W1AW")
        insertQso("K2XX")
        route("[]")

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(bodies).isEmpty()
        assertThat(requests).containsExactly("GET logbooks?limit=100")
        assertThat(result.wrlError).contains("no logbook")
        assertThat(savedLogbookIds).isEmpty()
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(2)
    }

    @Test
    fun `with several logbooks and none saved the choice is left to wrl`() {
        GeneralVariables.wrlLogbookId = ""
        insertQso("W1AW")
        route("[${logbook("lb-a")},${logbook("lb-b")}]")

        ThirdPartyService.syncAllQSOs(db, null)

        assertThat(JSONObject(bodies.single()!!).has("logbookId")).isFalse()
        assertThat(savedLogbookIds).isEmpty()
        assertThat(GeneralVariables.wrlLogbookId).isEmpty()
    }

    @Test
    fun `a saved logbook skips the lookup`() {
        insertQso("W1AW")
        route("[${logbook("lb-other")}]")

        ThirdPartyService.syncAllQSOs(db, null)

        assertThat(requests).containsExactly("POST contacts")
        assertThat(JSONObject(bodies.single()!!).getString("logbookId")).isEqualTo("lb-saved")
    }

    private fun insertQso(
        call: String,
        band: String? = "20m",
        syncedWrl: Int = 0,
        mySigInfo: String? = null,
    ) {
        val v = ContentValues()
        v.put("call", call)
        v.put("mode", "FT8")
        v.put("rst_sent", "-10")
        v.put("rst_rcvd", "-12")
        v.put("qso_date", "20260805")
        v.put("time_on", "143500")
        v.put("band", band)
        v.put("freq", "14.074")
        v.put("station_callsign", "K1AF")
        v.put("my_gridsquare", "EN52")
        v.put("gridsquare", "FN31")
        v.put("synced_wrl", syncedWrl)
        if (mySigInfo != null) {
            v.put("my_sig", "POTA")
            v.put("my_sig_info", mySigInfo)
        }
        db.insert("QSLTable", null, v)
    }

    private val okBody = """{"data":{"id":"c1","enrichment":"pending"},"meta":null,"error":null}"""

    /** Fake WRL answering each POST with the next status in turn (the last one repeats). */
    private fun respond(vararg statuses: Int, bodyFor: (Int) -> String = { okBody }) {
        WrlApi.transport = WrlApi.Transport { _, _, key, body ->
            keys.add(key)
            bodies.add(body)
            val code = statuses[minOf(bodies.size - 1, statuses.size - 1)]
            WrlApi.Response(code, bodyFor(code), null)
        }
    }

    private fun syncedWrl(call: String): Int =
        db.rawQuery("select synced_wrl from QSLTable where call = ?", arrayOf(call)).use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test
    fun `pending rows are uploaded one per request and marked synced`() {
        insertQso("W1AW")
        insertQso("K2XX")
        insertQso("N0DONE", syncedWrl = 1)
        respond(201)
        val progress = mutableListOf<Int>()

        val result = ThirdPartyService.syncAllQSOs(db) { _, _, _, _, wrlOk -> progress.add(wrlOk) }

        assertThat(result.total).isEqualTo(2)
        assertThat(result.wrlOk).isEqualTo(2)
        assertThat(result.wrlAttempted).isTrue()
        assertThat(result.wrlError).isNull()
        assertThat(bodies).hasSize(2)
        assertThat(keys).containsExactly("wrl_live_test", "wrl_live_test")
        assertThat(syncedWrl("W1AW")).isEqualTo(1)
        assertThat(syncedWrl("K2XX")).isEqualTo(1)
        assertThat(progress.last()).isEqualTo(2)
        val first = JSONObject(bodies[0]!!)
        assertThat(first.getString("call")).isEqualTo("W1AW")
        assertThat(first.getDouble("freq")).isWithin(1e-9).of(14.074)
        assertThat(first.getString("stationCallsign")).isEqualTo("K1AF")
    }

    @Test
    fun `a rejected key stops the batch after one request`() {
        insertQso("W1AW")
        insertQso("K2XX")
        insertQso("N3YY")
        respond(401) {
            """{"data":null,"meta":null,"error":{"code":"INVALID_KEY","message":"The API key is not valid."}}"""
        }

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(bodies).hasSize(1)
        assertThat(result.wrlOk).isEqualTo(0)
        assertThat(result.wrlError).contains("INVALID_KEY")
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(3)
    }

    @Test
    fun `a contact-specific rejection does not stop the rest`() {
        insertQso("W1AW")
        insertQso("K2XX")
        respond(400, 201) { code ->
            if (code == 400) {
                """{"data":null,"meta":null,"error":{"code":"VALIDATION_ERROR","message":"Bad band.","field":"band"}}"""
            } else {
                okBody
            }
        }

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(bodies).hasSize(2)
        assertThat(result.wrlOk).isEqualTo(1)
        assertThat(result.wrlError).contains("VALIDATION_ERROR")
        assertThat(syncedWrl("W1AW")).isEqualTo(0)
        assertThat(syncedWrl("K2XX")).isEqualTo(1)
    }

    @Test
    fun `a row missing a required field is never sent but the rest still upload`() {
        insertQso("NOBAND", band = null)
        insertQso("W1AW")
        respond(201)

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(bodies).hasSize(1)
        assertThat(result.wrlOk).isEqualTo(1)
        assertThat(result.wrlError).contains("missing band")
        assertThat(syncedWrl("NOBAND")).isEqualTo(0)
    }

    @Test
    fun `without an api key wrl is never called`() {
        GeneralVariables.wrlApiKey = ""
        insertQso("W1AW")
        respond(201)

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(bodies).isEmpty()
        assertThat(result.wrlError).isEqualTo("no API key configured")
    }

    @Test
    fun `an activation park goes out as myActivities`() {
        insertQso("W1AW", mySigInfo = "US-0662")
        respond(201)

        ThirdPartyService.syncAllQSOs(db, null)

        val activity = JSONObject(bodies[0]!!).getJSONArray("myActivities").getJSONObject(0)
        assertThat(activity.getString("type")).isEqualTo("POTA")
        assertThat(activity.getString("ref")).isEqualTo("US-0662")
    }

    @Test
    fun `markQsoSynced flags only the wrl column`() {
        insertQso("W1AW")
        val record = QSLRecord(
            hashMapOf(
                "CALL" to "W1AW",
                "QSO_DATE" to "20260805",
                "TIME_ON" to "143500",
                "MODE" to "FT8",
                "COMMENT" to "",
            ),
        )

        ThirdPartyService.markQsoSynced(db, record, false, false, true)

        assertThat(syncedWrl("W1AW")).isEqualTo(1)
        db.rawQuery("select synced_cloudlog, synced_qrz from QSLTable", null).use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(0)
            assertThat(it.getInt(1)).isEqualTo(0)
        }
    }

    @Test
    fun `post-QSO upload converts the Hz dial frequency and posts the record`() {
        val record = QSLRecord(
            hashMapOf(
                "CALL" to "W1AW",
                "QSO_DATE" to "20260805",
                "TIME_ON" to "1435",
                "MODE" to "FT8",
                "FREQ" to "14.074",
                "BAND" to "20m",
                "RST_SENT" to "-7",
                "RST_RCVD" to "3",
                "STATION_CALLSIGN" to "K1AF",
                "COMMENT" to "hi",
                "MY_SIG" to "POTA",
                "MY_SIG_INFO" to "US-0662",
            ),
        )
        respond(201)

        assertThat(ThirdPartyService.UploadToWrl(record)).isTrue()

        val sent = JSONObject(bodies.single()!!)
        assertThat(sent.getDouble("freq")).isWithin(1e-9).of(14.074)
        assertThat(sent.getString("band")).isEqualTo("20m")
        assertThat(sent.getJSONObject("timestamp").getString("timeOn")).isEqualTo("1435")
        assertThat(sent.getString("rstSent")).isEqualTo(AdifFormat.formatReport("FT8", -7))
        assertThat(sent.getString("notes")).isEqualTo("hi")
        assertThat(sent.getJSONArray("myActivities").getJSONObject(0).getString("ref")).isEqualTo("US-0662")
    }

    @Test
    fun `post-QSO upload reports failure when wrl refuses`() {
        val record = QSLRecord(
            hashMapOf(
                "CALL" to "W1AW",
                "QSO_DATE" to "20260805",
                "TIME_ON" to "1435",
                "MODE" to "FT8",
                "FREQ" to "14.074",
                "BAND" to "20m",
                "COMMENT" to "",
            ),
        )
        respond(500)

        assertThat(ThirdPartyService.UploadToWrl(record)).isFalse()
    }

    @Test
    fun `anyUploadServiceEnabled counts world radio league`() {
        GeneralVariables.enableWRL = true
        assertThat(ThirdPartyService.anyUploadServiceEnabled()).isTrue()
        GeneralVariables.enableWRL = false
        assertThat(ThirdPartyService.anyUploadServiceEnabled()).isFalse()
    }
}
