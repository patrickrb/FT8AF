package radio.ks3ckc.ft8af.ui.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.k1af.ft8af.R

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
 * Invokes [plot] once per complete `(lon, lat)` vertex of a flattened polygon
 * [ring] (`[lon0, lat0, lon1, lat1, ...]`), passing `first == true` on the
 * opening vertex so callers can `moveTo` it and `lineTo` the rest. Returns
 * `true` when at least one vertex was plotted (i.e. the caller should
 * `close()` the path), or `false` when the ring has fewer than [minVertices]
 * complete vertices and was skipped entirely.
 *
 * The vertex count is bounded to `ring.size / 2`, so a trailing unpaired
 * coordinate in a malformed odd-length ring is ignored rather than read out of
 * bounds. This centralizes the safe bound already used by [pointInRing] and
 * `CarMapSurfaceRenderer.buildPaths`, replacing the `while (i < ring.size)`
 * walks (which read `ring[i + 1]` one past the end of an odd-length ring) in
 * the equirectangular / azimuthal / POTA / QSO-path land renderers.
 *
 * `inline` keeps the per-vertex [plot] call allocation-free on the draw hot
 * path (this runs for every land ring, every frame).
 */
internal inline fun forEachRingVertex(
    ring: FloatArray,
    minVertices: Int = 3,
    plot: (lon: Float, lat: Float, first: Boolean) -> Unit,
): Boolean {
    val vertices = ring.size / 2
    // Explicit empty guard so the contract holds even for minVertices <= 0: an
    // empty ring must return false (nothing plotted, caller must not close()).
    if (vertices == 0 || vertices < minVertices) return false
    for (v in 0 until vertices) {
        plot(ring[v * 2], ring[v * 2 + 1], v == 0)
    }
    return true
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
 * Lazily loads country boundary outlines from res/raw/world_countries.json (a
 * GeoJSON FeatureCollection of world admin-0 countries), caching the parsed rings.
 * Used to draw national borders over the dissolved land layer.
 */
internal object WorldCountryOutlines {
    @Volatile private var cached: List<FloatArray>? = null

    fun load(context: Context): List<FloatArray> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.resources.openRawResource(R.raw.world_countries)
                .bufferedReader().use { it.readText() }
            return parseGeoJsonRings(text).also { cached = it }
        }
    }
}

/** A US state's two-letter label and the map point to anchor it at (lon/lat, degrees). */
internal data class StateLabel(val abbr: String, val lon: Float, val lat: Float)

/** Full state name (as it appears in us_states.json) → USPS two-letter abbreviation. */
private val US_STATE_ABBR: Map<String, String> = mapOf(
    "Alabama" to "AL", "Alaska" to "AK", "Arizona" to "AZ", "Arkansas" to "AR",
    "California" to "CA", "Colorado" to "CO", "Connecticut" to "CT", "Delaware" to "DE",
    "District of Columbia" to "DC", "Florida" to "FL", "Georgia" to "GA", "Hawaii" to "HI",
    "Idaho" to "ID", "Illinois" to "IL", "Indiana" to "IN", "Iowa" to "IA",
    "Kansas" to "KS", "Kentucky" to "KY", "Louisiana" to "LA", "Maine" to "ME",
    "Maryland" to "MD", "Massachusetts" to "MA", "Michigan" to "MI", "Minnesota" to "MN",
    "Mississippi" to "MS", "Missouri" to "MO", "Montana" to "MT", "Nebraska" to "NE",
    "Nevada" to "NV", "New Hampshire" to "NH", "New Jersey" to "NJ", "New Mexico" to "NM",
    "New York" to "NY", "North Carolina" to "NC", "North Dakota" to "ND", "Ohio" to "OH",
    "Oklahoma" to "OK", "Oregon" to "OR", "Pennsylvania" to "PA", "Rhode Island" to "RI",
    "South Carolina" to "SC", "South Dakota" to "SD", "Tennessee" to "TN", "Texas" to "TX",
    "Utah" to "UT", "Vermont" to "VT", "Virginia" to "VA", "Washington" to "WA",
    "West Virginia" to "WV", "Wisconsin" to "WI", "Wyoming" to "WY", "Puerto Rico" to "PR",
)

/**
 * Parses the same us_states.json FeatureCollection into a label per state: the
 * USPS abbreviation plus an anchor point at the area centroid of the state's
 * largest polygon (so the label lands inside the mainland body, not out in a
 * distant island for multi-part states). Features whose name isn't in
 * [US_STATE_ABBR] are skipped.
 */
internal fun parseStateLabels(text: String): List<StateLabel> {
    val features = JSONObject(text).getJSONArray("features")
    val out = ArrayList<StateLabel>(features.length())
    for (i in 0 until features.length()) {
        val feature = features.getJSONObject(i)
        val name = feature.optJSONObject("properties")?.optString("name").orEmpty()
        val abbr = US_STATE_ABBR[name] ?: continue
        val ring = largestOuterRing(feature.getJSONObject("geometry")) ?: continue
        val centroid = ringCentroid(ring) ?: continue
        out.add(StateLabel(abbr, centroid.first, centroid.second))
    }
    return out
}

/** The outer ring with the greatest absolute area (Polygon → its only outer ring). */
private fun largestOuterRing(geom: JSONObject): JSONArray? = when (geom.getString("type")) {
    "Polygon" -> geom.getJSONArray("coordinates").getJSONArray(0)
    "MultiPolygon" -> {
        val polys = geom.getJSONArray("coordinates")
        var best: JSONArray? = null
        var bestArea = -1.0
        for (p in 0 until polys.length()) {
            val ring = polys.getJSONArray(p).getJSONArray(0)
            val area = kotlin.math.abs(ringSignedArea(ring))
            if (area > bestArea) {
                bestArea = area
                best = ring
            }
        }
        best
    }
    else -> null
}

/** Shoelace signed area of a lon/lat ring (degrees²); sign encodes winding. */
private fun ringSignedArea(ring: JSONArray): Double {
    var area = 0.0
    val n = ring.length()
    for (i in 0 until n) {
        val a = ring.getJSONArray(i)
        val b = ring.getJSONArray((i + 1) % n)
        area += a.getDouble(0) * b.getDouble(1) - b.getDouble(0) * a.getDouble(1)
    }
    return area / 2.0
}

/** Polygon area centroid; falls back to the vertex mean for degenerate rings. */
private fun ringCentroid(ring: JSONArray): Pair<Float, Float>? {
    val n = ring.length()
    if (n == 0) return null
    var cx = 0.0
    var cy = 0.0
    var doubleArea = 0.0
    for (i in 0 until n) {
        val p0 = ring.getJSONArray(i)
        val p1 = ring.getJSONArray((i + 1) % n)
        val x0 = p0.getDouble(0)
        val y0 = p0.getDouble(1)
        val x1 = p1.getDouble(0)
        val y1 = p1.getDouble(1)
        val cross = x0 * y1 - x1 * y0
        doubleArea += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    }
    if (kotlin.math.abs(doubleArea) < 1e-9) {
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) {
            val p = ring.getJSONArray(i)
            mx += p.getDouble(0)
            my += p.getDouble(1)
        }
        return (mx / n).toFloat() to (my / n).toFloat()
    }
    val factor = 3.0 * doubleArea // 6 * area, with area = doubleArea/2
    return (cx / factor).toFloat() to (cy / factor).toFloat()
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

/**
 * Lazily loads US state labels (abbreviation + centroid anchor) from the same
 * res/raw/us_states.json, caching the parsed list for the process lifetime.
 */
internal object UsStateLabels {
    @Volatile private var cached: List<StateLabel>? = null

    fun load(context: Context): List<StateLabel> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.resources.openRawResource(R.raw.us_states)
                .bufferedReader().use { it.readText() }
            return parseStateLabels(text).also { cached = it }
        }
    }
}

/** A named region and its polygon outer rings (lon/lat interleaved), for lookups. */
internal data class NamedRegion(val name: String, val rings: List<FloatArray>)

/** Parses a GeoJSON FeatureCollection into named regions, keeping each polygon's outer ring. */
internal fun parseNamedRegions(text: String): List<NamedRegion> {
    val features = JSONObject(text).getJSONArray("features")
    val out = ArrayList<NamedRegion>(features.length())
    for (i in 0 until features.length()) {
        val feature = features.getJSONObject(i)
        val name = feature.optJSONObject("properties")?.optString("name").orEmpty()
        if (name.isEmpty()) continue
        val geom = feature.getJSONObject("geometry")
        val rings = ArrayList<FloatArray>()
        when (geom.getString("type")) {
            "Polygon" -> rings.add(ringToFlat(geom.getJSONArray("coordinates").getJSONArray(0)))
            "MultiPolygon" -> {
                val polys = geom.getJSONArray("coordinates")
                for (p in 0 until polys.length()) {
                    rings.add(ringToFlat(polys.getJSONArray(p).getJSONArray(0)))
                }
            }
        }
        if (rings.isNotEmpty()) out.add(NamedRegion(name, rings))
    }
    return out
}

/** Ray-casting point-in-polygon on a flat [lon, lat, ...] ring. */
internal fun pointInRing(ring: FloatArray, lon: Float, lat: Float): Boolean {
    val n = ring.size / 2
    if (n < 3) return false
    var inside = false
    var j = n - 1
    for (i in 0 until n) {
        val xi = ring[2 * i]
        val yi = ring[2 * i + 1]
        val xj = ring[2 * j]
        val yj = ring[2 * j + 1]
        if (((yi > lat) != (yj > lat)) &&
            (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/** Name of the first region whose polygon contains (lon, lat), or null. */
internal fun regionNameAt(regions: List<NamedRegion>, lon: Float, lat: Float): String? {
    for (region in regions) {
        for (ring in region.rings) {
            if (pointInRing(ring, lon, lat)) return region.name
        }
    }
    return null
}

/**
 * Resolves a lat/lon to a US state name via point-in-polygon over us_states.json.
 * Regions are parsed and cached on first use. Returns null outside the US.
 */
internal object UsStateLocator {
    @Volatile private var cached: List<NamedRegion>? = null

    private fun regions(context: Context): List<NamedRegion> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.resources.openRawResource(R.raw.us_states)
                .bufferedReader().use { it.readText() }
            return parseNamedRegions(text).also { cached = it }
        }
    }

    fun stateAt(context: Context, lat: Double, lon: Double): String? =
        regionNameAt(regions(context), lon.toFloat(), lat.toFloat())
}

