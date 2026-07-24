package radio.ks3ckc.ft8af.ui.decode

import com.k1af.ft8af.maidenhead.MaidenheadGrid
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Great-circle beam heading from the operator's grid to a remote station.
 *
 * [shortPathDeg] is the initial great-circle bearing the operator would point a
 * directional antenna to take the short way round; [longPathDeg] is its
 * reciprocal (the long way, over the antipode) — a genuinely different heading
 * DX chasers use when the short path is closed. Both are integer degrees in
 * `0..359`.
 *
 * The trig lives in [greatCircleBearing]/[longPathBearing] as pure functions (no
 * Android types) so they unit-test without Robolectric; [computeBeamHeadings]
 * decodes the grids through [MaidenheadGrid] and therefore needs it.
 */
internal data class BeamHeadings(val shortPathDeg: Int, val longPathDeg: Int)

/**
 * Initial great-circle bearing (degrees, `0..360`) from point 1 to point 2 using
 * the standard forward-azimuth formula. Pure math — no Android types.
 */
internal fun greatCircleBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Long-path heading is the reciprocal of the short-path initial bearing. */
internal fun longPathBearing(shortPathDeg: Double): Double = (shortPathDeg + 180.0) % 360.0

/** Round a bearing to whole degrees in `0..359` (360 wraps back to 0). */
internal fun normalizeHeadingDeg(deg: Double): Int = (Math.round(deg).toInt() % 360 + 360) % 360

/**
 * Short- and long-path beam headings from the operator's grid to the remote
 * grid, or null when either grid is missing/unparseable or the two points are
 * identical (a bearing to yourself is undefined). Delegates to
 * [MaidenheadGrid.gridToLatLng] (Play-Services `LatLng`), so callers and tests
 * that reach this need Robolectric.
 */
internal fun computeBeamHeadings(myGrid: String?, theirGrid: String?): BeamHeadings? {
    if (myGrid.isNullOrEmpty() || theirGrid.isNullOrEmpty()) return null
    return try {
        val me = MaidenheadGrid.gridToLatLng(myGrid) ?: return null
        val them = MaidenheadGrid.gridToLatLng(theirGrid) ?: return null
        if (me.latitude == them.latitude && me.longitude == them.longitude) return null
        val sp = greatCircleBearing(me.latitude, me.longitude, them.latitude, them.longitude)
        BeamHeadings(
            shortPathDeg = normalizeHeadingDeg(sp),
            longPathDeg = normalizeHeadingDeg(longPathBearing(sp)),
        )
    } catch (_: Exception) {
        null
    }
}

/** Format a whole-degree heading as e.g. `47°`. */
internal fun formatHeading(deg: Int): String = "$deg°"
