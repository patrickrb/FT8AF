package radio.ks3ckc.ft8af.bandadvisor.alerts

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persisted delivery history (opening identity → delivered-at epoch ms) so
 * dedup and cooldowns survive process restarts. Bounded by [pruneHistory]'s
 * 48-hour retention on every write.
 */
object AlertHistoryStore {
    private const val PREFS_NAME = "ft8af_prop_alert_history"
    private const val KEY_DELIVERED = "delivered"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun deliveredAt(context: Context): Map<String, Long> =
        parse(prefs(context).getString(KEY_DELIVERED, null))

    fun recordDelivery(context: Context, identity: String, nowMs: Long) {
        val pruned = pruneHistory(deliveredAt(context), nowMs) + (identity to nowMs)
        prefs(context).edit().putString(KEY_DELIVERED, encode(pruned)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    internal fun parse(json: String?): Map<String, Long> {
        json ?: return emptyMap()
        return try {
            val root = JSONObject(json)
            buildMap {
                for (key in root.keys()) {
                    put(key, root.optLong(key))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    internal fun encode(map: Map<String, Long>): String {
        val root = JSONObject()
        for ((k, v) in map) root.put(k, v)
        return root.toString()
    }
}
