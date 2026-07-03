package radio.ks3ckc.ft8af.ui.settings

import org.json.JSONObject

/**
 * Pure logic for exporting/importing the app's settings as a JSON backup file
 * (issue #357). The Compose UI and Storage Access Framework wiring in
 * [AdvancedSettings] is a thin wrapper over these functions so that the
 * serialization, filtering, and validation stay unit-testable (Compose/SAF code
 * cannot be exercised in a plain unit test).
 *
 * The backup is the app's `config` key/value table serialized verbatim, wrapped
 * with metadata (format version, app version, creation time). Restoring writes
 * each key back through `DatabaseOpr`.
 */
object SettingsBackup {
    /** Bumped when the on-disk backup schema changes incompatibly. */
    const val FORMAT_VERSION = 1

    /** Marker written to identify our files and reject unrelated JSON on import. */
    const val APP_NAME = "FT8AF"

    /**
     * Config keys holding credentials/secrets. These are excluded from an export
     * unless the user explicitly opts in, so a shared or cloud-stored backup never
     * leaks API keys or passwords by default.
     */
    val SENSITIVE_KEYS: Set<String> = setOf(
        "cloudlogApiKey",
        "qrzApiKey",
        "qrzXmlUsername",
        "qrzXmlPassword",
        "icomUserName",
        "icomPassword",
    )

    /** Metadata + config restored from a backup file. */
    data class ParsedBackup(
        val formatVersion: Int,
        val appVersion: String,
        val createdAt: String,
        val config: Map<String, String>,
    )

    /**
     * Drop sensitive keys from [config] unless [includeSensitive]. Preserves order
     * so exports are stable when the caller passes an ordered map.
     */
    internal fun filterConfig(
        config: Map<String, String>,
        includeSensitive: Boolean,
    ): Map<String, String> =
        config.filterKeys { key -> includeSensitive || key !in SENSITIVE_KEYS }

    /**
     * Serialize [config] to the backup JSON text. Non-sensitive keys are always
     * included; sensitive keys only when [includeSensitive]. Keys are sorted so the
     * output is deterministic and diff-friendly across exports.
     */
    fun buildBackupJson(
        config: Map<String, String>,
        includeSensitive: Boolean,
        appVersion: String,
        createdAt: String,
    ): String {
        val filtered = filterConfig(config, includeSensitive)
        val configJson = JSONObject()
        for (key in filtered.keys.sorted()) {
            configJson.put(key, filtered[key])
        }
        val root = JSONObject()
        root.put("appName", APP_NAME)
        root.put("formatVersion", FORMAT_VERSION)
        root.put("appVersion", appVersion)
        root.put("createdAt", createdAt)
        root.put("includesSensitive", includeSensitive)
        root.put("config", configJson)
        return root.toString(2)
    }

    /**
     * Parse and validate backup JSON. Throws [IllegalArgumentException] with a
     * user-facing message when the text is not a recognizable FT8AF backup, or when
     * its format version is newer than this build understands.
     */
    fun parseBackupJson(text: String): ParsedBackup {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid backup file (invalid JSON).")
        }
        // The appName marker is what actually distinguishes our backups from any
        // other JSON that happens to carry formatVersion+config keys — reject
        // anything not stamped by us before looking at versions.
        if (root.optString("appName") != APP_NAME) {
            throw IllegalArgumentException("Not a valid FT8AF settings backup.")
        }
        if (!root.has("config") || !root.has("formatVersion")) {
            throw IllegalArgumentException("Not a valid FT8AF settings backup.")
        }
        val formatVersion = root.optInt("formatVersion", -1)
        if (formatVersion <= 0) {
            throw IllegalArgumentException("Not a valid FT8AF settings backup.")
        }
        if (formatVersion > FORMAT_VERSION) {
            throw IllegalArgumentException(
                "This backup was created by a newer version of FT8AF " +
                    "(format $formatVersion). Please update the app to import it.",
            )
        }
        val configJson = root.optJSONObject("config")
            ?: throw IllegalArgumentException("Backup contains no settings to restore.")
        val config = LinkedHashMap<String, String>()
        val keys = configJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            config[key] = configJson.optString(key, "")
        }
        if (config.isEmpty()) {
            throw IllegalArgumentException("Backup contains no settings to restore.")
        }
        return ParsedBackup(
            formatVersion = formatVersion,
            appVersion = root.optString("appVersion", ""),
            createdAt = root.optString("createdAt", ""),
            config = config,
        )
    }

    /**
     * Suggested export file name, e.g. "ft8af-settings-2026-07-03-v1.2.3.json".
     * [date] is an already-formatted yyyy-MM-dd string; [appVersion] is sanitized to
     * keep the name filesystem-safe.
     */
    fun defaultFileName(date: String, appVersion: String): String {
        val safeVersion = appVersion.ifBlank { "unknown" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "ft8af-settings-$date-v$safeVersion.json"
    }
}
