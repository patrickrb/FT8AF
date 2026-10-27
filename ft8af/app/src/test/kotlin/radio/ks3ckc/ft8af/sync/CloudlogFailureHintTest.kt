package radio.ks3ckc.ft8af.sync

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test

/**
 * Unit tests for the rejected-upload explainer: [cloudlogServerReason], [classifyCloudlogFailure]
 * and [cloudlogHintResId].
 *
 * The motivating case is a Wavelog user whose Test Connection passes but every QSO comes
 * back `HTTP 401: {"status":"failed","reason":"station id does not belong to the API key
 * owner."}` — a wrong Station ID. The app used to show "0 accepted" and nothing else.
 */
class CloudlogFailureHintTest {
    private val stationRejection =
        "HTTP 401: {\"status\":\"failed\",\"reason\":\"station id does not belong to the API key owner.\"}"

    @Test
    fun `wavelog station rejection yields the bare reason and the station hint`() {
        assertThat(cloudlogServerReason(stationRejection))
            .isEqualTo("station id does not belong to the API key owner.")
        assertThat(classifyCloudlogFailure(stationRejection)).isEqualTo(CloudlogFailureKind.STATION_ID)
        assertThat(cloudlogHintResId(stationRejection)).isEqualTo(R.string.cloudlog_hint_station_id)
    }

    @Test
    fun `wrong api key points at the key, not the station`() {
        val f = "HTTP 401: {\"status\":\"failed\",\"reason\":\"missing or wrong api key\"}"

        assertThat(cloudlogServerReason(f)).isEqualTo("missing or wrong api key")
        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.API_KEY)
        assertThat(cloudlogHintResId(f)).isEqualTo(R.string.cloudlog_hint_api_key)
    }

    @Test
    fun `read-only key is an api key problem`() {
        val f = "HTTP 403: {\"status\":\"failed\",\"reason\":\"API key does not have write permissions\"}"

        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.API_KEY)
    }

    @Test
    fun `adif import errors surface the messages array with no hint`() {
        val f =
            "HTTP 400: {\"status\":\"abort\",\"adif_errors\":1," +
                "\"messages\":[\"column \\\"tx_pwr\\\" of relation \\\"contacts\\\" does not exist\", \"\"]}"

        assertThat(cloudlogServerReason(f))
            .isEqualTo("column \"tx_pwr\" of relation \"contacts\" does not exist")
        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.OTHER)
        assertThat(cloudlogHintResId(f)).isNull()
    }

    @Test
    fun `several messages are joined`() {
        val f = "HTTP 400: {\"status\":\"abort\",\"messages\":[\"first\",\"second\"]}"

        assertThat(cloudlogServerReason(f)).isEqualTo("first; second")
    }

    @Test
    fun `non-json failures pass through unchanged`() {
        assertThat(cloudlogServerReason("HTTP 502")).isEqualTo("HTTP 502")
        assertThat(cloudlogServerReason("Unknown host: wavelog.example")).isEqualTo("Unknown host: wavelog.example")
        assertThat(cloudlogServerReason("HTTP 500: <html>gateway timeout</html>"))
            .isEqualTo("HTTP 500: <html>gateway timeout</html>")
        assertThat(classifyCloudlogFailure("HTTP 502")).isEqualTo(CloudlogFailureKind.OTHER)
        assertThat(cloudlogHintResId("Unknown host: wavelog.example")).isNull()
    }

    @Test
    fun `blank and null are harmless`() {
        assertThat(cloudlogServerReason(null)).isEmpty()
        assertThat(cloudlogServerReason("   ")).isEmpty()
        assertThat(classifyCloudlogFailure(null)).isEqualTo(CloudlogFailureKind.OTHER)
        assertThat(cloudlogHintResId("")).isNull()
    }

    @Test
    fun `station check wins even though its text also mentions the api key`() {
        // "…does not belong to the API key owner" contains "api key"; the fix is still the station.
        assertThat(classifyCloudlogFailure(stationRejection)).isEqualTo(CloudlogFailureKind.STATION_ID)
    }

    @Test
    fun `a bracket or comma inside a message does not cut it short`() {
        val f = "HTTP 400: {\"status\":\"abort\",\"messages\":[\"Import failed\",\"Invalid [BAND] value, see docs\"]}"

        assertThat(cloudlogServerReason(f)).isEqualTo("Import failed; Invalid [BAND] value, see docs")
    }

    @Test
    fun `standard json escapes are decoded`() {
        val f = "HTTP 400: {\"reason\":\"caf\\u00e9 \\/ tab\\there \\\"q\\\"\"}"

        assertThat(cloudlogServerReason(f)).isEqualTo("café / tab\there \"q\"")
    }

    @Test
    fun `decodeJsonString keeps a malformed escape rather than dropping it`() {
        assertThat(decodeJsonString("bad \\u12 end")).isEqualTo("bad \\u12 end")
        assertThat(decodeJsonString("trailing \\")).isEqualTo("trailing \\")
        assertThat(decodeJsonString("plain")).isEqualTo("plain")
    }

    @Test
    fun `a truncated messages array falls back to the raw line`() {
        val f = "HTTP 400: {\"status\":\"abort\",\"messages\":[\"first\",\"seco…"

        assertThat(cloudlogServerReason(f)).isEqualTo(f)
        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.OTHER)
    }

    @Test
    fun `a messages array of non-strings falls back to the raw line`() {
        val f = "HTTP 400: {\"messages\":[{\"x\":1}]}"

        assertThat(cloudlogServerReason(f)).isEqualTo(f)
    }

    @Test
    fun `a transport error naming a station host is not a station id problem`() {
        val f = "Unknown host: station.example.com"

        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.OTHER)
        assertThat(cloudlogHintResId(f)).isNull()
        assertThat(cloudlogFailureStopsBatch(f)).isFalse()
    }

    @Test
    fun `an adif message about the station callsign is not a station id problem`() {
        val f = "HTTP 400: {\"status\":\"abort\",\"messages\":[\"Wrong station callsign W1AW while importing QSO\"]}"

        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.OTHER)
    }

    @Test
    fun `station_profile_id wording is still a station id problem`() {
        val f = "HTTP 400: {\"status\":\"failed\",\"reason\":\"station_profile_id missing\"}"

        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.STATION_ID)
    }

    @Test
    fun `only station and key rejections stop a batch`() {
        assertThat(cloudlogFailureStopsBatch(stationRejection)).isTrue()
        assertThat(cloudlogFailureStopsBatch("HTTP 401: {\"reason\":\"missing or wrong api key\"}")).isTrue()
        assertThat(cloudlogFailureStopsBatch("HTTP 400: {\"messages\":[\"bad band\"]}")).isFalse()
        assertThat(cloudlogFailureStopsBatch("Timed out: connect")).isFalse()
        assertThat(cloudlogFailureStopsBatch(null)).isFalse()
    }

    @Test
    fun `only an http answer from the server counts as a rejection`() {
        assertThat(isCloudlogServerRejection(stationRejection)).isTrue()
        assertThat(isCloudlogServerRejection("HTTP 502")).isTrue()
        assertThat(isCloudlogServerRejection("HTTP 404 from https://x/index.php/api/qso")).isTrue()
        assertThat(isCloudlogServerRejection("Unknown host: wavelog.example")).isFalse()
        assertThat(isCloudlogServerRejection("Timed out: connect timed out")).isFalse()
        assertThat(isCloudlogServerRejection("No response from https://x/api/qso")).isFalse()
        assertThat(isCloudlogServerRejection("no server address configured")).isFalse()
        assertThat(isCloudlogServerRejection("")).isFalse()
        assertThat(isCloudlogServerRejection(null)).isFalse()
    }
}
