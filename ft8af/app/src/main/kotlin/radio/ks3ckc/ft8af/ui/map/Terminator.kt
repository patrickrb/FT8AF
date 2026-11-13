package radio.ks3ckc.ft8af.ui.map

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Day/night terminator ("gray line") geometry for the world map.
 *
 * The gray line — the moving band of sunrise/sunset sweeping across the Earth —
 * is where HF propagation is briefly enhanced, so operators deliberately hunt
 * along it. This file computes where the Sun is and where the terminator falls
 * as pure functions (no Android/Compose types), so the map Composable stays a
 * thin wrapper and the math is unit-tested directly.
 *
 * All angles are degrees unless noted; longitudes are east-positive in
 * [-180, 180]; times are UTC epoch milliseconds.
 */

/** The point on Earth directly beneath the Sun (Sun at the zenith). */
internal data class SubsolarPoint(val lat: Double, val lon: Double)

/**
 * Sub-solar point for a UTC instant, via the NOAA low-precision solar-position
 * algorithm. Accurate to a few tenths of a degree — far finer than the map's
 * grid, and plenty for a gray-line overlay that only has to look right.
 */
internal fun subsolarPoint(utcMillis: Long): SubsolarPoint {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = utcMillis
    val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
    val hoursUtc = cal.get(Calendar.HOUR_OF_DAY) +
        cal.get(Calendar.MINUTE) / 60.0 +
        cal.get(Calendar.SECOND) / 3600.0

    // Fractional-year angle γ (radians): NOAA uses (day - 1) + (hour - 12) / 24.
    val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1 + (hoursUtc - 12.0) / 24.0)

    // Equation of time (minutes) — the Sun's longitude wobble vs. clock time.
    val eqTime = 229.18 * (
        0.000075 +
            0.001868 * cos(gamma) -
            0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) -
            0.040849 * sin(2 * gamma)
    )
    // Solar declination (radians) = sub-solar latitude.
    val declRad = 0.006918 -
        0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
        0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
        0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

    val subsolarLat = Math.toDegrees(declRad)
    // The Sun is overhead at true solar noon; each UTC hour past noon moves that
    // meridian 15° west. The equation of time nudges it off clock noon.
    val subsolarLon = normalizeLon(-15.0 * (hoursUtc + eqTime / 60.0 - 12.0))
    return SubsolarPoint(subsolarLat, subsolarLon)
}

/** Wrap a longitude into [-180, 180]. */
internal fun normalizeLon(lon: Double): Double {
    var l = lon % 360.0
    if (l > 180.0) l -= 360.0
    if (l < -180.0) l += 360.0
    return l
}

/**
 * Solar elevation (degrees) at [lat]/[lon] given the [subsolar] point: the Sun's
 * angle above the horizon. Positive = daytime, negative = night, ≈0 = on the
 * terminator (the gray line itself).
 */
internal fun solarElevationDeg(lat: Double, lon: Double, subsolar: SubsolarPoint): Double {
    val phi = Math.toRadians(lat)
    val decl = Math.toRadians(subsolar.lat)
    val hourAngle = Math.toRadians(lon - subsolar.lon)
    val sinElev = sin(phi) * sin(decl) + cos(phi) * cos(decl) * cos(hourAngle)
    return Math.toDegrees(asin(sinElev.coerceIn(-1.0, 1.0)))
}

/** Whether [lat]/[lon] is on the night side (Sun below the horizon). */
internal fun isNight(lat: Double, lon: Double, subsolar: SubsolarPoint): Boolean =
    solarElevationDeg(lat, lon, subsolar) < 0.0

/**
 * The terminator latitude at a given [lon] — the latitude where the Sun sits on
 * the horizon. From sin φ·sin δ + cos φ·cos δ·cos H = 0 → tan φ = −cos H / tan δ.
 *
 * Near the equinoxes δ → 0 makes the true terminator two vertical meridians with
 * no single latitude per longitude; |δ| is floored to [MIN_DECL_DEG] so the
 * curve stays steep-but-finite (a good visual stand-in for the ~2 days a year
 * this matters) instead of blowing up.
 */
internal fun terminatorLatitude(lon: Double, subsolar: SubsolarPoint): Double {
    val declDeg = subsolar.lat.let {
        when {
            it >= 0.0 && it < MIN_DECL_DEG -> MIN_DECL_DEG
            it < 0.0 && it > -MIN_DECL_DEG -> -MIN_DECL_DEG
            else -> it
        }
    }
    val decl = Math.toRadians(declDeg)
    val hourAngle = Math.toRadians(lon - subsolar.lon)
    return Math.toDegrees(atan(-cos(hourAngle) / tan(decl)))
}

/** Floor on |declination| used by [terminatorLatitude] to survive the equinox. */
private const val MIN_DECL_DEG = 0.3

/**
 * The terminator as a flat `[lon0, lat0, lon1, lat1, …]` polyline sampled every
 * [stepDeg] degrees of longitude from −180 to +180. Suitable for drawing the
 * gray-line stroke; see [nightPolygonLatLon] for the filled night region.
 */
internal fun terminatorCurveLatLon(subsolar: SubsolarPoint, stepDeg: Double = 3.0): FloatArray {
    val step = if (stepDeg <= 0.0) 3.0 else stepDeg
    val out = ArrayList<Float>()
    var lon = -180.0
    while (lon <= 180.0 + 1e-9) {
        out.add(lon.toFloat())
        out.add(terminatorLatitude(lon, subsolar).toFloat())
        lon += step
    }
    return out.toFloatArray()
}

/**
 * The night region as a flat `[lon0, lat0, …]` closed ring: the terminator curve
 * plus two vertices along whichever pole is in darkness (the south pole when the
 * Sun is north of the equator, the north pole otherwise). Fill this ring to
 * shade the dark side of the map.
 */
internal fun nightPolygonLatLon(subsolar: SubsolarPoint, stepDeg: Double = 3.0): FloatArray {
    val curve = terminatorCurveLatLon(subsolar, stepDeg)
    // Sun north of the equator ⇒ the south pole is dark; close the ring there.
    val poleLat = if (subsolar.lat >= 0.0) -90f else 90f
    val out = FloatArray(curve.size + 4)
    System.arraycopy(curve, 0, out, 0, curve.size)
    // Curve ends at lon +180; drop to the dark pole, run back to lon −180, and
    // let the fill auto-close to the curve's first vertex.
    out[curve.size] = 180f
    out[curve.size + 1] = poleLat
    out[curve.size + 2] = -180f
    out[curve.size + 3] = poleLat
    return out
}
