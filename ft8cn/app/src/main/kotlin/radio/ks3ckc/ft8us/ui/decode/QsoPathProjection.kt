package radio.ks3ckc.ft8us.ui.decode

import kotlin.math.max
import kotlin.math.min

/** Minimum lat/lon span (degrees) the QSO path map will frame, so two nearby
 *  stations don't zoom in to an uninformative street-level view. */
internal const val QSO_MAP_MIN_SPAN_DEG = 25.0

/** Fractional padding added around the framed endpoints, per side. */
internal const val QSO_MAP_PAD_FRAC = 0.30

/**
 * Pure (no-Android) geometry for the QSO path map. Frames the operator's grid
 * and the remote grid into a fixed-size canvas using an equirectangular
 * projection with a uniform degrees-per-pixel scale, so the inset isn't
 * stretched.
 *
 * Kept separate from the Compose `DrawScope` drawing code in QsoSheet so the
 * math can be unit-tested without a Canvas. Construct with the two endpoints
 * and the canvas size; the instance exposes the antemeridian-normalized remote
 * longitude plus `projectX`/`projectY` (degrees -> pixels).
 */
internal class QsoPathProjection(
    myLat: Double,
    myLon: Double,
    theirLat: Double,
    theirLon: Double,
    private val width: Float,
    private val height: Float,
) {
    /** Remote longitude shifted to the equivalent value nearest the operator,
     *  so the connecting path takes the short way around the globe. */
    val normalizedTheirLon: Double = normalizeLon(myLon, theirLon)

    private val centerLon: Double
    private val centerLat: Double

    /** Uniform projection scale in pixels-per-degree. */
    val scale: Double

    init {
        val tLon = normalizedTheirLon
        centerLon = (min(myLon, tLon) + max(myLon, tLon)) / 2.0
        centerLat = (min(myLat, theirLat) + max(myLat, theirLat)) / 2.0

        val padFactor = 1.0 + 2.0 * QSO_MAP_PAD_FRAC
        val spanLon = max(max(myLon, tLon) - min(myLon, tLon), QSO_MAP_MIN_SPAN_DEG) * padFactor
        val spanLat = max(max(myLat, theirLat) - min(myLat, theirLat), QSO_MAP_MIN_SPAN_DEG) * padFactor
        // Fit both spans; the smaller scale wins so neither axis overflows.
        scale = min(width / spanLon, height / spanLat)
    }

    fun projectX(lon: Double): Float = (width / 2.0 + (lon - centerLon) * scale).toFloat()

    fun projectY(lat: Double): Float = (height / 2.0 - (lat - centerLat) * scale).toFloat()

    companion object {
        /**
         * Shift [lon] by ±360 so it lies within 180° of [refLon] — i.e. the
         * representation reached by the short way around the antemeridian.
         */
        fun normalizeLon(refLon: Double, lon: Double): Double {
            var l = lon
            if (l - refLon > 180.0) l -= 360.0
            else if (l - refLon < -180.0) l += 360.0
            return l
        }
    }
}
