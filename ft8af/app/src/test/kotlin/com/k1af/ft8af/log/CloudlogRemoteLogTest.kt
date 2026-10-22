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
}
