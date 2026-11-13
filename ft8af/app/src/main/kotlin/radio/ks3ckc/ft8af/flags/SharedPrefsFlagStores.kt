package radio.ks3ckc.ft8af.flags

import android.content.Context
import android.content.SharedPreferences

// SharedPreferences (not the SQLite config table) so flags resolve
// synchronously at startup, before DatabaseOpr's async hydration — the same
// reason ThemePreference lives in prefs. See theme/ThemePreference.kt.
private const val PREFS_NAME = "ft8af_feature_flags"
private const val KEY_OVERRIDE_PREFIX = "override_"
private const val KEY_REMOTE_JSON = "remote_config_json"
private const val KEY_REMOTE_FETCHED_AT = "remote_config_fetched_at"
private const val KEY_REMOTE_LAST_ATTEMPT = "remote_config_last_attempt"

private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

/** Developer tri-state overrides, persisted per flag. Debug builds only. */
class SharedPrefsFlagOverrideStore(context: Context) : FlagOverrideStore {
    private val prefs = prefs(context.applicationContext)

    override fun overrideFor(flag: FeatureFlag): FlagOverride =
        FlagOverride.fromId(prefs.getString(KEY_OVERRIDE_PREFIX + flag.name, null))

    override fun setOverride(flag: FeatureFlag, override: FlagOverride) {
        prefs.edit().apply {
            if (override == FlagOverride.DEFAULT) {
                remove(KEY_OVERRIDE_PREFIX + flag.name)
            } else {
                putString(KEY_OVERRIDE_PREFIX + flag.name, override.id)
            }
        }.apply()
    }
}

/**
 * Caches the raw JSON of the last payload that passed validation, re-parsing
 * on read (cheap, and keeps a single source of truth for the schema).
 */
class SharedPrefsRemoteFlagConfigStore(context: Context) : RemoteFlagConfigStore {
    private val prefs = prefs(context.applicationContext)

    override fun cached(): RemoteFlagConfig? {
        val json = prefs.getString(KEY_REMOTE_JSON, null) ?: return null
        return parseRemoteFlagConfig(json)
    }

    override fun save(rawJson: String, fetchedAtMs: Long) {
        // Never cache an invalid payload — a corrupt save would otherwise shadow
        // a previously good one until the next successful fetch.
        if (parseRemoteFlagConfig(rawJson) == null) return
        prefs.edit()
            .putString(KEY_REMOTE_JSON, rawJson)
            .putLong(KEY_REMOTE_FETCHED_AT, fetchedAtMs)
            .apply()
    }

    override fun lastAttemptMs(): Long = prefs.getLong(KEY_REMOTE_LAST_ATTEMPT, 0L)

    override fun recordAttempt(nowMs: Long) {
        prefs.edit().putLong(KEY_REMOTE_LAST_ATTEMPT, nowMs).apply()
    }
}
