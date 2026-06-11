package radio.ks3ckc.ft8us.pota

import android.util.Log
import com.bg7yoz.ft8cn.GeneralVariables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import radio.ks3ckc.ft8us.pota.model.PotaPark
import radio.ks3ckc.ft8us.pota.model.PotaSpot
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
 * status. Carries the status so the UI can tell a server-side rejection (5xx —
 * usually a bad park ref or unregistered callsign) from a client/auth problem.
 */
class PotaUploadException(val httpCode: Int, val body: String) :
    Exception("HTTP $httpCode${if (body.isNotBlank()) ": ${body.take(160)}" else ""}")

/**
 * Talks to pota.app's read+write endpoints. Mirrors [radio.ks3ckc.ft8us.pskreporter.PskReporterClient]:
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
            )
        } catch (e: Exception) {
            log("park parse error: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
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
            val boundary = "----ft8af${System.nanoTime()}"
            val preamble = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"adif\"; filename=\"").append(filename).append("\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }.toByteArray(StandardCharsets.UTF_8)
            val epilogue = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
            val payload = adif.toByteArray(StandardCharsets.UTF_8)

            var conn: HttpURLConnection? = null
            try {
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
                    return@withContext Result.failure(PotaUploadException(code, err))
                }
                val resp = conn.inputStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                log("uploadAdif ok $filename (${payload.size}B) -> ${resp.take(120)}")
                Result.success(resp)
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
