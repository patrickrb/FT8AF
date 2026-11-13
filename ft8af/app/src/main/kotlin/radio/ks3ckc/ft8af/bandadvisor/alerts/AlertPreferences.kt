package radio.ks3ckc.ft8af.bandadvisor.alerts

import android.content.Context
import android.content.SharedPreferences

/** The opt-in alert categories. Every one defaults OFF. */
enum class AlertType(val id: String) {
    /** Any band opening near the operator's grid. */
    BAND_OPENING("band_opening"),

    /** One of the watched bands becoming active. */
    BAND_ACTIVE("band_active"),

    /** One of the watched regions becoming reachable. */
    REGION_REACHABLE("region_reachable"),

    /** The operator's own signal outperforming its baseline. */
    PERSONAL_IMPROVEMENT("personal_improvement"),

    /** Observed conditions substantially exceeding the VOACAP baseline. */
    UNUSUAL_OPENING("unusual_opening"),
}

/**
 * Immutable snapshot of the alert preferences, separated from storage so the
 * decision policy is pure and unit-testable.
 */
data class AlertPrefs(
    /** Master switch. Everything below is moot while false. */
    val enabled: Boolean = false,
    val enabledTypes: Set<AlertType> = emptySet(),
    /** Local hour [0..23] quiet window start; equal start/end = no quiet hours. */
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
    /** Bands to watch for BAND_ACTIVE ("20m" names); empty = all bands. */
    val watchedBands: Set<String> = emptySet(),
    /** Regions to watch for REGION_REACHABLE (server region names); empty = none. */
    val watchedRegions: Set<String> = emptySet(),
)

/** SharedPreferences persistence for [AlertPrefs]. */
object AlertPreferencesStore {
    private const val PREFS_NAME = "ft8af_prop_alerts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TYPES = "types"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"
    private const val KEY_BANDS = "bands"
    private const val KEY_REGIONS = "regions"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): AlertPrefs {
        val p = prefs(context)
        return AlertPrefs(
            enabled = p.getBoolean(KEY_ENABLED, false),
            enabledTypes = p.getStringSet(KEY_TYPES, emptySet())!!
                .mapNotNull { id -> AlertType.entries.firstOrNull { it.id == id } }
                .toSet(),
            quietStartHour = p.getInt(KEY_QUIET_START, 22).coerceIn(0, 23),
            quietEndHour = p.getInt(KEY_QUIET_END, 7).coerceIn(0, 23),
            watchedBands = p.getStringSet(KEY_BANDS, emptySet())!!.toSet(),
            watchedRegions = p.getStringSet(KEY_REGIONS, emptySet())!!.toSet(),
        )
    }

    fun save(context: Context, value: AlertPrefs) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putStringSet(KEY_TYPES, value.enabledTypes.map { it.id }.toSet())
            .putInt(KEY_QUIET_START, value.quietStartHour)
            .putInt(KEY_QUIET_END, value.quietEndHour)
            .putStringSet(KEY_BANDS, value.watchedBands)
            .putStringSet(KEY_REGIONS, value.watchedRegions)
            .apply()
    }
}
