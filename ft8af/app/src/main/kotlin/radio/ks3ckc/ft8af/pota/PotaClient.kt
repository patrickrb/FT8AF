package radio.ks3ckc.ft8af.pota

import android.util.Log
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import radio.ks3ckc.ft8af.pota.model.PotaLocation
import radio.ks3ckc.ft8af.pota.model.PotaPark
import radio.ks3ckc.ft8af.pota.model.PotaSpot
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thrown by [PotaClient.uploadAdif] when POTA's endpoint returns a non-2xx HTTP
 * status. Carries the status so callers can tell a transient gateway outage
 * (502/503/504 — retryable) from a real log rejection (other 5xx — usually a bad
 * park ref or unregistered callsign) or a client/auth problem (4xx).
 */
class PotaUploadException(val httpCode: Int, val body: String) :
    Exception("HTTP $httpCode${if (body.isNotBlank()) ": ${body.take(160)}" else ""}")

/**
 * HTTP statuses that mean POTA's backend was *transiently* unavailable (a gateway
 * timed out or the upstream Lambda was cold/overloaded) rather than rejecting the
 * log itself. These are worth retrying; a 4xx or a plain 500 is not — the request
 * would fail again identically. (The historical every-attempt 502 turned out not
 * to be transient at all — POTA's Lambda crashed on a capitalized `Content-Type`
 * header name; see [uploadRequestHeaders]. Genuine gateway flaps still exist and
 * are what this retry is for.)
 */
private val RETRYABLE_UPLOAD_CODES = setOf(502, 503, 504)

/** Total upload attempts (the initial try plus retries) before giving up. */
private const val MAX_UPLOAD_ATTEMPTS = 3

/**
 * True when [error] is a transient upload failure worth retrying: a gateway status
 * in [RETRYABLE_UPLOAD_CODES], or a connectivity [java.io.IOException] (timeout,
 * connection reset, dropped DNS). A non-retryable status (4xx, plain 500) or any
 * other throwable returns false so the caller surfaces it immediately.
 */
internal fun isRetryableUploadFailure(error: Throwable?): Boolean = when (error) {
    is PotaUploadException -> error.httpCode in RETRYABLE_UPLOAD_CODES
    is java.io.IOException -> true
    else -> false
}

/**
 * Exponential backoff before retry [attempt] (1-based): 1s, 2s, 4s, … Keeps the
 * total wait modest (a few seconds) so the upload toast doesn't hang, while still
 * giving a flapping gateway time to recover between tries.
 */
internal fun uploadBackoffMs(attempt: Int): Long = 1000L shl (attempt - 1)

/**
 * Count non-overlapping occurrences of [needle] in [haystack]. Used to report the
 * QSO count of an ADIF document (one `<EOR>` per record) in the upload diagnostics.
 * Pure so it can be unit-tested; returns 0 for an empty needle rather than looping.
 */
internal fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var i = haystack.indexOf(needle)
    while (i >= 0) {
        count++
        i = haystack.indexOf(needle, i + needle.length)
    }
    return count
}

/**
 * Request headers for the authenticated `/adif` upload, with every name in
 * lowercase. POTA's upload Lambda looks headers up **case-sensitively by their
 * lowercase name**: browsers work because HTTP/2 lowercases all header names on
 * the wire, but HttpURLConnection speaks HTTP/1.1 and preserves the casing we
 * set — a `Content-Type` spelled with capitals makes the Lambda miss the
 * multipart boundary and crash with the long-standing 502
 * `InternalServerErrorException` (verified by replaying the identical body via
 * curl: `Content-Type:` → 502, `content-type:` → 200). Lowercase names are
 * always legal (RFC 9110 §5.1 — field names are case-insensitive), so this is
 * safe for any spec-compliant server too.
 */
internal fun uploadRequestHeaders(idToken: String, boundary: String): List<Pair<String, String>> =
    listOf(
        "user-agent" to "ft8af-1.0",
        "authorization" to idToken,
        "content-type" to "multipart/form-data; boundary=$boundary",
        "accept" to "application/json",
    )

/**
 * Build the multipart/form-data upload body exactly as pota.app's "My Log
 * Uploads" page does: the `adif` file part first, then plain `reference`,
 * `location` and `callsign` fields. The three plain fields are NOT optional
 * decoration — a POST carrying only the `adif` part returns 200 with
 * `{"adif_files": []}` and POTA silently discards the log (no processing job is
 * ever created); with the fields present the same file is ingested and
 * processed within seconds. Pure so the exact byte layout is unit-testable.
 */
internal fun buildUploadBody(
    boundary: String,
    filename: String,
    adif: String,
    reference: String,
    location: String,
    callsign: String,
): ByteArray {
    val sb = StringBuilder()
    sb.append("--").append(boundary).append("\r\n")
    sb.append("Content-Disposition: form-data; name=\"adif\"; filename=\"").append(filename).append("\"\r\n")
    sb.append("Content-Type: application/octet-stream\r\n\r\n")
    sb.append(adif)
    sb.append("\r\n")
    for ((name, value) in listOf("reference" to reference, "location" to location, "callsign" to callsign)) {
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
        sb.append(value)
        sb.append("\r\n")
    }
    sb.append("--").append(boundary).append("--\r\n")
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

/**
 * Response headers worth capturing when POTA's `/adif` endpoint fails. A 502 with
 * body `{"message":"Internal server error"}` comes from AWS API Gateway / CloudFront
 * in front of POTA's Lambda, and these headers carry the request/trace ids that let
 * POTA support (and us) pinpoint the failing invocation — far more actionable than
 * the opaque body. Matched case-insensitively; only those present are logged.
 */
internal val UPLOAD_TRACE_HEADERS = listOf(
    "x-amzn-RequestId",
    "x-amzn-ErrorType",
    "apigw-requestid",
    "x-amzn-trace-id",
    "x-cache",
    "via",
    "Content-Type",
)

/**
 * Build the one-line trace summary logged on an upload failure from a connection's
 * response [headers]. Pure (no Android/network types) so the header selection can be
 * unit-tested directly. Keys are matched case-insensitively — [HttpURLConnection]
 * preserves the server's casing and includes a null-keyed entry for the status line,
 * both of which this tolerates. Only [UPLOAD_TRACE_HEADERS] found in [headers] appear,
 * in declared order, as space-separated `name=value` pairs (multi-valued headers
 * comma-joined); returns "" when none are present.
 */
internal fun uploadTraceSummary(headers: Map<String?, List<String>>): String {
    val lower = headers.entries
        .mapNotNull { e -> e.key?.let { it.lowercase() to e.value.joinToString(",") } }
        .toMap()
    return UPLOAD_TRACE_HEADERS
        .mapNotNull { h -> lower[h.lowercase()]?.let { "$h=$it" } }
        .joinToString(" ")
}

/**
 * Talks to pota.app's read+write endpoints. Mirrors [radio.ks3ckc.ft8af.pskreporter.PskReporterClient]:
 *   - HttpURLConnection only (no extra deps).
 *   - Coroutine-friendly suspend functions on Dispatchers.IO.
 *   - Logs every call into debug.log alongside the rest of the network layer.
 *
 * Endpoints:
 *   GET  https://api.pota.app/spot/activator       -> JSON array of live spots
 *   POST https://api.pota.app/spot                 -> self-spot
 *   GET  https://api.pota.app/park/<reference>     -> park details (optional / fallback)
 */
object PotaClient {
    private const val TAG = "PotaClient"
    private const val BASE_URL = "https://api.pota.app"
    private const val USER_AGENT = "ft8af-1.0"
    private const val IO_TIMEOUT_MS = 10_000

    suspend fun getActiveSpots(modeFilter: String? = "FT8"): List<PotaSpot>? =
        withContext(Dispatchers.IO) {
            val body = httpGet("$BASE_URL/spot/activator") ?: return@withContext null
            try {
                val arr = JSONArray(body)
                val out = ArrayList<PotaSpot>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val mode = o.optString("mode", "")
                    if (modeFilter != null && !mode.equals(modeFilter, ignoreCase = true)) continue
                    val freqKhz = o.optString("frequency", "0").toDoubleOrNull() ?: 0.0
                    out.add(
                        PotaSpot(
                            activator = o.optString("activator", "").uppercase(),
                            frequencyKhz = freqKhz,
                            mode = mode,
                            reference = o.optString("reference", ""),
                            parkName = o.optString("name", ""),
                            locationDesc = o.optString("locationDesc", ""),
                            spotter = o.optString("spotter", ""),
                            spotTimeUtc = o.optString("spotTime", ""),
                            comments = o.optString("comments", ""),
                        ),
                    )
                }
                log("spots ok N=${out.size} (filter=${modeFilter ?: "any"})")
                out
            } catch (e: Exception) {
                log("spots parse error: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
                null
            }
        }

    suspend fun selfSpot(
        activator: String,
        spotter: String,
        frequencyKhz: Double,
        mode: String,
        reference: String,
        comments: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("activator", activator.uppercase())
            put("spotter", spotter.uppercase())
            put("frequency", String.format(Locale.US, "%.1f", frequencyKhz))
            put("reference", reference.uppercase())
            put("mode", mode)
            put("source", "FT8AF")
            put("comments", comments)
        }.toString()
        val ok = httpPost("$BASE_URL/spot", body) != null
        log("selfSpot ${if (ok) "ok" else "FAILED"} ref=$reference freq=${frequencyKhz}kHz mode=$mode")
        ok
    }

    suspend fun lookupPark(reference: String): PotaPark? = withContext(Dispatchers.IO) {
        val ref = reference.trim().uppercase()
        if (ref.isEmpty()) return@withContext null
        val body = httpGet("$BASE_URL/park/${urlEncode(ref)}") ?: return@withContext null
        try {
            val o = JSONObject(body)
            PotaPark(
                reference = ref,
                name = o.optString("name", ""),
                locationDesc = o.optString("locationDesc", ""),
                latitude = o.optDouble("latitude", 0.0),
                longitude = o.optDouble("longitude", 0.0),
                grid = o.optString("grid", ""),
                activations = o.optInt("activations", 0),
                qsos = o.optInt("qsos", 0),
            )
        } catch (e: Exception) {
            log("park parse error: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            null
        }
    }

    /** Fetch all POTA location codes (e.g. US-PA, VE-ON) with their center coordinates. */
    suspend fun getLocations(): List<PotaLocation>? = withContext(Dispatchers.IO) {
        val body = httpGet("$BASE_URL/locations") ?: return@withContext null
        try {
            val arr = JSONArray(body)
            val out = ArrayList<PotaLocation>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lat = o.optDouble("latitude", Double.NaN)
                val lng = o.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                out.add(
                    PotaLocation(
                        locationDesc = o.optString("locationDesc", ""),
                        locationName = o.optString("locationName", ""),
                        latitude = lat,
                        longitude = lng,
                    ),
                )
            }
            log("locations ok N=${out.size}")
            out
        } catch (e: Exception) {
            log("locations parse error: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            null
        }
    }

    /** Fetch all parks within a POTA location code (e.g. US-PA). */
    suspend fun getParksForLocation(locationCode: String): List<PotaPark>? =
        withContext(Dispatchers.IO) {
            val code = locationCode.trim().uppercase()
            if (code.isEmpty()) return@withContext null
            val body = httpGet("$BASE_URL/location/parks/${urlEncode(code)}")
                ?: return@withContext null
            try {
                val arr = JSONArray(body)
                val out = ArrayList<PotaPark>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        PotaPark(
                            reference = o.optString("reference", ""),
                            name = o.optString("name", ""),
                            locationDesc = o.optString("locationDesc", ""),
                            latitude = o.optDouble("latitude", 0.0),
                            longitude = o.optDouble("longitude", 0.0),
                            grid = o.optString("grid", ""),
                            activations = o.optInt("activations", 0),
                            qsos = o.optInt("qsos", 0),
                        ),
                    )
                }
                log("parks for $code ok N=${out.size}")
                out
            } catch (e: Exception) {
                log("parks parse error ($code): ${e.javaClass.simpleName}: ${e.message ?: "?"}")
                null
            }
        }

    /**
     * Upload one ADIF document to the authenticated endpoint. [idToken] is a
     * Cognito ID token from [PotaAuth.idToken]; it goes in the authorization
     * header verbatim (POTA's API Gateway expects the raw JWT, not "Bearer …").
     * The body is multipart/form-data mirroring pota.app's "My Log Uploads"
     * page: the `adif` file part plus `reference`/`location`/`callsign` fields
     * (see [buildUploadBody] — without them POTA parses the file but never
     * creates a processing job). [reference] is the single park being credited
     * (e.g. `US-12398`), [location] its POTA location code (e.g. `US-KS`), and
     * [callsign] the activator. Returns the (possibly empty) response body on
     * success, or a failure carrying the HTTP status / error text.
     */
    suspend fun uploadAdif(
        idToken: String,
        filename: String,
        adif: String,
        reference: String,
        location: String,
        callsign: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            var last: Result<String> = Result.failure(IllegalStateException("no upload attempt"))
            for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
                if (attempt > 1) {
                    val backoff = uploadBackoffMs(attempt - 1)
                    log("uploadAdif $filename retry $attempt/$MAX_UPLOAD_ATTEMPTS after ${backoff}ms")
                    delay(backoff)
                }
                last = uploadAdifOnce(idToken, filename, adif, reference, location, callsign)
                if (last.isSuccess || !isRetryableUploadFailure(last.exceptionOrNull())) break
            }
            last
        }

    private fun uploadAdifOnce(
        idToken: String,
        filename: String,
        adif: String,
        reference: String,
        location: String,
        callsign: String,
    ): Result<String> {
        val boundary = "----ft8af${System.nanoTime()}"
        val body = buildUploadBody(boundary, filename, adif, reference, location, callsign)
        val payloadSize = adif.toByteArray(StandardCharsets.UTF_8).size

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$BASE_URL/adif").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = 30_000
                doOutput = true
                // Lowercase names on purpose — see uploadRequestHeaders.
                for ((name, value) in uploadRequestHeaders(idToken, boundary)) {
                    setRequestProperty(name, value)
                }
            }
            // Log exactly what we send so a failure can be compared byte-for-byte
            // against a working website upload: overall body size, the ADIF payload
            // size, and QSO count (each record ends with <EOR>).
            val qsoCount = countOccurrences(adif, "<EOR>")
            log(
                "uploadAdif $filename sending: body=${body.size}B adif=${payloadSize}B qsos=$qsoCount " +
                    "ref=$reference loc=$location call=$callsign",
            )
            conn.outputStream.use { out -> out.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                val trace = uploadTraceSummary(conn.headerFields ?: emptyMap())
                log("uploadAdif $filename -> http $code ${err.take(200)}${if (trace.isNotEmpty()) " [$trace]" else ""}")
                // Persist the exact bytes POTA rejected so the failed log can be pulled
                // and diffed against a website-successful upload of the same activation.
                dumpFailedUpload(filename, adif, code, err)
                return Result.failure(PotaUploadException(code, err))
            }
            val resp = conn.inputStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            log("uploadAdif ok $filename (${payloadSize}B) -> ${resp.take(120)}")
            Result.success(resp)
        } catch (e: CancellationException) {
            // Don't let coroutine cancellation (e.g. the user navigated away) be
            // swallowed into a failed Result and surfaced as an upload-error toast.
            throw e
        } catch (e: Exception) {
            log("uploadAdif $filename failed: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /** Fetch the user's recent upload/processing jobs (authenticated). Raw JSON. */
    suspend fun getJobs(idToken: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$BASE_URL/user/jobs").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = IO_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Authorization", idToken)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                log("getJobs -> http $code")
                return@withContext null
            }
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            log("getJobs failed: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = IO_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                log("GET $url -> http $code")
                return null
            }
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            log("GET $url failed: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPost(url: String, jsonBody: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = IO_TIMEOUT_MS
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                log("POST $url -> http $code")
                return null
            }
            val stream = conn.inputStream ?: return ""
            stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            log("POST $url failed: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    /**
     * On an upload failure, write the exact ADIF bytes we POSTed (plus the HTTP
     * status and error body) to `pota-upload-failed.adi` in the app's external files
     * dir — the same directory as debug.log, so `adb pull` grabs both. This lets a
     * field failure be reproduced and diffed against a website-successful upload of
     * the same activation without needing to re-run the QSO. Best-effort: any IO
     * problem here must never mask the original upload error, so it's swallowed.
     */
    private fun dumpFailedUpload(filename: String, adif: String, code: Int, body: String) {
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val header = "; POTA upload FAILED $ts http=$code file=$filename\n" +
                "; response=${body.take(500)}\n"
            File(dir, "pota-upload-failed.adi").writeText(header + adif)
        } catch (_: Exception) {
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts Pota: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
