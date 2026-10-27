package com.k1af.ft8af.log

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CloudlogRemoteLog]: the index of QSOs already in Wavelog that the catch-up sync checks
 * before uploading (issue #936). Robolectric only for org.json.
 */
@RunWith(RobolectricTestRunner::class)
class CloudlogRemoteLogTest {

    private fun log(vararg rows: Array<String?>) = CloudlogRemoteLog().apply {
        rows.forEach { add(it[0], it[1], it[2], it[3], it[4], it[5], it[6]) }
    }

    private fun row(
        call: String? = "W1AW",
        date: String? = "20260805",
        time: String? = "143500",
        band: String? = "20m",
        mode: String? = "FT8",
        submode: String? = null,
        station: String? = "K1AF",
    ) = arrayOf(call, date, time, band, mode, submode, station)

    @Test
    fun `same call band and mode at the same time is present`() {
        val remote = log(row())

        assertThat(remote.contains("W1AW", "20260805", "143500", "20m", "FT8", "K1AF")).isTrue()
    }

    @Test
    fun `start times up to fifteen minutes apart match, further apart do not`() {
        val remote = log(row(time = "143500"))

        assertThat(remote.contains("W1AW", "20260805", "1450", "20m", "FT8", "K1AF")).isTrue()
        assertThat(remote.contains("W1AW", "20260805", "142000", "20m", "FT8", "K1AF")).isTrue()
        assertThat(remote.contains("W1AW", "20260805", "145100", "20m", "FT8", "K1AF")).isFalse()
        assertThat(remote.contains("W1AW", "20260806", "143500", "20m", "FT8", "K1AF")).isFalse()
    }

    @Test
    fun `window works across midnight`() {
        val remote = log(row(date = "20260805", time = "235800"))

        assertThat(remote.contains("W1AW", "20260806", "000500", "20m", "FT8", "K1AF")).isTrue()
    }

    @Test
    fun `different call band or mode is not a match`() {
        val remote = log(row())

        assertThat(remote.contains("W1AX", "20260805", "143500", "20m", "FT8", "K1AF")).isFalse()
        assertThat(remote.contains("W1AW", "20260805", "143500", "40m", "FT8", "K1AF")).isFalse()
        assertThat(remote.contains("W1AW", "20260805", "143500", "20m", "FT4", "K1AF")).isFalse()
    }

    @Test
    fun `remote MFSK with an FT4 submode matches a local FT4 row`() {
        val remote = log(row(mode = "MFSK", submode = "FT4"))

        assertThat(remote.contains("W1AW", "20260805", "143500", "20m", "FT4", "K1AF")).isTrue()
        assertThat(remote.contains("W1AW", "20260805", "143500", "20m", "FT8", "K1AF")).isFalse()
    }

    @Test
    fun `call band and station compare case-insensitively and ignore hash brackets`() {
        val remote = log(row(call = "dk4rh", band = "20M", station = "k1af"))

        assertThat(remote.contains("<DK4RH>", "20260805", "143500", "20m", "ft8", "K1AF")).isTrue()
    }

    @Test
    fun `station callsign must agree only when both sides have one`() {
        val remote = log(row(station = "K1AF"))

        assertThat(remote.contains("W1AW", "20260805", "143500", "20m", "FT8", "N0CALL")).isFalse()
        assertThat(remote.contains("W1AW", "20260805", "143500", "20m", "FT8", null)).isTrue()
        assertThat(log(row(station = null)).contains("W1AW", "20260805", "143500", "20m", "FT8", "N0CALL"))
            .isTrue()
    }

    private fun local(time: String, station: String? = "K1AF", call: String = "W1AW") =
        CloudlogRemoteLog.Local(call, "20260805", time, "20m", "FT8", station)

    @Test
    fun `one remote qso accounts for only one local row`() {
        // W1AW worked twice ten minutes apart; Wavelog has only the first.
        val remote = log(row(time = "143500"))

        assertThat(remote.matchPresent(listOf(local("143500"), local("144500")))).containsExactly(0)
    }

    @Test
    fun `the remote qso goes to the closest local row, not the first one`() {
        val remote = log(row(time = "144400"))

        assertThat(remote.matchPresent(listOf(local("143500"), local("144500")))).containsExactly(1)
    }

    @Test
    fun `two remote qsos cover two local rows`() {
        val remote = log(row(time = "143500"), row(time = "144500"))

        assertThat(remote.matchPresent(listOf(local("143500"), local("144500")))).containsExactly(0, 1)
    }

    @Test
    fun `matchPresent leaves the remote log unchanged between calls`() {
        val remote = log(row())

        assertThat(remote.matchPresent(listOf(local("143500")))).containsExactly(0)
        assertThat(remote.matchPresent(listOf(local("143500")))).containsExactly(0)
    }

    @Test
    fun `a local row without a station callsign uses the upload station's callsign`() {
        val remote = log(row(station = "W1XYZ"))

        // Unknown upload station: matches any station, as before.
        assertThat(remote.contains("W1AW", "20260805", "143500", "20m", "FT8", "")).isTrue()
        remote.setDefaultStationCallsign("k1af")
        assertThat(remote.contains("W1AW", "20260805", "143500", "20m", "FT8", "")).isFalse()
        assertThat(log(row(station = "K1AF")).apply { setDefaultStationCallsign("K1AF") }
            .contains("W1AW", "20260805", "143500", "20m", "FT8", null)).isTrue()
    }

    @Test
    fun `rows without a call or with a bad date are ignored on both sides`() {
        val remote = log(row(call = ""), row(date = "2026-08-05"), row(time = "14"))

        assertThat(remote.size()).isEqualTo(0)
        assertThat(log(row()).contains("W1AW", null, "143500", "20m", "FT8", "K1AF")).isFalse()
    }

    @Test
    fun `epochMinute counts minutes from 1970 UTC and rejects junk`() {
        assertThat(CloudlogRemoteLog.epochMinute("19700101", "0000")).isEqualTo(0L)
        assertThat(CloudlogRemoteLog.epochMinute("19700102", "000100")).isEqualTo(1441L)
        // 2024-02-29 12:00 UTC = 1709208000 s
        assertThat(CloudlogRemoteLog.epochMinute("20240229", "1200")).isEqualTo(1709208000L / 60)
        assertThat(CloudlogRemoteLog.epochMinute("20241301", "1200")).isEqualTo(Long.MIN_VALUE)
        assertThat(CloudlogRemoteLog.epochMinute("20240229", "2500")).isEqualTo(Long.MIN_VALUE)
        assertThat(CloudlogRemoteLog.epochMinute("2024022", "1200")).isEqualTo(Long.MIN_VALUE)
        assertThat(CloudlogRemoteLog.epochMinute("20240229", "12a0")).isEqualTo(Long.MIN_VALUE)
    }

    @Test
    fun `json page adds its qsos and returns the cursor`() {
        val remote = CloudlogRemoteLog()
        val body = """{"status":"successful","lastfetchedid":42,"exported_records":2,"qsos":[
            {"CALL":"W1AW","QSO_DATE":"20260805","TIME_ON":"143500","BAND":"20m","MODE":"FT8","SUBMODE":null,"STATION_CALLSIGN":"K1AF"},
            {"CALL":"K2XX","QSO_DATE":"20260805","TIME_ON":"150000","BAND":"40m","MODE":"MFSK","SUBMODE":"FT4","STATION_CALLSIGN":"K1AF"}]}"""

        assertThat(remote.addPage(body)).isEqualTo(42L)
        assertThat(remote.size()).isEqualTo(2)
        assertThat(remote.contains("K2XX", "20260805", "150000", "40m", "FT4", "K1AF")).isTrue()
    }

    @Test
    fun `adif page from an older wavelog is parsed too`() {
        val remote = CloudlogRemoteLog()
        val adif = "Wavelog ADIF export\n<ADIF_VER:5>3.1.4 <EOH>\n" +
            "<CALL:4>W1AW <QSO_DATE:8>20260805 <TIME_ON:6>143500 <BAND:3>20m <MODE:3>FT8 " +
            "<STATION_CALLSIGN:4>K1AF <EOR>\n" +
            "<call:4>K2XX <qso_date:8>20260805 <time_on:4>1500 <band:3>40m <mode:4>MFSK " +
            "<submode:3>FT4 <eor>\n"
        val body = JSONObject()
            .put("status", "successful")
            .put("lastfetchedid", 7)
            .put("exported_qsos", 2)
            .put("adif", adif)
            .toString()

        assertThat(remote.addPage(body)).isEqualTo(7L)
        assertThat(remote.size()).isEqualTo(2)
        assertThat(remote.contains("W1AW", "20260805", "1435", "20m", "FT8", "K1AF")).isTrue()
        assertThat(remote.contains("K2XX", "20260805", "1500", "40m", "FT4", "K1AF")).isTrue()
    }

    @Test
    fun `empty or failed pages end the walk`() {
        val remote = CloudlogRemoteLog()

        assertThat(
            remote.addPage(
                """{"status":"successful","message":"No new QSOs available.","lastfetchedid":42,"exported_qsos":0,"adif":null}""",
            ),
        ).isEqualTo(-1L)
        assertThat(remote.addPage("""{"status":"successful","lastfetchedid":42,"qsos":[]}""")).isEqualTo(-1L)
        assertThat(remote.addPage("""{"status":"failed","reason":"missing api key"}""")).isEqualTo(-1L)
        assertThat(remote.addPage("<html>not json</html>")).isEqualTo(-1L)
        assertThat(remote.addPage(null)).isEqualTo(-1L)
    }

    @Test
    fun `page request asks for json with only the matching fields`() {
        val req = JSONObject(CloudlogRemoteLog.pageRequestJson("KEY", "3", 42L, 5000))

        assertThat(req.getString("key")).isEqualTo("KEY")
        assertThat(req.getString("station_id")).isEqualTo("3")
        assertThat(req.getLong("fetchfromid")).isEqualTo(42L)
        assertThat(req.getInt("limit")).isEqualTo(5000)
        assertThat(req.getString("output_format")).isEqualTo("json")
        val fields = req.getJSONArray("fields")
        assertThat((0 until fields.length()).map { fields.getString(it) })
            .containsExactly("CALL", "QSO_DATE", "TIME_ON", "BAND", "MODE", "SUBMODE", "STATION_CALLSIGN")
    }

    // -- Two-way sync (issue #965): which remote QSOs are missing locally --

    private fun local(call: String, time: String = "143500", band: String = "20m", mode: String = "FT8") =
        CloudlogRemoteLog.Local(call, "20260805", time, band, mode, "K1AF")

    private fun record(call: String, time: String = "143500", band: String = "20m") = mapOf(
        "CALL" to call, "QSO_DATE" to "20260805", "TIME_ON" to time, "BAND" to band,
        "MODE" to "FT8", "STATION_CALLSIGN" to "K1AF", "GRIDSQUARE" to "FN31",
    )

    @Test
    fun `pair leaves out remote qsos a local row accounts for`() {
        val remote = CloudlogRemoteLog(true).apply {
            addRecord(record("W1AW"))
            addRecord(record("K2XX"))
        }

        val pairing = remote.pair(listOf(local("W1AW", time = "144000")))

        assertThat(pairing.presentLocals).containsExactly(0)
        assertThat(pairing.unmatchedRemote.map { it["CALL"] }).containsExactly("K2XX")
        assertThat(pairing.unmatchedRemote[0]["GRIDSQUARE"]).isEqualTo("FN31")
    }

    @Test
    fun `one local row accounts for only one of two remote qsos`() {
        val remote = CloudlogRemoteLog(true).apply {
            addRecord(record("W1AW", time = "143500"))
            addRecord(record("W1AW", time = "144500"))
        }

        val pairing = remote.pair(listOf(local("W1AW", time = "144400")))

        // The closer remote QSO (14:45) pairs; the 14:35 one is missing locally.
        assertThat(pairing.unmatchedRemote.map { it["TIME_ON"] }).containsExactly("143500")
    }

    @Test
    fun `unmatched remote qsos keep download order`() {
        val remote = CloudlogRemoteLog(true).apply {
            addRecord(record("K2XX"))
            addRecord(record("W1AW"))
            addRecord(record("N3ZZ"))
        }

        assertThat(remote.pair(emptyList()).unmatchedRemote.map { it["CALL"] })
            .containsExactly("K2XX", "W1AW", "N3ZZ").inOrder()
    }

    @Test
    fun `a duplicate-check log keeps no records`() {
        val remote = CloudlogRemoteLog().apply { addRecord(record("W1AW")) }

        assertThat(remote.size()).isEqualTo(1)
        assertThat(remote.pair(emptyList()).unmatchedRemote).isEmpty()
    }

    @Test
    fun `json pages keep every field with upper-case names`() {
        val remote = CloudlogRemoteLog(true)

        remote.addPage(
            """{"status":"successful","lastfetchedid":7,"qsos":[{"call":"W1AW","qso_date":"20260805",""" +
                """"time_on":"143500","band":"20m","mode":"MFSK","submode":"FT4","freq":14.08,""" +
                """"rst_sent":"-05","name":"Hiram","comment":null,"gridsquare":""}]}""",
        )

        val r = remote.pair(emptyList()).unmatchedRemote.single()
        assertThat(r["CALL"]).isEqualTo("W1AW")
        assertThat(r["SUBMODE"]).isEqualTo("FT4")
        assertThat(r["FREQ"]).isEqualTo("14.08")
        assertThat(r["NAME"]).isEqualTo("Hiram")
        // Nulls and blanks are dropped, so the import's own defaults apply.
        assertThat(r).doesNotContainKey("COMMENT")
        assertThat(r).doesNotContainKey("GRIDSQUARE")
    }

    @Test
    fun `adif pages keep every field`() {
        val remote = CloudlogRemoteLog(true)

        remote.addPage(
            JSONObject()
                .put("status", "successful")
                .put("lastfetchedid", 9)
                .put(
                    "adif",
                    "<ADIF_VER:5>3.1.4<EOH>\n<call:4>W1AW<qso_date:8>20260805<time_on:6>143500" +
                        "<band:3>20m<mode:3>FT8<gridsquare:4>FN31<rst_rcvd:3>-12<eor>\n",
                ).toString(),
        )

        val r = remote.pair(emptyList()).unmatchedRemote.single()
        assertThat(r["GRIDSQUARE"]).isEqualTo("FN31")
        assertThat(r["RST_RCVD"]).isEqualTo("-12")
    }

    @Test
    fun `only the import fields are kept from a full adif record`() {
        val remote = CloudlogRemoteLog(true)

        remote.addAdif(
            "<call:4>W1AW<qso_date:8>20260805<time_on:6>143500<band:3>20m<mode:3>FT8" +
                "<qsl_rcvd:1>Y<lotw_qsl_rcvd:1>Y<cqz:1>5<eor>",
        )

        val r = remote.pair(emptyList()).unmatchedRemote.single()
        assertThat(r.keys).containsExactly("CALL", "QSO_DATE", "TIME_ON", "BAND", "MODE")
    }

    @Test
    fun `import page request asks for the fields a full row needs`() {
        val req = JSONObject(
            CloudlogRemoteLog.pageRequestJson("KEY", "3", 0L, 5000, CloudlogRemoteLog.IMPORT_FIELDS),
        )

        val fields = req.getJSONArray("fields")
        assertThat((0 until fields.length()).map { fields.getString(it) }).containsAtLeast(
            "CALL", "QSO_DATE", "TIME_ON", "TIME_OFF", "BAND", "FREQ", "MODE", "SUBMODE",
            "RST_SENT", "RST_RCVD", "GRIDSQUARE", "STATION_CALLSIGN", "MY_GRIDSQUARE", "NAME",
            "COMMENT",
        )
    }

    private fun bareRecord(call: String, time: String = "143500") = mapOf(
        "CALL" to call, "QSO_DATE" to "20260805", "TIME_ON" to time, "BAND" to "20m",
        "MODE" to "FT8", "STATION_CALLSIGN" to "K1AF",
    )

    @Test
    fun `records are kept up to the limit`() {
        val remote = CloudlogRemoteLog(true, 2)
        remote.addRecord(bareRecord("W1AW"))
        remote.addRecord(bareRecord("K2XX"))

        assertThat(remote.tooLargeToImport()).isFalse()
        assertThat(remote.pair(emptyList()).unmatchedRemote).hasSize(2)
    }

    @Test
    fun `one record past the limit drops every kept field but keeps the duplicate check`() {
        val remote = CloudlogRemoteLog(true, 2)
        remote.addRecord(bareRecord("W1AW"))
        remote.addRecord(bareRecord("K2XX"))
        remote.addRecord(bareRecord("N3ZZ"))

        assertThat(remote.tooLargeToImport()).isTrue()
        assertThat(remote.size()).isEqualTo(3)
        assertThat(remote.pair(emptyList()).unmatchedRemote).isEmpty()
        assertThat(remote.contains("N3ZZ", "20260805", "143500", "20m", "FT8", "K1AF")).isTrue()
    }

    @Test
    fun `a duplicate-check-only log never reports too large`() {
        val remote = CloudlogRemoteLog(false, 1)
        remote.addRecord(bareRecord("W1AW"))
        remote.addRecord(bareRecord("K2XX"))

        assertThat(remote.tooLargeToImport()).isFalse()
    }

    @Test
    fun `kept field values are cut to their limits`() {
        val kept = CloudlogRemoteLog.importFieldsOf(
            bareRecord("W1AW") + mapOf(
                "COMMENT" to "c".repeat(CloudlogRemoteLog.MAX_COMMENT_CHARS + 50),
                "NAME" to "n".repeat(CloudlogRemoteLog.MAX_FIELD_CHARS + 50),
            ),
        )

        assertThat(kept["COMMENT"]).hasLength(CloudlogRemoteLog.MAX_COMMENT_CHARS)
        assertThat(kept["NAME"]).hasLength(CloudlogRemoteLog.MAX_FIELD_CHARS)
        assertThat(kept["CALL"]).isEqualTo("W1AW")
    }

    @Test
    fun `a deeply nested page is rejected instead of overflowing the stack`() {
        val remote = CloudlogRemoteLog(true)

        assertThat(remote.addPage("{\"a\":".repeat(200_000))).isEqualTo(-1L)
        assertThat(remote.size()).isEqualTo(0)
    }
}
