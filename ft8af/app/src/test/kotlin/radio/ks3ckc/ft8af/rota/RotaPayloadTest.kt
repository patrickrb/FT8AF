package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wire-format checks for the live endpoint. Robolectric because org.json is an
 * Android stub on the plain JVM classpath.
 *
 * The server validates every field with zod; anything these tests let through
 * that the schema rejects would 400 the *whole batch*, stranding a trip's
 * backlog behind one bad sample. So the interesting cases here are the ugly
 * ones — a wrapping heading, a rig reporting 0 Hz, a missing report.
 */
@RunWith(RobolectricTestRunner::class)
class RotaPayloadTest {
    private val ts = 1_753_970_709_000L // 2025-07-31T14:05:09Z

    @Test
    fun `iso timestamps are UTC with milliseconds`() {
        assertThat(isoUtc(ts)).isEqualTo("2025-07-31T14:05:09.000Z")
    }

    @Test
    fun `point encodes the fields the API names`() {
        val json =
            TripPoint(
                timestampMs = ts,
                latitude = 39.7392,
                longitude = -104.9903,
                speedMph = 63.4,
                headingDeg = 271.5,
                accuracyM = 6.0,
                state = "Colorado",
                highway = "I-70",
            ).toJson()

        assertThat(json.getString("timestamp")).isEqualTo("2025-07-31T14:05:09.000Z")
        assertThat(json.getDouble("latitude")).isWithin(1e-9).of(39.7392)
        assertThat(json.getDouble("speedMph")).isWithin(1e-9).of(63.4)
        assertThat(json.getString("state")).isEqualTo("Colorado")
        assertThat(json.getString("highway")).isEqualTo("I-70")
    }

    @Test
    fun `unknown optional fields are omitted rather than sent as null`() {
        val json = TripPoint(timestampMs = ts, latitude = 39.0, longitude = -105.0).toJson()
        assertThat(json.has("speedMph")).isFalse()
        assertThat(json.has("headingDeg")).isFalse()
        assertThat(json.has("state")).isFalse()
    }

    @Test
    fun `a heading past 360 is normalised into the accepted range`() {
        val json =
            TripPoint(
                timestampMs = ts,
                latitude = 39.0,
                longitude = -105.0,
                headingDeg = 725.0,
            ).toJson()
        assertThat(json.getDouble("headingDeg")).isWithin(1e-9).of(5.0)
    }

    @Test
    fun `a negative speed is dropped instead of failing the batch`() {
        val json =
            TripPoint(
                timestampMs = ts,
                latitude = 39.0,
                longitude = -105.0,
                speedMph = -1.0,
            ).toJson()
        assertThat(json.has("speedMph")).isFalse()
    }

    @Test
    fun `qso encodes callsign uppercase with reports`() {
        val json =
            TripQso(
                callsign = "k1af",
                timestampMs = ts,
                band = "20m",
                mode = "FT8",
                grid = "dm79",
                sentReport = "-12",
                rcvdReport = "+03",
                roverLat = 39.7,
                roverLon = -104.9,
                frequencyKhz = 14074.0,
            ).toJson()

        assertThat(json.getString("callsign")).isEqualTo("K1AF")
        assertThat(json.getString("sentReport")).isEqualTo("-12")
        assertThat(json.getDouble("frequencyKhz")).isWithin(1e-9).of(14074.0)
    }

    @Test
    fun `an out-of-range frequency is dropped`() {
        val json = TripQso(callsign = "K1AF", timestampMs = ts, frequencyKhz = 0.0).toJson()
        assertThat(json.has("frequencyKhz")).isFalse()
    }

    @Test
    fun `live body carries only the arrays that have content`() {
        val body =
            buildLiveBody(
                points = listOf(TripPoint(ts, 39.0, -105.0)),
                qsos = emptyList(),
            )
        val root = JSONObject(body)
        assertThat(root.getJSONArray("points").length()).isEqualTo(1)
        assertThat(root.has("qsos")).isFalse()
    }

    @Test
    fun `frequency conversion clamps to the accepted band`() {
        assertThat(frequencyKhzOrNull(14_074_000L)).isWithin(1e-9).of(14074.0)
        assertThat(frequencyKhzOrNull(0L)).isNull()
        assertThat(frequencyKhzOrNull(50_000L)).isNull() // 50 Hz — a disconnected rig
    }

    @Test
    fun `reports format like ADIF and drop the no-report sentinels`() {
        assertThat(formatReport(-12)).isEqualTo("-12")
        assertThat(formatReport(3)).isEqualTo("+03")
        assertThat(formatReport(-100)).isNull()
        assertThat(formatReport(-120)).isNull()
    }

    @Test
    fun `mode-aware reports go up plain for voice and signed for digital`() {
        // A 59 sent on SSB must reach the server as "59" — the signed "+59"
        // would disagree with the end-of-trip ADIF and land the QSO twice.
        assertThat(formatReport("SSB", 59)).isEqualTo("59")
        assertThat(formatReport("CW", 599)).isEqualTo("599")
        assertThat(formatReport("FT8", 3)).isEqualTo("+03")
        assertThat(formatReport("FT4", -12)).isEqualTo("-12")
        assertThat(formatReport(null, 59)).isEqualTo("59")
        assertThat(formatReport("SSB", -100)).isNull()
        assertThat(formatReport("FT8", -120)).isNull()
    }

    @Test
    fun `adif date-time parses as UTC and rejects junk`() {
        assertThat(parseAdifUtc("20250731", "140509")).isEqualTo(ts)
        // ADIF allows HH:mm with no seconds.
        assertThat(parseAdifUtc("20250731", "1405")).isEqualTo(ts - 9_000L)
        assertThat(parseAdifUtc("", "140509")).isNull()
        assertThat(parseAdifUtc("20250731", "")).isNull()
        assertThat(parseAdifUtc("2025-07-31", "140509")).isNull()
    }

    @Test
    fun `live ack parses the server's counts`() {
        val ack =
            parseLiveAck(
                """{"points":{"inserted":4},"qsos":{"inserted":2,"duplicatesExisting":3,"duplicatesInBatch":1}}""",
            )
        assertThat(ack).isNotNull()
        assertThat(ack!!.pointsInserted).isEqualTo(4)
        assertThat(ack.qsosInserted).isEqualTo(2)
        assertThat(ack.duplicates).isEqualTo(4)
    }

    @Test
    fun `live ack survives an unexpected body`() {
        assertThat(parseLiveAck("not json")).isNull()
        assertThat(parseLiveAck("")).isNull()
    }

    @Test
    fun `stored round-trip preserves a point`() {
        val original = TripPoint(ts, 39.7392, -104.9903, 63.4, 271.5, 6.0, "Colorado", "I-70")
        val restored = tripPointFromJson(original.toStoredJson())
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `stored round-trip preserves a qso`() {
        val original =
            TripQso(
                callsign = "K1AF",
                timestampMs = ts,
                band = "20m",
                mode = "FT8",
                grid = "DM79",
                sentReport = "-12",
                rcvdReport = "+03",
                roverLat = 39.7,
                roverLon = -104.9,
                state = "Colorado",
                frequencyKhz = 14074.0,
            )
        assertThat(tripQsoFromJson(original.toStoredJson())).isEqualTo(original)
    }

    @Test
    fun `a corrupt stored row decodes to null instead of a bogus point`() {
        assertThat(tripPointFromJson(JSONObject("""{"lat":39.0}"""))).isNull()
        assertThat(tripQsoFromJson(JSONObject("""{"t":1}"""))).isNull()
    }
}
