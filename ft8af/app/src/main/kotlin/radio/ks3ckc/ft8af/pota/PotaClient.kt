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
 * would fail again identically. POTA returns 502 when its API Gateway can't reach
 * the upstream, which is exactly the failure observed in the field.
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
     * Cognito ID token from [PotaAuth.idToken]; it goes in the Authorization
     * header verbatim (POTA's API Gateway expects the raw JWT, not "Bearer …").
     * The body is multipart/form-data with a single `adif` part, matching the
     * pota.app website uploader. Returns the (possibly empty) response body on
     * success, or a failure carrying the HTTP status / error text.
     */
    suspend fun uploadAdif(idToken: String, filename: String, adif: String): Result<String> =
        withContext(Dispatchers.IO) {
            var last: Result<String> = Result.failure(IllegalStateException("no upload attempt"))
            for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
                if (attempt > 1) {
                    val backoff = uploadBackoffMs(attempt - 1)
                    log("uploadAdif $filename retry $attempt/$MAX_UPLOAD_ATTEMPTS after ${backoff}ms")
                    delay(backoff)
                }
                last = uploadAdifOnce(idToken, filename, adif)
                if (last.isSuccess || !isRetryableUploadFailure(last.exceptionOrNull())) break
            }
            last
        }

    private fun uploadAdifOnce(idToken: String, filename: String, adif: String): Result<String> {
        val boundary = "----ft8af${System.nanoTime()}"
        val preamble = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"adif\"; filename=\"").append(filename).append("\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val epilogue = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val payload = adif.toByteArray(StandardCharsets.UTF_8)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$BASE_URL/adif").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Authorization", idToken)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { out ->
                out.write(preamble)
                out.write(payload)
                out.write(epilogue)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                log("uploadAdif $filename -> http $code ${err.take(200)}")
                return Result.failure(PotaUploadException(code, err))
            }
            val resp = conn.inputStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            log("uploadAdif ok $filename (${payload.size}B) -> ${resp.take(120)}")
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
