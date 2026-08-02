package radio.ks3ckc.ft8af.rota

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wire model for ROTA (Roads On The Air) trip mode — the road-going
 * companion service at roadsontheair.com.
 *
 * Everything here is deliberately free of Android types so the payload shapes
 * can be unit-tested without a device: the manager collects [TripPoint] /
 * [TripQso] values, [RotaQueue] persists them, and [buildLiveBody] renders the
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
data class RotaTripHandle(val id: String, val shareToken: String?)

/**
 * A trip the operator announced on roadsontheair.com — what the site's plan
 * wizard saves, held server-side as a trip with `status: "planned"`.
 *
 * It is the same row the trip will be driven as, not a separate record a trip
 * gets matched to: picking one here and starting it by id is what makes the
 * wizard's privacy choices apply to the drive.
 */
data class RotaPlannedTrip(
    val id: String,
    val name: String,
    val startTimeMs: Long,
    /** Planned finish, or null when the plan is open-ended. */
    val endTimeMs: Long?,
    val detail: String?,
)

/**
 * What `GET /api/trips/:id/sync-state` says the server already holds — the
 * resume handshake for a client that has been out of touch long enough not to
 * know what it still owes.
 */
data class RotaSyncState(
    val tripId: String,
    /** "active" or "completed" — a completed trip must not be fed any more. */
    val status: String,
    val pointCount: Int,
    val qsoCount: Int,
    /** Dedupe keys of the most recent contacts, newest first. */
    val qsoDedupeKeys: Set<String>,
    /** True when the trip holds more keys than the server returned. */
    val truncated: Boolean,
)

/** What the server reports it did with a live batch. */
data class RotaLiveAck(
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

/**
 * Trim and upper-case a callsign for storage and for the wire.
 *
 * `Locale.US` is not decoration. The default-locale `uppercase()` is
 * locale-sensitive for ASCII: under a Turkish or Azeri locale, "i" upper-cases
 * to "İ" (U+0130), so an operator whose phone is set to Turkish would register
 * as KİABC, store KİABC, and never match the KIABC the server knows — while
 * [RotaClient.registerOperator] normalizes with `Locale.US` and disagrees with
 * the very setting that produced it. Callsigns are ASCII by definition, so the
 * locale must be fixed rather than ambient.
 */
fun normalizeCallsign(raw: String): String = raw.trim().uppercase(Locale.US)

/**
 * Clean up a base URL before it is used, repairing the two mistakes that cost a
 * whole trip rather than one request.
 *
 * 1. **A bare host.** "roadsontheair.com" typed into the server field has no scheme, so
 *    `URL()` throws and every upload fails with a MalformedURLException the
 *    operator can do nothing with. Assume https.
 * 2. **The apex host.** `roadsontheair.com` 308-redirects to `www.roadsontheair.com`, and a 308
 *    must not be auto-followed for a POST (RFC 9110 §15.4.9 — the method and
 *    body have to survive, so a client that can't guarantee that declines).
 *    Every write here is a POST, so the apex yields a 308 that
 *    [isRetryableRotaFailure] correctly classifies as fatal, stranding the trip
 *    on the phone. Rewriting the host is the difference between a working test
 *    run and a silent one.
 *
 * Only the hosted service's own domains are rewritten: a self-hosted origin or a
 * dev box is left exactly as typed, since nothing here knows how *it* is fronted.
 */
fun normalizeRotaBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    val withScheme =
        when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
    // Match the known hosts only — not "myroadsontheair.com", and not a path that
    // happens to contain the name. The legacy rtota.app pair (the program's name
    // before it became Roads On The Air) is folded in here too: an install that
    // persisted the old origin before the rename would otherwise keep POSTing at
    // a domain we no longer run, so rewriting on *read* silently repoints it.
    return Regex(
        "^(https?://)(?:www\\.)?(?:roadsontheair\\.com|rtota\\.app)(?=$|[/?#])",
        RegexOption.IGNORE_CASE,
    ).replace(withScheme) { "${it.groupValues[1]}www.roadsontheair.com" }
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

/**
 * The server's dedupe key for this contact: `CALLSIGN|BAND|MODE|UTC-minute`,
 * with an empty field where the value is unknown.
 *
 * A deliberate mirror of `dedupeKey()` in the service's lib/qso.ts, computed
 * from exactly what [toJson] puts on the wire (same trimming, same uppercasing,
 * same truncation) so the two never disagree. The asymmetry is the safety net: a
 * key the client fails to match merely re-sends a contact the server dedupes
 * anyway, whereas a key that matches something it shouldn't would drop a QSO
 * that was never delivered. That is why this mirrors the format exactly rather
 * than approximating it, and why [RotaSyncState] is only ever used to *skip*
 * sends on an exact hit.
 */
fun TripQso.dedupeKey(): String {
    val call = callsign.trim().uppercase(Locale.US).take(20)
    val b = band?.takeIf { it.isNotBlank() }?.take(12)?.trim()?.uppercase(Locale.US).orEmpty()
    val m = mode?.takeIf { it.isNotBlank() }?.take(20)?.trim()?.uppercase(Locale.US).orEmpty()
    // "YYYY-MM-DDTHH:MM" — the ISO instant truncated to the minute, matching the
    // server's `toISOString().slice(0, 16)`.
    val minute = isoUtc(timestampMs).take(16)
    return listOf(call, b, m, minute).joinToString("|")
}

/** Parse a `GET /api/trips/:id/sync-state` body, or null when it is unusable. */
fun parseSyncState(body: String?): RotaSyncState? {
    if (body.isNullOrBlank()) return null
    return try {
        val root = JSONObject(body)
        val tripId = root.optString("tripId").takeIf { it.isNotEmpty() } ?: return null
        val qsos = root.optJSONObject("qsos")
        val keysArray = qsos?.optJSONArray("dedupeKeys")
        val keys = mutableSetOf<String>()
        if (keysArray != null) {
            for (i in 0 until keysArray.length()) {
                keysArray.optString(i).takeIf { it.isNotEmpty() }?.let { keys.add(it) }
            }
        }
        RotaSyncState(
            tripId = tripId,
            status = root.optString("status"),
            pointCount = root.optJSONObject("points")?.optInt("count", 0) ?: 0,
            qsoCount = qsos?.optInt("count", 0) ?: 0,
            qsoDedupeKeys = keys,
            truncated = qsos?.optBoolean("truncated", false) ?: false,
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Parse an ISO-8601 instant of the shape the API returns
 * (`2026-08-04T11:00:00.000Z`), or null when it is missing or unusable.
 *
 * Deliberately narrow: the API always emits UTC with milliseconds, and a
 * hand-rolled lenient parser would happily accept a local-time string and place
 * a trip several hours from where it belongs.
 */
fun parseIsoUtc(value: String?): Long? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.isLenient = false
        fmt.parse(raw)?.time
    } catch (_: Exception) {
        null
    }
}

/**
 * Pull the operator's own announced trips out of a `GET /api/me` body, soonest
 * departure first. Rows missing an id, name or start time are dropped rather
 * than shown as blanks in the picker.
 *
 * The key is `plannedTrips` and the label is `name`. Both were `activations`
 * and `title` before announcements became a trip status, and the rename is the
 * kind that fails quietly here — the catch below turns a shape mismatch into an
 * empty list, which the picker renders as "no upcoming trips" rather than an
 * error. Worth pinning in a test for exactly that reason.
 */
fun parseMyPlannedTrips(body: String?): List<RotaPlannedTrip> {
    if (body.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONObject(body).optJSONArray("plannedTrips") ?: return emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val name = o.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val start = parseIsoUtc(o.optString("startTime")) ?: continue
                add(
                    RotaPlannedTrip(
                        id = id,
                        name = name,
                        startTimeMs = start,
                        endTimeMs = parseIsoUtc(o.optString("endTime")),
                        detail = o.optString("detail").takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }.sortedBy { it.startTimeMs }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Parse the live endpoint's response into counts for the UI. */
fun parseLiveAck(body: String?): RotaLiveAck? {
    if (body.isNullOrBlank()) return null
    return try {
        val root = JSONObject(body)
        val points = root.optJSONObject("points")
        val qsos = root.optJSONObject("qsos")
        RotaLiveAck(
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
