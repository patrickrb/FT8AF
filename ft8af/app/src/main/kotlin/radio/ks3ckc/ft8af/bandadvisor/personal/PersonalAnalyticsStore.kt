package radio.ks3ckc.ft8af.bandadvisor.personal

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local rolling history of per-band personal performance, used only to build
 * the operator's OWN baseline ("above your normal 20m range"). Bounded to
 * [MAX_SESSIONS] entries per band; cleared entirely by the privacy control in
 * Band Advisor settings. Nothing here ever leaves the device.
 */
object PersonalAnalyticsStore {
    private const val PREFS_NAME = "ft8af_personal_psk"
    private const val KEY_HISTORY = "band_history"
    internal const val MAX_SESSIONS = 10

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Historical max-distance samples (km) for [band], oldest first. */
    fun historyFor(context: Context, band: String): List<Int> =
        parseHistory(prefs(context).getString(KEY_HISTORY, null))[band] ?: emptyList()

    /**
     * Record a session sample for [band]. Only sessions with a meaningful
     * number of reports should be recorded (callers gate on sample size) so
     * a single stray spot doesn't drag the baseline.
     */
    fun record(context: Context, band: String, maxDistanceKm: Int) {
        val all = parseHistory(prefs(context).getString(KEY_HISTORY, null)).toMutableMap()
        val list = (all[band] ?: emptyList()) + maxDistanceKm
        all[band] = list.takeLast(MAX_SESSIONS)
        prefs(context).edit().putString(KEY_HISTORY, encodeHistory(all)).apply()
    }

    /** Wipe all locally cached personal analytics. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    internal fun parseHistory(json: String?): Map<String, List<Int>> {
        json ?: return emptyMap()
        return try {
            val root = JSONObject(json)
            buildMap {
                for (band in root.keys()) {
                    val arr = root.optJSONArray(band) ?: continue
                    put(band, (0 until arr.length()).map { arr.optInt(it) })
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    internal fun encodeHistory(history: Map<String, List<Int>>): String {
        val root = JSONObject()
        for ((band, values) in history) {
            root.put(band, JSONArray(values))
        }
        return root.toString()
    }
}
