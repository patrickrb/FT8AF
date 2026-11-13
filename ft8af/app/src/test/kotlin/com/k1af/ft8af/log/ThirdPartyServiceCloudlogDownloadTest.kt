package com.k1af.ft8af.log

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.database.DatabaseOpr
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.pota.model.PotaActivation

/**
 * Two-way Wavelog sync (issue #965): with "Download QSOs from Wavelog when syncing" on, the
 * QSOs the logbook has and the local log doesn't are inserted, through the ADIF import's
 * [DatabaseOpr.doInsertQSLData], before the pending rows are uploaded. Real (Robolectric)
 * in-memory database with the app's schema; the server is a fake
 * [ThirdPartyService.CloudlogApi], as in [ThirdPartyServiceCloudlogSyncTest].
 */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyServiceCloudlogDownloadTest {

    private lateinit var opr: DatabaseOpr
    private var savedCloudlog = false
    private var savedQrz = false
    private var savedWrl = false
    private var savedDownload = false
    private var savedAddress: String? = null
    private var savedKey: String? = null
    private var savedStation: String? = null
    private lateinit var savedApi: ThirdPartyService.CloudlogApi

    /** Fake server state. */
    private var stationInfo: String? = """[{"station_id":"1","station_callsign":"K1AF"},""" +
        """{"station_id":"2","station_callsign":"K1AF"}]"""
    private val remotePages = mutableMapOf<String, MutableList<String?>>()
    /** Served again on every sync, like a real server; [remotePages] is used up once. */
    private var remoteLog: Map<String, List<String>>? = null
    private var listingFailure = ""
    private val pageRequests = mutableListOf<JSONObject>()
    private val uploads = mutableListOf<String>()
    /** When set, every api/qso upload fails with this reason. */
    private var uploadFailure: String? = null

    @Before
    fun setUp() {
        savedCloudlog = GeneralVariables.enableCloudlog
        savedQrz = GeneralVariables.enableQRZ
        savedWrl = GeneralVariables.enableWRL
        savedDownload = GeneralVariables.cloudlogDownloadQsos
        savedAddress = GeneralVariables.cloudlogServerAddress
        savedKey = GeneralVariables.cloudlogApiKey
        savedStation = GeneralVariables.cloudlogStationID
        savedApi = ThirdPartyService.cloudlogApi
        GeneralVariables.enableCloudlog = true
        GeneralVariables.enableQRZ = false
        GeneralVariables.enableWRL = false
        GeneralVariables.cloudlogDownloadQsos = true
        GeneralVariables.cloudlogServerAddress = "https://log.example.org/"
        GeneralVariables.cloudlogApiKey = "KEY"
        GeneralVariables.cloudlogStationID = "1"
        ThirdPartyService.cloudlogApi = object : ThirdPartyService.CloudlogApi {
            override fun get(base: String, path: String): String? = stationInfo

            override fun post(base: String, path: String, json: String, failureOut: StringBuilder?): String? {
                val req = JSONObject(json)
                return when (path) {
                    "api/get_contacts_adif" -> {
                        pageRequests.add(req)
                        val station = req.getString("station_id")
                        val body = remoteLog?.let { log ->
                            // Pages are numbered by their lastfetchedid: serve the first one past the cursor.
                            log[station]?.firstOrNull {
                                JSONObject(it).optLong("lastfetchedid") > req.getLong("fetchfromid")
                            } ?: emptyPage
                        } ?: remotePages[station]?.removeFirstOrNull()
                        if (body == null) failureOut?.append(listingFailure)
                        body
                    }
                    "api/qso" -> {
                        val failure = uploadFailure
                        if (failure != null) {
                            failureOut?.append(failure)
                            null
                        } else {
                            uploads.add(req.getString("string"))
                            """{"status":"created"}"""
                        }
                    }
                    else -> throw AssertionError("unexpected POST $path")
                }
            }
        }
        opr = DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 21)
    }

    @After
    fun tearDown() {
        opr.close()
        GeneralVariables.enableCloudlog = savedCloudlog
        GeneralVariables.enableQRZ = savedQrz
        GeneralVariables.enableWRL = savedWrl
        GeneralVariables.cloudlogDownloadQsos = savedDownload
        GeneralVariables.cloudlogServerAddress = savedAddress
        GeneralVariables.cloudlogApiKey = savedKey
        GeneralVariables.cloudlogStationID = savedStation
        ThirdPartyService.cloudlogApi = savedApi
    }

    private val emptyPage = """{"status":"successful","lastfetchedid":0,"exported_qsos":0,"adif":null}"""

    private fun qso(
        call: String,
        timeOn: String = "143500",
        band: String = "20m",
        mode: String = "FT8",
        extra: String = "",
    ) = """{"CALL":"$call","QSO_DATE":"20260805","TIME_ON":"$timeOn","BAND":"$band",""" +
        """"MODE":"$mode","STATION_CALLSIGN":"K1AF"$extra}"""

    private fun page(lastId: Long, vararg qsos: String) =
        """{"status":"successful","lastfetchedid":$lastId,"qsos":[${qsos.joinToString(",")}]}"""

    private fun insertLocal(call: String, timeOn: String = "143500", syncedCloudlog: Int = 0, band: String = "20m") {
        val v = ContentValues()
        v.put("call", call)
        v.put("mode", "FT8")
        v.put("qso_date", "20260805")
        v.put("time_on", timeOn)
        v.put("band", band)
        v.put("freq", "14.074")
        v.put("station_callsign", "K1AF")
        v.put("synced_cloudlog", syncedCloudlog)
        opr.db.insert("QSLTable", null, v)
    }

    private fun rows(): List<Map<String, String?>> {
        val c = opr.db.rawQuery("select * from QSLTable order by id", null)
        val out = mutableListOf<Map<String, String?>>()
        while (c.moveToNext()) {
            out.add((0 until c.columnCount).associate { c.getColumnName(it) to c.getString(it) })
        }
        c.close()
        return out
    }

    private fun sync() = ThirdPartyService.syncAllQSOs(opr, null)

    @Test
    fun `a remote-only qso is inserted with its fields and marked synced everywhere`() {
        remotePages["1"] = mutableListOf(
            page(
                10,
                qso(
                    "W1AW", mode = "MFSK",
                    extra = ""","SUBMODE":"FT4","FREQ":"14.080","RST_SENT":"-05","RST_RCVD":"-12",""" +
                        """"GRIDSQUARE":"FN31","MY_GRIDSQUARE":"FN42","COMMENT":"from WSJT-X","TIME_OFF":"143600"""",
                ),
            ),
            emptyPage,
        )
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(1)
        val row = rows().single()
        assertThat(row["call"]).isEqualTo("W1AW")
        // Same normalisation as the ADIF import: MFSK + SUBMODE FT4 is stored as FT4.
        assertThat(row["mode"]).isEqualTo("FT4")
        assertThat(row["band"]).isEqualTo("20m")
        assertThat(row["gridsquare"]).isEqualTo("FN31")
        assertThat(row["my_gridsquare"]).isEqualTo("FN42")
        assertThat(row["station_callsign"]).isEqualTo("K1AF")
        assertThat(row["comment"]).isEqualTo("from WSJT-X")
        assertThat(row["time_off"]).isEqualTo("143600")
        assertThat(row["rst_sent"]).isEqualTo("-05")
        assertThat(row["rst_rcvd"]).isEqualTo("-12")
        assertThat(row["synced_cloudlog"]).isEqualTo("1")
        assertThat(row["synced_qrz"]).isEqualTo("1")
        assertThat(row["synced_wrl"]).isEqualTo("1")
        // The worked-before index the ADIF import fills gets the QSO too.
        val c = opr.db.rawQuery("select count(*) from QslCallsigns where callsign = 'W1AW'", null)
        c.moveToFirst()
        assertThat(c.getInt(0)).isEqualTo(1)
        c.close()
        assertThat(uploads).isEmpty()
        // Nothing is left for QRZ or WRL to pick up either.
        GeneralVariables.enableQRZ = true
        GeneralVariables.enableWRL = true
        assertThat(ThirdPartyService.countUnsyncedQSOs(opr.db)).isEqualTo(0)
    }

    @Test
    fun `the download asks for the full field list`() {
        remotePages["1"] = mutableListOf(emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        sync()

        val fields = pageRequests.first().getJSONArray("fields")
        assertThat((0 until fields.length()).map { fields.getString(it) })
            .containsExactlyElementsIn(CloudlogRemoteLog.IMPORT_FIELDS.toList()).inOrder()
    }

    @Test
    fun `a qso the local log already has is not inserted and not uploaded`() {
        insertLocal("W1AW", timeOn = "143700") // pending, logged 2 minutes later than Wavelog has it
        insertLocal("K2XX", syncedCloudlog = 1) // uploaded earlier
        insertLocal("N3ZZ") // pending, Wavelog doesn't have it
        remotePages["1"] = mutableListOf(page(10, qso("W1AW"), qso("K2XX")), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(0)
        assertThat(rows()).hasSize(3)
        assertThat(result.cloudlogAlreadyPresent).isEqualTo(1)
        assertThat(uploads).hasSize(1)
        assertThat(uploads[0]).contains("<call:4>N3ZZ")
    }

    @Test
    fun `a second sync inserts nothing`() {
        insertLocal("N3ZZ")
        remoteLog = mapOf(
            "1" to listOf(page(10, qso("W1AW"), qso("K2XX", band = "40m")), page(12, qso("VE3AA", timeOn = "150000"))),
            "2" to listOf(page(20, qso("JA1XX", timeOn = "160000"))),
        )

        val first = sync()
        // Wavelog now has the uploaded N3ZZ too.
        remoteLog = remoteLog!! + ("2" to listOf(page(20, qso("JA1XX", timeOn = "160000")), page(21, qso("N3ZZ"))))
        val second = sync()

        assertThat(first.cloudlogDownloaded).isEqualTo(4)
        assertThat(second.cloudlogDownloaded).isEqualTo(0)
        assertThat(rows()).hasSize(5)
        assertThat(uploads).hasSize(1)
    }

    @Test
    fun `toggle off means no inserts and the short field list`() {
        GeneralVariables.cloudlogDownloadQsos = false
        insertLocal("N3ZZ")
        remotePages["1"] = mutableListOf(page(10, qso("W1AW")), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(0)
        assertThat(rows().map { it["call"] }).containsExactly("N3ZZ")
        val fields = pageRequests.first().getJSONArray("fields")
        assertThat((0 until fields.length()).map { fields.getString(it) })
            .containsExactlyElementsIn(CloudlogRemoteLog.FIELDS.toList())
        assertThat(uploads).hasSize(1)
    }

    @Test
    fun `the database-only sync never downloads`() {
        remotePages["1"] = mutableListOf(page(10, qso("W1AW")), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        val result = ThirdPartyService.syncAllQSOs(opr.db, null)

        assertThat(result.cloudlogDownloaded).isEqualTo(0)
        assertThat(rows()).isEmpty()
        assertThat(pageRequests).isEmpty()
    }

    @Test
    fun `plain cloudlog without get_contacts_adif uploads as before and asks once`() {
        // Every listing request 404s (fake returns null).
        listingFailure = "HTTP 404 from https://log.example.org/index.php/api/get_contacts_adif"
        insertLocal("W1AW")
        insertLocal("K2XX")

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(0)
        assertThat(result.cloudlogOk).isEqualTo(2)
        assertThat(uploads).hasSize(2)
        // One try per station; the upload pass doesn't download again.
        assertThat(pageRequests.map { it.getString("station_id") }).containsExactly("1", "2")
    }

    @Test
    fun `the remote log is downloaded once for both directions`() {
        insertLocal("W1AW")
        insertLocal("N3ZZ")
        remotePages["1"] = mutableListOf(page(10, qso("W1AW"), qso("K2XX")), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(pageRequests.map { it.getString("station_id") + "@" + it.getLong("fetchfromid") })
            .containsExactly("1@0", "1@10", "2@0").inOrder()
        assertThat(result.cloudlogDownloaded).isEqualTo(1)
        assertThat(result.cloudlogAlreadyPresent).isEqualTo(1)
        // Download first, then upload: only the pending N3ZZ goes up, not the new K2XX.
        assertThat(uploads).hasSize(1)
        assertThat(uploads[0]).contains("<call:4>N3ZZ")
    }

    @Test
    fun `adif pages from older wavelog are imported`() {
        val adif = "<call:4>W1AW<qso_date:8>20260805<time_on:6>143500<band:3>20m<mode:3>FT8" +
            "<station_callsign:4>K1AF<gridsquare:4>FN31<eor>\n"
        remotePages["1"] = mutableListOf(
            JSONObject().put("status", "successful").put("lastfetchedid", 5).put("adif", adif).toString(),
            emptyPage,
        )
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(1)
        assertThat(rows().single()["gridsquare"]).isEqualTo("FN31")
    }

    @Test
    fun `pages from every station location are imported`() {
        remotePages["1"] = mutableListOf(page(10, qso("W1AW")), page(11, qso("K2XX")), emptyPage)
        remotePages["2"] = mutableListOf(page(30, qso("VE3AA")), emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(3)
        assertThat(rows().map { it["call"] }).containsExactly("W1AW", "K2XX", "VE3AA").inOrder()
    }

    @Test
    fun `a qso logged under two station locations is inserted once`() {
        remotePages["1"] = mutableListOf(page(10, qso("W1AW")), emptyPage)
        remotePages["2"] = mutableListOf(page(30, qso("W1AW")), emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(1)
        assertThat(rows()).hasSize(1)
    }

    @Test
    fun `a remote qso that only differs by band leaves the pending local row alone`() {
        // Same call, date, time and mode as a local row the matcher doesn't pair (other band):
        // the insert would treat it as that row, so it is skipped and the row stays pending
        // and untouched.
        insertLocal("W1AW", band = "40m")
        remotePages["1"] = mutableListOf(
            page(10, qso("W1AW", band = "20m", extra = ""","GRIDSQUARE":"FN42","RST_RCVD":"+03"""")),
            emptyPage,
        )
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(0)
        assertThat(rows()).hasSize(1)
        assertThat(uploads).hasSize(1)
        val row = rows().single()
        assertThat(row["synced_cloudlog"]).isEqualTo("1") // set by the upload, not the download
        assertThat(row["synced_qrz"]).isEqualTo("0")
        // The other QSO's grid and report don't overwrite the local row's.
        assertThat(row["band"]).isEqualTo("40m")
        assertThat(row["gridsquare"]).isNull()
        assertThat(row["rst_rcvd"]).isNull()
        assertThat(row["isLotW_import"]).isEqualTo("0")
    }

    @Test
    fun `a remote qso without a comment gets no import note`() {
        remotePages["1"] = mutableListOf(page(10, qso("W1AW")), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        sync()

        assertThat(rows().single()["comment"]).isEmpty()
    }

    @Test
    fun `downloaded qsos are not stamped with a running pota activation`() {
        val field = PotaSessionManager::class.java.getDeclaredField("_currentActivation")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val current = field.get(PotaSessionManager) as MutableStateFlow<PotaActivation?>
        current.value = PotaActivation(1, "US-1234", "K1AF", 0, null, 0, null)
        try {
            remotePages["1"] = mutableListOf(
                page(10, qso("W1AW"), qso("W2AW", extra = ""","MY_SIG":"POTA","MY_SIG_INFO":"US-0001"""")),
                emptyPage,
            )
            remotePages["2"] = mutableListOf(emptyPage)

            sync()

            val byCall = rows().associateBy { it["call"] }
            assertThat(byCall["W1AW"]!!["my_sig_info"]).isNull()
            assertThat(byCall["W2AW"]!!["my_sig_info"]).isEqualTo("US-0001")
        } finally {
            current.value = null
        }
    }

    @Test
    fun `remote qsos without a call or a time are skipped`() {
        remotePages["1"] = mutableListOf(
            page(10, """{"CALL":"","QSO_DATE":"20260805","TIME_ON":"143500","BAND":"20m","MODE":"FT8"}""",
                """{"CALL":"W1AW","QSO_DATE":"20260805","BAND":"20m","MODE":"FT8"}""", qso("K2XX")),
            emptyPage,
        )
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(1)
        assertThat(rows().map { it["call"] }).containsExactly("K2XX")
    }

    @Test
    fun `cloudlogCanListQsos reads the probe reply`() {
        remotePages["1"] = mutableListOf(emptyPage)
        assertThat(ThirdPartyService.cloudlogCanListQsos()).isTrue()
        // The probe asks past any real cursor, for one QSO, so nothing is downloaded.
        assertThat(pageRequests.single().getLong("fetchfromid")).isEqualTo(Int.MAX_VALUE.toLong())
        assertThat(pageRequests.single().getInt("limit")).isEqualTo(1)

        listingFailure = "HTTP 404 from https://log.example.org/index.php/api/get_contacts_adif"
        assertThat(ThirdPartyService.cloudlogCanListQsos()).isFalse()

        GeneralVariables.cloudlogApiKey = ""
        assertThat(ThirdPartyService.cloudlogCanListQsos()).isNull()
    }

    @Test
    fun `contactsListingSupport tells wavelog, plain cloudlog and unknown apart`() {
        assertThat(ThirdPartyService.contactsListingSupport(emptyPage, "")).isTrue()
        assertThat(ThirdPartyService.contactsListingSupport("<html>404 Page Not Found</html>", "")).isFalse()
        assertThat(ThirdPartyService.contactsListingSupport(null, "HTTP 404 from https://x/api/get_contacts_adif"))
            .isFalse()
        assertThat(ThirdPartyService.contactsListingSupport(null, "HTTP 401: wrong key")).isNull()
        // A JSON refusal is Wavelog answering, just not with QSOs: unknown, not "needs Wavelog".
        assertThat(
            ThirdPartyService.contactsListingSupport("""{"status":"failed","reason":"station id"}""", ""),
        ).isNull()
        assertThat(ThirdPartyService.contactsListingSupport(null, "Timed out: connect")).isNull()
    }

    /** A page holding [n] distinct remote QSOs (a minute apart, so none pair with each other). */
    private fun bigPage(lastId: Long, n: Int): String {
        val qsos = (0 until n).map { i ->
            val minutes = i % (24 * 60)
            val day = 5 + i / (24 * 60)
            """{"CALL":"W${i}AA","QSO_DATE":"202608${"%02d".format(day)}",""" +
                """"TIME_ON":"${"%02d%02d00".format(minutes / 60, minutes % 60)}","BAND":"20m",""" +
                """"MODE":"FT8","STATION_CALLSIGN":"K1AF"}"""
        }
        return """{"status":"successful","lastfetchedid":$lastId,"qsos":[${qsos.joinToString(",")}]}"""
    }

    @Test
    fun `a logbook over the import limit inserts nothing and says why, but still dedupes`() {
        // Holding every QSO's fields for a logbook this size risks an OutOfMemoryError,
        // which auto-sync would hit again on every launch.
        insertLocal("W0AA", timeOn = "000000") // pending; Wavelog already has it (first QSO below)
        remotePages["1"] = mutableListOf(bigPage(10, CloudlogRemoteLog.MAX_KEPT_RECORDS + 1), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(0)
        assertThat(rows()).hasSize(1)
        assertThat(result.cloudlogError).contains("more than ${CloudlogRemoteLog.MAX_KEPT_RECORDS} QSOs")
        // The duplicate check still ran on the whole download: W0AA isn't re-uploaded.
        assertThat(result.cloudlogAlreadyPresent).isEqualTo(1)
        assertThat(uploads).isEmpty()
    }

    @Test
    fun `a station id rejection replaces an earlier download error`() {
        insertLocal("N3ZZ")
        remotePages["1"] = mutableListOf(bigPage(10, CloudlogRemoteLog.MAX_KEPT_RECORDS + 1), emptyPage)
        remotePages["2"] = mutableListOf(emptyPage)
        val rejection = "HTTP 401: {\"reason\":\"missing or wrong api key\"}"
        uploadFailure = rejection

        val result = sync()

        // The rejection is the one with a fix-it hint, so it's the one reported.
        assertThat(result.cloudlogError).isEqualTo(rejection)
    }

    @Test
    fun `an unreadable page from a hostile server doesn't crash the sync`() {
        insertLocal("N3ZZ")
        // org.json parses recursively; this used to be an uncaught StackOverflowError.
        remotePages["1"] = mutableListOf("{\"a\":".repeat(200_000))
        remotePages["2"] = mutableListOf(emptyPage)

        val result = sync()

        assertThat(result.cloudlogDownloaded).isEqualTo(0)
        assertThat(uploads).hasSize(1)
    }
}
