package radio.ks3ckc.ft8af.bandadvisor.alerts

import org.json.JSONObject
import radio.ks3ckc.ft8af.flags.parseIso8601UtcMs
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Client for the lightweight `/v1/conditions` endpoint the alert worker polls.
 * The backend does all expensive aggregation (VOACAP + PSK Reporter); the
 * phone downloads one small pre-scored JSON document per check.
 */
object ConditionsClient {
    const val DEFAULT_BASE_URL = "https://ft8af.app/api/band-advisor"
    private const val IO_TIMEOUT_MS = 15_000

    /** Null on any transport/HTTP/parse failure — the worker just skips a round. */
    fun fetch(
        grid: String,
        baseUrl: String = DEFAULT_BASE_URL,
        nowMs: Long = System.currentTimeMillis(),
    ): List<BandConditionSnapshot>? {
        var conn: HttpURLConnection? = null
        val body = try {
            val url = "$baseUrl/v1/conditions?grid=" +
                URLEncoder.encode(grid, StandardCharsets.UTF_8.name())
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = IO_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
        return parseConditions(body, nowMs)
    }

    /**
     * Parse the conditions document. Malformed entries are dropped; a document
     * that yields nothing valid returns null so callers treat it as a failed
     * fetch rather than "no openings".
     */
    internal fun parseConditions(json: String, nowMs: Long): List<BandConditionSnapshot>? {
        return try {
            val root = JSONObject(json)
            val generatedAtMs = parseIso8601UtcMs(root.optString("generatedAt")) ?: nowMs
            val ageMs = (nowMs - generatedAtMs).coerceAtLeast(0)
            val array = root.optJSONArray("bands") ?: return null
            val out = mutableListOf<BandConditionSnapshot>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val band = obj.optString("band")
                val score = obj.optDouble("score", Double.NaN)
                val confidence = obj.optDouble("confidence", Double.NaN)
                if (band.isBlank() || !score.isFinite() || !confidence.isFinite()) continue
                val regionsArray = obj.optJSONArray("activeRegions")
                val regions = if (regionsArray == null) {
                    emptyList()
                } else {
                    (0 until regionsArray.length()).mapNotNull { j ->
                        (regionsArray.opt(j) as? String)?.takeIf { it.isNotBlank() }
                    }
                }
                out.add(
                    BandConditionSnapshot(
                        band = band,
                        score = score.coerceIn(0.0, 1.0),
                        confidence = confidence.coerceIn(0.0, 1.0),
                        observationCount = obj.optInt("observationCount", 0).coerceAtLeast(0),
                        activityRatio = obj.optDouble("activityRatio", 0.0).coerceAtLeast(0.0),
                        activeRegions = regions,
                        voacapSupported = obj.optBoolean("voacapSupported", false),
                        unusualOpening = obj.optBoolean("unusualOpening", false),
                        personalImprovement =
                            if (obj.has("personalImprovement")) {
                                obj.optBoolean("personalImprovement")
                            } else {
                                null
                            },
                        dataAgeMs = ageMs,
                    ),
                )
            }
            out.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
