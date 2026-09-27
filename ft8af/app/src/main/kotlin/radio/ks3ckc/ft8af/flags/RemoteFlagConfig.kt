package radio.ks3ckc.ft8af.flags

import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A validated remote feature-configuration payload:
 *
 * ```json
 * {
 *   "version": 1,
 *   "generatedAt": "2026-09-27T12:00:00Z",
 *   "expiresAt": "2026-09-27T18:00:00Z",
 *   "flags": { "bandAdvisor": true, "personalPskAnalytics": false }
 * }
 * ```
 *
 * Only entries whose value is a strict boolean are kept; unknown flag names are
 * carried but simply never looked up, and flags missing from [flags] fall back
 * to their build-time default in the repository. A payload that fails
 * validation is rejected as a whole (never partially applied or cached).
 */
data class RemoteFlagConfig(
    val version: Int,
    val generatedAtMs: Long,
    val expiresAtMs: Long,
    val flags: Map<String, Boolean>,
) {
    /** True while the payload's own expiry has not passed. */
    fun isFresh(nowMs: Long): Boolean = nowMs < expiresAtMs

    /** The remote value for [flag], or null when the payload omits it. */
    fun valueFor(flag: FeatureFlag): Boolean? = flags[flag.remoteKey]
}

/** The schema version this client understands. Higher versions are rejected. */
const val REMOTE_FLAG_CONFIG_VERSION = 1

/**
 * Parse + validate a remote feature-configuration JSON document. Returns null
 * for anything malformed: bad JSON, wrong/missing version, unparseable
 * timestamps, expiry not after generation, or a missing `flags` object.
 * Non-boolean flag values are dropped (not coerced); unknown names are ignored
 * by lookup. Never throws.
 */
fun parseRemoteFlagConfig(json: String): RemoteFlagConfig? {
    return try {
        val root = JSONObject(json)
        val version = root.optInt("version", -1)
        if (version < 1 || version > REMOTE_FLAG_CONFIG_VERSION) return null
        val generatedAtMs = parseIso8601UtcMs(root.optString("generatedAt")) ?: return null
        val expiresAtMs = parseIso8601UtcMs(root.optString("expiresAt")) ?: return null
        if (expiresAtMs <= generatedAtMs) return null
        val flagsObj = root.optJSONObject("flags") ?: return null
        val flags = mutableMapOf<String, Boolean>()
        for (key in flagsObj.keys()) {
            val value = flagsObj.opt(key)
            if (value is Boolean) flags[key] = value
        }
        RemoteFlagConfig(version, generatedAtMs, expiresAtMs, flags)
    } catch (_: Exception) {
        null
    }
}

/**
 * Parse an ISO-8601 UTC timestamp ("2026-09-27T12:00:00Z", optionally with
 * fractional seconds) to epoch millis, or null if it doesn't match. Uses
 * SimpleDateFormat rather than java.time because minSdk is 23 (java.time needs
 * API 26 and this project does not enable core-library desugaring).
 */
internal fun parseIso8601UtcMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    // Strip fractional seconds ("...:00.123Z" -> "...:00Z") so one pattern covers both.
    val normalized = raw.replace(Regex("""\.\d+Z$"""), "Z")
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val position = ParsePosition(0)
    val date = format.parse(normalized, position) ?: return null
    // Reject trailing garbage ("2026-09-27T12:00:00Zjunk").
    if (position.index != normalized.length) return null
    return date.time
}
