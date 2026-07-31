package radio.ks3ckc.ft8af.rtota

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance in metres. Pure — the unit tests lean on it directly. */
fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    // min(1.0, …) guards asin's domain against floating-point overshoot for antipodes.
    return 2 * r * asin(min(1.0, sqrt(a)))
}

/** Smallest absolute angle between two compass bearings, 0–180 degrees. */
fun headingDelta(a: Double, b: Double): Double {
    // Normalise into [0,360) first so a wrap (350° -> 10°) reads as 20°, not 340°.
    val d = ((a - b) % 360.0 + 360.0) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/**
 * How aggressively to sample the route.
 *
 * @param minIntervalMs never emit two breadcrumbs closer together than this.
 * @param minDistanceM  …and only once the rover has moved this far.
 * @param maxAccuracyM  drop fixes wobblier than this (tunnels, urban canyons).
 * @param turnHeadingDeg a course change this large is worth a point of its own…
 * @param turnMinDistanceM …provided the rover moved at least this far, so a
 *        parked phone's drifting compass can't emit a point every interval.
 */
data class SamplerConfig(
    val minIntervalMs: Long = 30_000L,
    val minDistanceM: Double = 120.0,
    val maxAccuracyM: Double = 100.0,
    val turnHeadingDeg: Double = 45.0,
    val turnMinDistanceM: Double = 30.0,
)

/**
 * Decides which GPS fixes become route breadcrumbs.
 *
 * Driving at 70 mph a phone emits a fix every second — 3,600 points an hour,
 * nearly all of them redundant on a straight interstate. This keeps the shape of
 * the route (time floor, distance floor, plus an extra point through a turn so
 * corners don't get cut) at a small fraction of the volume, which matters for
 * both the cellular data budget and the size of the offline backlog.
 *
 * Deliberately **no** heartbeat while stationary: the server derives overnight
 * stops from ≥4 h gaps in the breadcrumb trail (see the RTOTA README), so a
 * parked rover must go quiet for that gap to exist. Silence is data here.
 *
 * Pure and stateless apart from the last accepted fix, so every rule is
 * unit-testable without a device.
 */
class TripPointSampler(private val config: SamplerConfig = SamplerConfig()) {

    private var last: TripPoint? = null

    /** The last fix this sampler accepted, for stamping QSOs with a position. */
    val lastAccepted: TripPoint? get() = last

    /** Total metres along the accepted breadcrumbs — drives the live mileage readout. */
    var traveledMeters: Double = 0.0
        private set

    /** Forget the route so far. Called when a trip ends. */
    fun reset() {
        last = null
        traveledMeters = 0.0
    }

    /**
     * Offer a fix. Returns the point to enqueue, or null when it adds nothing.
     * Accepting a point makes it the new reference for the next decision.
     */
    fun offer(point: TripPoint): TripPoint? {
        if (!shouldAccept(last, point)) return null
        last?.let {
            traveledMeters += haversineMeters(it.latitude, it.longitude, point.latitude, point.longitude)
        }
        last = point
        return point
    }

    private fun shouldAccept(prev: TripPoint?, next: TripPoint): Boolean {
        val accuracy = next.accuracyM
        if (accuracy != null && accuracy > config.maxAccuracyM) return false
        if (prev == null) return true
        // Out-of-order or duplicate fix (GPS can replay a cached one after a
        // provider restart); keeping it would draw the route backwards.
        val elapsed = next.timestampMs - prev.timestampMs
        if (elapsed <= 0L) return false

        val moved = haversineMeters(prev.latitude, prev.longitude, next.latitude, next.longitude)
        if (elapsed < config.minIntervalMs) return false
        if (moved >= config.minDistanceM) return true

        // Below the distance floor: only a genuine turn earns a point.
        val prevHeading = prev.headingDeg
        val nextHeading = next.headingDeg
        if (prevHeading != null && nextHeading != null && moved >= config.turnMinDistanceM) {
            return headingDelta(prevHeading, nextHeading) >= config.turnHeadingDeg
        }
        return false
    }
}
