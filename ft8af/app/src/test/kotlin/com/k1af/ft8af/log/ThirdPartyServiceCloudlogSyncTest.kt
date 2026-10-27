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
 * Catch-up sync against Cloudlog/Wavelog (issue #936): QSOs the logbook already has are
 * marked synced instead of uploaded again, and a duplicate rejection is not retried
 * forever. Real (Robolectric) in-memory SQLite; the server is a fake
 * [ThirdPartyService.CloudlogApi].
 */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyServiceCloudlogSyncTest {

    private lateinit var db: SQLiteDatabase
    private var savedCloudlog = false
    private var savedQrz = false
    private var savedWrl = false
    private var savedAddress: String? = null
    private var savedKey: String? = null
    private var savedStation: String? = null
    private lateinit var savedApi: ThirdPartyService.CloudlogApi

    /** Fake server state. */
    private var stationInfo: String? = """[{"station_id":"1"},{"station_id":"2"}]"""
    private val remotePages = mutableMapOf<String, MutableList<String?>>()
    private var uploadReply: (String) -> String? = { """{"status":"created"}""" }
    private var uploadFailure = ""
    private val gets = mutableListOf<String>()
    private val pageRequests = mutableListOf<JSONObject>()
    private val uploads = mutableListOf<String>()

    @Before
    fun setUp() {
        savedCloudlog = GeneralVariables.enableCloudlog
        savedQrz = GeneralVariables.enableQRZ
        savedWrl = GeneralVariables.enableWRL
        savedAddress = GeneralVariables.cloudlogServerAddress
        savedKey = GeneralVariables.cloudlogApiKey
        savedStation = GeneralVariables.cloudlogStationID
        savedApi = ThirdPartyService.cloudlogApi
        GeneralVariables.enableCloudlog = true
        GeneralVariables.enableQRZ = false
        GeneralVariables.enableWRL = false
        GeneralVariables.cloudlogServerAddress = "https://log.example.org/"
        GeneralVariables.cloudlogApiKey = "KEY"
        GeneralVariables.cloudlogStationID = "1"
        ThirdPartyService.cloudlogApi = object : ThirdPartyService.CloudlogApi {
            override fun get(base: String, path: String): String? {
                gets.add(path)
                return stationInfo
            }

            override fun post(base: String, path: String, json: String, failureOut: StringBuilder?): String? {
                val req = JSONObject(json)
                return when (path) {
                    "api/get_contacts_adif" -> {
                        pageRequests.add(req)
                        remotePages[req.getString("station_id")]?.removeFirstOrNull()
                    }
                    "api/qso" -> {
                        uploads.add(req.getString("string"))
                        uploadReply(req.getString("string")).also {
                            if (it == null) failureOut?.append(uploadFailure)
                        }
                    }
                    else -> throw AssertionError("unexpected POST $path")
                }
            }
        }
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
        GeneralVariables.cloudlogServerAddress = savedAddress
        GeneralVariables.cloudlogApiKey = savedKey
        GeneralVariables.cloudlogStationID = savedStation
        ThirdPartyService.cloudlogApi = savedApi
    }

    private fun page(lastId: Long, vararg calls: String): String {
        val qsos = calls.joinToString(",") {
            """{"CALL":"$it","QSO_DATE":"20260805","TIME_ON":"143500","BAND":"20m","MODE":"FT8","STATION_CALLSIGN":"K1AF"}"""
        }
        return """{"status":"successful","lastfetchedid":$lastId,"qsos":[$qsos]}"""
    }

    private val emptyPage = """{"status":"successful","lastfetchedid":0,"exported_qsos":0,"adif":null}"""

    private fun insertQso(
        call: String,
        syncedCloudlog: Int = 0,
        timeOn: String = "143500",
        stationCallsign: String = "K1AF",
    ) {
        val v = ContentValues()
        v.put("call", call)
        v.put("mode", "FT8")
        v.put("qso_date", "20260805")
        v.put("time_on", timeOn)
        v.put("band", "20m")
        v.put("freq", "14.074")
        v.put("station_callsign", stationCallsign)
        v.put("synced_cloudlog", syncedCloudlog)
        db.insert("QSLTable", null, v)
    }

    private fun syncedCalls(): List<String> {
        val c = db.rawQuery("select call from QSLTable where synced_cloudlog = 1 order by id", null)
        val out = mutableListOf<String>()
        while (c.moveToNext()) out.add(c.getString(0))
        c.close()
        return out
    }

    @Test
    fun `qsos already in wavelog are marked synced without being uploaded`() {
        insertQso("W1AW")
        insertQso("K2XX")
        insertQso("N3ZZ")
        // W1AW lives under station 1, K2XX under station 2; N3ZZ is new.
        remotePages["1"] = mutableListOf(page(10, "W1AW"), emptyPage)
        remotePages["2"] = mutableListOf(page(20, "K2XX"), emptyPage)

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(uploads).hasSize(1)
        assertThat(uploads[0]).contains("<call:4>N3ZZ")
        assertThat(result.cloudlogOk).isEqualTo(1)
        assertThat(result.cloudlogAlreadyPresent).isEqualTo(2)
        assertThat(result.cloudlogError).isNull()
        assertThat(syncedCalls()).containsExactly("W1AW", "K2XX", "N3ZZ")
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(0)
    }

    @Test
    fun `a station worked twice is uploaded once more when wavelog has only one`() {
        insertQso("W1AW", timeOn = "143500")
        insertQso("W1AW", timeOn = "144500")
        remotePages["1"] = mutableListOf(page(10, "W1AW"), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(result.cloudlogAlreadyPresent).isEqualTo(1)
        assertThat(uploads).hasSize(1)
        assertThat(uploads[0]).contains("<time_on:6>144500")
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(0)
    }

    @Test
    fun `a row without a station callsign only matches the upload station's callsign`() {
        stationInfo = """[{"station_id":"1","station_callsign":"K1AF"},""" +
            """{"station_id":"2","station_callsign":"W1XYZ"}]"""
        insertQso("W1AW", stationCallsign = "")
        // Wavelog has W1AW, but logged by the club call under station 2.
        remotePages["1"] = mutableListOf(emptyPage)
        remotePages["2"] = mutableListOf(
            """{"status":"successful","lastfetchedid":20,"qsos":[{"CALL":"W1AW","QSO_DATE":"20260805",""" +
                """"TIME_ON":"143500","BAND":"20m","MODE":"FT8","STATION_CALLSIGN":"W1XYZ"}]}""",
            emptyPage,
        )

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(result.cloudlogAlreadyPresent).isEqualTo(0)
        assertThat(uploads).hasSize(1)
    }

    @Test
    fun `paging stops when the server does not advance the cursor`() {
        insertQso("N3ZZ")
        remotePages["1"] = mutableListOf(page(10, "W1AW"), page(10, "W1AW"), page(11, "K2XX"))
        remotePages["2"] = mutableListOf(emptyPage)

        ThirdPartyService.syncAllQSOs(db, null)

        assertThat(pageRequests.map { it.getString("station_id") + "@" + it.getLong("fetchfromid") })
            .containsExactly("1@0", "1@10", "2@0").inOrder()
    }

    @Test
    fun `paging is capped per station`() {
        insertQso("N3ZZ")
        remotePages["1"] = (1..ThirdPartyService.REMOTE_MAX_PAGES + 100).map { page(it.toLong(), "W1AW") }.toMutableList()
        remotePages["2"] = mutableListOf(emptyPage)

        ThirdPartyService.syncAllQSOs(db, null)

        assertThat(pageRequests.count { it.getString("station_id") == "1" }).isEqualTo(ThirdPartyService.REMOTE_MAX_PAGES)
        assertThat(uploads).hasSize(1)
    }

    @Test
    fun `reading stops once the logbook passes the overall cap`() {
        val saved = ThirdPartyService.remoteMaxQsos
        ThirdPartyService.remoteMaxQsos = 3
        try {
            insertQso("N3ZZ")
            remotePages["1"] = (1..10).map { page(it.toLong(), "W1AW") }.toMutableList()
            remotePages["2"] = (11..20).map { page(it.toLong(), "K2XX") }.toMutableList()

            ThirdPartyService.syncAllQSOs(db, null)

            // Three one-QSO pages reach the cap; station 2 is never asked.
            assertThat(pageRequests).hasSize(3)
            assertThat(uploads).hasSize(1)
        } finally {
            ThirdPartyService.remoteMaxQsos = saved
        }
    }

    @Test
    fun `remote log is paged with the returned cursor for every station`() {
        insertQso("W1AW")
        remotePages["1"] = mutableListOf(page(10, "K2XX"), page(25, "W1AW"), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        ThirdPartyService.syncAllQSOs(db, null)

        assertThat(pageRequests.map { it.getString("station_id") + "@" + it.getLong("fetchfromid") })
            .containsExactly("1@0", "1@10", "1@25", "2@0").inOrder()
        assertThat(uploads).isEmpty()
    }

    @Test
    fun `remote log is downloaded once per sync, not per row`() {
        repeat(5) { insertQso("W${it}AW") }
        remotePages["1"] = mutableListOf(emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        ThirdPartyService.syncAllQSOs(db, null)

        assertThat(gets).containsExactly("api/station_info/KEY")
        assertThat(pageRequests).hasSize(2)
        assertThat(uploads).hasSize(5)
    }

    @Test
    fun `nothing pending for cloudlog means no download`() {
        insertQso("W1AW", syncedCloudlog = 1)

        ThirdPartyService.syncAllQSOs(db, null)

        assertThat(gets).isEmpty()
        assertThat(pageRequests).isEmpty()
    }

    @Test
    fun `server without get_contacts_adif falls back to uploading`() {
        // Plain Cloudlog: the listing endpoint 404s (fake returns null for every page).
        insertQso("W1AW")
        insertQso("K2XX")

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(uploads).hasSize(2)
        assertThat(result.cloudlogOk).isEqualTo(2)
        assertThat(result.cloudlogAlreadyPresent).isEqualTo(0)
    }

    @Test
    fun `without a station list the configured station is used`() {
        stationInfo = null
        insertQso("W1AW")
        remotePages["1"] = mutableListOf(page(5, "W1AW"), emptyPage)

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(pageRequests.map { it.getString("station_id") }.distinct()).containsExactly("1")
        assertThat(result.cloudlogAlreadyPresent).isEqualTo(1)
        assertThat(uploads).isEmpty()
    }

    @Test
    fun `a duplicate rejection marks the row synced and is not reported as an error`() {
        insertQso("W1AW")
        insertQso("K2XX")
        uploadReply = { adif -> if (adif.contains("W1AW")) null else """{"status":"created"}""" }
        uploadFailure = "HTTP 400: {\"status\":\"abort\",\"adif_count\":1,\"adif_errors\":1," +
            "\"messages\":[\"\",\"Date\\/Time: 2026-08-05 14:35:00 Callsign: W1AW Band: 20m Duplicate for K1AF<br>\"]}"

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(result.cloudlogOk).isEqualTo(1)
        assertThat(result.cloudlogAlreadyPresent).isEqualTo(1)
        assertThat(result.cloudlogError).isNull()
        assertThat(syncedCalls()).containsExactly("W1AW", "K2XX")
    }

    @Test
    fun `other upload failures still leave the row pending and report why`() {
        insertQso("W1AW")
        uploadReply = { null }
        uploadFailure = "HTTP 401: {\"status\":\"failed\",\"reason\":\"missing or wrong api key\"}"

        val result = ThirdPartyService.syncAllQSOs(db, null)

        assertThat(result.cloudlogOk).isEqualTo(0)
        assertThat(result.cloudlogAlreadyPresent).isEqualTo(0)
        assertThat(result.cloudlogError).contains("HTTP 401")
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(1)
    }

    @Test
    fun `isDuplicateRejection only accepts a 400 that says duplicate`() {
        assertThat(ThirdPartyService.isDuplicateRejection("HTTP 400: ... Band: 20m Duplicate for K1AF")).isTrue()
        assertThat(ThirdPartyService.isDuplicateRejection("HTTP 400: wrong station id")).isFalse()
        assertThat(ThirdPartyService.isDuplicateRejection("HTTP 500: Duplicate for K1AF")).isFalse()
        assertThat(ThirdPartyService.isDuplicateRejection(null)).isFalse()
    }

    @Test
    fun `station profiles parse leniently and give the configured station's callsign`() {
        val profiles = ThirdPartyService.parseStationProfiles(
            """[{"station_id":"1","station_profile_name":"Home","station_callsign":"K1AF"},""" +
                """"junk",{"station_id":"2","station_callsign":"W1XYZ"}]""",
        )

        assertThat(profiles.map { it.stationId }).containsExactly("1", "2").inOrder()
        assertThat(profiles[0].profileName).isEqualTo("Home")
        assertThat(ThirdPartyService.stationCallsignOf(profiles, " 2 ")).isEqualTo("W1XYZ")
        assertThat(ThirdPartyService.stationCallsignOf(profiles, "9")).isEmpty()
        assertThat(ThirdPartyService.stationCallsignOf(profiles, null)).isEmpty()
        assertThat(ThirdPartyService.parseStationProfiles("not json")).isEmpty()
        assertThat(ThirdPartyService.parseStationProfiles(null)).isEmpty()
    }

    @Test
    fun `parseStationIds reads ids, skips blanks and repeats, survives junk`() {
        assertThat(
            ThirdPartyService.parseStationIds(
                """[{"station_id":"1"},{"station_id":2},{"station_id":""},{"station_id":"1"}]""",
            ),
        ).containsExactly("1", "2").inOrder()
        assertThat(ThirdPartyService.parseStationIds("not json")).isEmpty()
        assertThat(ThirdPartyService.parseStationIds(null)).isEmpty()
    }
}
