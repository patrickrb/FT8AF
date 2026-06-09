package radio.ks3ckc.ft8us.ui.pota

import radio.ks3ckc.ft8us.ui.decode.QSO_MAP_MIN_SPAN_DEG
import radio.ks3ckc.ft8us.ui.decode.QSO_MAP_PAD_FRAC
import radio.ks3ckc.ft8us.ui.decode.QsoPathProjection
import kotlin.math.max
import kotlin.math.min

/**
 * Pure (no-Android) geometry for the POTA activation contacts map. Generalizes
 * [QsoPathProjection] from two endpoints to the operator plus an arbitrary list
 * of contacts: frames them all into a fixed-size canvas with an equirectangular
 * projection at a uniform degrees-per-pixel scale, reusing the same min-span
 * floor and padding so a clustered cluster of nearby contacts doesn't zoom in to
 * an uninformative street-level view.
 *
 * Kept separate from the Compose `DrawScope` drawing code so the math can be
 * unit-tested without a Canvas. Construct with the operator location, the
 * contact (lat, lon) pairs, and the canvas size; the instance exposes the
 * antemeridian-normalized contact longitudes plus `projectX`/`projectY`.
 */
internal class PotaActivationProjection(
    operatorLat: Double,
    operatorLon: Double,
    contacts: List<Pair<Double, Double>>,
    private val width: Float,
    private val height: Float,
) {
    /** Each contact's longitude shifted to the equivalent value nearest the
     *  operator, so its connecting line takes the short way around the globe. */
    val normalizedContactLons: List<Double> =
        contacts.map { QsoPathProjection.normalizeLon(operatorLon, it.second) }

    private val centerLon: Double
    private val centerLat: Double

    /** Uniform projection scale in pixels-per-degree. */
    val scale: Double

    init {
        // Bounding box over the operator + every (normalized) contact.
        var minLon = operatorLon
        var maxLon = operatorLon
        var minLat = operatorLat
        var maxLat = operatorLat
        for (i in contacts.indices) {
            val lon = normalizedContactLons[i]
            val lat = contacts[i].first
            minLon = min(minLon, lon)
            maxLon = max(maxLon, lon)
            minLat = min(minLat, lat)
            maxLat = max(maxLat, lat)
        }
        centerLon = (minLon + maxLon) / 2.0
        centerLat = (minLat + maxLat) / 2.0

        val padFactor = 1.0 + 2.0 * QSO_MAP_PAD_FRAC
        val spanLon = max(maxLon - minLon, QSO_MAP_MIN_SPAN_DEG) * padFactor
        val spanLat = max(maxLat - minLat, QSO_MAP_MIN_SPAN_DEG) * padFactor
        // Fit both spans; the smaller scale wins so neither axis overflows.
        scale = min(width / spanLon, height / spanLat)
    }

    fun projectX(lon: Double): Float = (width / 2.0 + (lon - centerLon) * scale).toFloat()

    fun projectY(lat: Double): Float = (height / 2.0 - (lat - centerLat) * scale).toFloat()
}
