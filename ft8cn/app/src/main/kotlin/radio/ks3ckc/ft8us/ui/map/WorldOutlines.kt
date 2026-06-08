package radio.ks3ckc.ft8us.ui.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.bg7yoz.ft8cn.R

/**
 * Parse a GeoJSON FeatureCollection of Polygon/MultiPolygon features into a flat
 * list of polygon outer-boundary rings. Each ring is a FloatArray of interleaved
 * [lon, lat, lon, lat, ...].
 *
 * Holes (inner rings) are ignored — negligible for a low-resolution basemap.
 * GeoJSON coordinate order is [lon, lat]; we preserve that here.
 */
internal fun parseGeoJsonRings(text: String): List<FloatArray> {
    val features = JSONObject(text).getJSONArray("features")
    val rings = ArrayList<FloatArray>(features.length())
    for (i in 0 until features.length()) {
        val geom = features.getJSONObject(i).getJSONObject("geometry")
        when (geom.getString("type")) {
            "Polygon" -> {
                rings.add(ringToFlat(geom.getJSONArray("coordinates").getJSONArray(0)))
            }
            "MultiPolygon" -> {
                val polys = geom.getJSONArray("coordinates")
                for (p in 0 until polys.length()) {
                    rings.add(ringToFlat(polys.getJSONArray(p).getJSONArray(0)))
                }
            }
        }
    }
    return rings
}

private fun ringToFlat(ring: JSONArray): FloatArray {
    val n = ring.length()
    val out = FloatArray(n * 2)
    for (i in 0 until n) {
        val pt = ring.getJSONArray(i)
        out[i * 2] = pt.getDouble(0).toFloat()      // lon
        out[i * 2 + 1] = pt.getDouble(1).toFloat()  // lat
    }
    return out
}

/**
 * Lazily loads Natural Earth 110m land outlines from res/raw/world_land.json,
 * caching the parsed rings for the process lifetime.
 */
internal object WorldOutlines {
    @Volatile private var cached: List<FloatArray>? = null

    fun load(context: Context): List<FloatArray> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.resources.openRawResource(R.raw.world_land)
                .bufferedReader().use { it.readText() }
            return parseGeoJsonRings(text).also { cached = it }
        }
    }
}

/**
 * Lazily loads US state boundary outlines from res/raw/us_states.json (a GeoJSON
 * FeatureCollection of the 50 states + DC), caching the parsed rings. Used to
 * draw state borders over land on the QSO path map.
 */
internal object UsStateOutlines {
    @Volatile private var cached: List<FloatArray>? = null

    fun load(context: Context): List<FloatArray> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.resources.openRawResource(R.raw.us_states)
                .bufferedReader().use { it.readText() }
            return parseGeoJsonRings(text).also { cached = it }
        }
    }
}
