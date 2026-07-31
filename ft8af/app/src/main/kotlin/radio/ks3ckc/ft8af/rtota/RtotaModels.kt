package radio.ks3ckc.ft8af.rtota

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wire model for RTOTA (Road Trips On The Air) trip mode — the road-going
 * companion service at rtota.app.
 *
 * Everything here is deliberately free of Android types so the payload shapes
 * can be unit-tested without a device: the manager collects [TripPoint] /
 * [TripQso] values, [RtotaQueue] persists them, and [buildLiveBody] renders the
 * exact JSON `POST /api/trips/:id/live` expects.
 *
 * The server validates every field (zod schemas in lib/api/schemas.ts), so the
 * builders below clamp/drop anything out of range rather than let the whole
 * batch bounce with a 400 — one bad GPS sample must never strand a trip's
 * backlog. Nullable fields are simply omitted when unknown.
 */

// One GPS breadcrumb along the route.
data class TripPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    /** Ground speed in mph, or null when the fix carried no speed. */
    val speedMph: Double? = null,
    /** Course over ground, 0–360 degrees, or null when unknown/stationary. */
    val headingDeg: Double? = null,
    /** Horizontal accuracy in metres, or null when the fix carried none. */
    val accuracyM: Double? = null,
    /** U.S. state (or other region) label, when the app has resolved one. */
    val state: String? = null,
    /** Highway label such as "I-70", when known. */
    val highway: String? = null,
)

/** One logged contact, stamped with where the rover was at the time. */
data class TripQso(
    val callsign: String,
    val timestampMs: Long,
    val band: String? = null,
    val mode: String? = null,
    val grid: String? = null,
    val sentReport: String? = null,
    val rcvdReport: String? = null,
    val roverLat: Double? = null,
    val roverLon: Double? = null,
    val state: String? = null,
    /** Dial frequency in kHz — powers precise auto-spots on the server. */
    val frequencyKhz: Double? = null,
)

/** Trip identifiers handed back by `POST /api/trips`. */
data class RtotaTripHandle(val id: String, val shareToken: String?)

/** What the server reports it did with a live batch. */
data class RtotaLiveAck(
    val pointsInserted: Int,
    val qsosInserted: Int,
    val duplicates: Int,
)

// ---------------------------------------------------------------------------
// Formatting helpers (pure)
// ---------------------------------------------------------------------------

/**
 * Render epoch millis as the UTC ISO-8601 instant the API's `isoDate` schema
 * accepts (`z.string().datetime()`), e.g. `2026-07-31T14:05:09.000Z`.
 *
 * A fresh [SimpleDateFormat] per call: the instances are cheap next to an HTTP
 * round trip, and a shared one would need synchronising — points arrive from
 * the location thread while the flush thread is serialising a batch.
 */
fun isoUtc(epochMs: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(epochMs))
}

/**
 * Parse an ADIF date/time pair (`QSO_DATE` = `yyyyMMdd`, `TIME_ON` = `HHmmss`
 * or `HHmm`) as UTC epoch millis, or null when either half is unusable.
 *
 * QSLRecord stores its timestamps in exactly this split form, so this is the
 * bridge from a logged contact back to a real instant. Lenient parsing is off:
 * a malformed row must yield null (the caller then falls back to "now") rather
 * than silently land the QSO in year 202.
 */
fun parseAdifUtc(
    date: String?,
    time: String?,
): Long? {
    val d = date?.trim().orEmpty()
    val t = time?.trim().orEmpty()
    if (d.length != 8 || t.length < 4) return null
    // ADIF allows HHmm (no seconds); pad so one pattern covers both.
    val padded = if (t.length == 4) t + "00" else t.take(6)
    return try {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.isLenient = false
        fmt.parse(d + padded)?.time
    } catch (_: Exception) {
        null
    }
}

/**
 * Format an FT8 signal report for the wire, or null when the value is one of
 * QSLRecord's "no report" sentinels (-100 for an unfinished/SWL exchange, -120
 * from imported logs). Mirrors `AdifFormat.formatReport` for real values so a
 * live-sent report reads identically to the one in the end-of-trip ADIF.
 */
fun formatReport(report: Int): String? = if (report == -100 || report == -120) null else String.format(Locale.US, "%+03d", report)

/**
 * Dial frequency in Hz → kHz, or null when outside the server's accepted range
 * (100 kHz – 10 GHz). A rig that reports 0 while disconnected would otherwise
 * fail schema validation for the entire batch.
 */
fun frequencyKhzOrNull(freqHz: Long): Double? {
    if (freqHz <= 0L) return null
    val khz = freqHz / 1000.0
    return if (khz in 100.0..10_000_000.0) khz else null
}

// ---------------------------------------------------------------------------
// JSON encoding / decoding
// ---------------------------------------------------------------------------

private fun JSONObject.putIfNotNull(
    name: String,
    value: Any?,
) {
    if (value != null) put(name, value)
}

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.optDoubleOrNull(name: String): Double? = if (isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }

/**
 * Encode a point. Out-of-range optional fields are dropped rather than clamped
 * where a wrong value would be a lie (a bogus heading), and clamped where the
 * value is merely imprecise (a hair over 360 from a wrapping compass).
 */
fun TripPoint.toJson(): JSONObject =
    JSONObject().apply {
        put("timestamp", isoUtc(timestampMs))
        put("latitude", latitude)
        put("longitude", longitude)
        putIfNotNull("speedMph", speedMph?.takeIf { it.isFinite() && it >= 0.0 })
        putIfNotNull("headingDeg", headingDeg?.takeIf { it.isFinite() }?.let { ((it % 360.0) + 360.0) % 360.0 })
        putIfNotNull("accuracy", accuracyM?.takeIf { it.isFinite() && it >= 0.0 })
        putIfNotNull("state", state?.takeIf { it.isNotBlank() }?.take(40))
        putIfNotNull("highway", highway?.takeIf { it.isNotBlank() }?.take(40))
    }

fun TripQso.toJson(): JSONObject =
    JSONObject().apply {
        put("callsign", callsign.trim().uppercase(Locale.US).take(20))
        put("timestamp", isoUtc(timestampMs))
        putIfNotNull("band", band?.takeIf { it.isNotBlank() }?.take(12))
        putIfNotNull("mode", mode?.takeIf { it.isNotBlank() }?.take(20))
        putIfNotNull("grid", grid?.takeIf { it.isNotBlank() }?.take(12))
        putIfNotNull("sentReport", sentReport?.takeIf { it.isNotBlank() }?.take(12))
        putIfNotNull("rcvdReport", rcvdReport?.takeIf { it.isNotBlank() }?.take(12))
        putIfNotNull("roverLat", roverLat?.takeIf { it.isFinite() && it in -90.0..90.0 })
        putIfNotNull("roverLon", roverLon?.takeIf { it.isFinite() && it in -180.0..180.0 })
        putIfNotNull("state", state?.takeIf { it.isNotBlank() }?.take(40))
        putIfNotNull("frequencyKhz", frequencyKhz?.takeIf { it.isFinite() && it in 100.0..10_000_000.0 })
    }

/** Rebuild a point from the queue file. Returns null for an unparsable row. */
fun tripPointFromJson(o: JSONObject): TripPoint? {
    val ts = o.optLong("t", 0L)
    if (ts <= 0L) return null
    val lat = o.optDouble("lat", Double.NaN)
    val lon = o.optDouble("lon", Double.NaN)
    if (lat.isNaN() || lon.isNaN()) return null
    return TripPoint(
        timestampMs = ts,
        latitude = lat,
        longitude = lon,
        speedMph = o.optDoubleOrNull("spd"),
        headingDeg = o.optDoubleOrNull("hdg"),
        accuracyM = o.optDoubleOrNull("acc"),
        state = o.optStringOrNull("st"),
        highway = o.optStringOrNull("hw"),
    )
}

/**
 * Queue-file encoding. Deliberately *not* [toJson]: the queue stores raw epoch
 * millis and short keys so a long dead-zone backlog stays small on disk, and so
 * a future change to the wire format can't corrupt already-queued data.
 */
fun TripPoint.toStoredJson(): JSONObject =
    JSONObject().apply {
        put("t", timestampMs)
        put("lat", latitude)
        put("lon", longitude)
        putIfNotNull("spd", speedMph)
        putIfNotNull("hdg", headingDeg)
        putIfNotNull("acc", accuracyM)
        putIfNotNull("st", state)
        putIfNotNull("hw", highway)
    }

fun TripQso.toStoredJson(): JSONObject =
    JSONObject().apply {
        put("t", timestampMs)
        put("call", callsign)
        putIfNotNull("band", band)
        putIfNotNull("mode", mode)
        putIfNotNull("grid", grid)
        putIfNotNull("rs", sentReport)
        putIfNotNull("rr", rcvdReport)
        putIfNotNull("lat", roverLat)
        putIfNotNull("lon", roverLon)
        putIfNotNull("st", state)
        putIfNotNull("khz", frequencyKhz)
    }

fun tripQsoFromJson(o: JSONObject): TripQso? {
    val ts = o.optLong("t", 0L)
    val call = o.optString("call").trim()
    if (ts <= 0L || call.isEmpty()) return null
    return TripQso(
        callsign = call,
        timestampMs = ts,
        band = o.optStringOrNull("band"),
        mode = o.optStringOrNull("mode"),
        grid = o.optStringOrNull("grid"),
        sentReport = o.optStringOrNull("rs"),
        rcvdReport = o.optStringOrNull("rr"),
        roverLat = o.optDoubleOrNull("lat"),
        roverLon = o.optDoubleOrNull("lon"),
        state = o.optStringOrNull("st"),
        frequencyKhz = o.optDoubleOrNull("khz"),
    )
}

/** The body of one `POST /api/trips/:id/live` call. */
fun buildLiveBody(
    points: List<TripPoint>,
    qsos: List<TripQso>,
): String =
    JSONObject().apply {
        if (points.isNotEmpty()) {
            put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })
        }
        if (qsos.isNotEmpty()) {
            put("qsos", JSONArray().apply { qsos.forEach { put(it.toJson()) } })
        }
    }.toString()

/** Parse the live endpoint's response into counts for the UI. */
fun parseLiveAck(body: String?): RtotaLiveAck? {
    if (body.isNullOrBlank()) return null
    return try {
        val root = JSONObject(body)
        val points = root.optJSONObject("points")
        val qsos = root.optJSONObject("qsos")
        RtotaLiveAck(
            pointsInserted = points?.optInt("inserted", 0) ?: 0,
            qsosInserted = qsos?.optInt("inserted", 0) ?: 0,
            duplicates =
                (qsos?.optInt("duplicatesExisting", 0) ?: 0) +
                    (qsos?.optInt("duplicatesInBatch", 0) ?: 0),
        )
    } catch (_: Exception) {
        null
    }
}
