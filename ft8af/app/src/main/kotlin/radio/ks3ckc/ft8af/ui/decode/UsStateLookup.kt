package radio.ks3ckc.ft8af.ui.decode

import android.content.Context
import org.json.JSONObject

internal object UsStateLookup {
    @Volatile private var map: Map<String, String>? = null

    fun stateFromGrid(context: Context, grid: String?): String? {
        if (grid.isNullOrEmpty() || grid.length < 4) return null
        val m = map ?: synchronized(this) {
            map ?: load(context).also { map = it }
        }
        return m[grid.take(4).uppercase()]
    }

    private fun load(context: Context): Map<String, String> {
        return try {
            val json = context.assets.open("us_grid_states.json")
                .bufferedReader()
                .use { it.readText() }
            val obj = JSONObject(json)
            val out = HashMap<String, String>(obj.length() * 2)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getString(k)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
