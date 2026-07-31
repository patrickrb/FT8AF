package radio.ks3ckc.ft8af.rtota

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
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
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    // min(1.0, …) guards asin's domain against floating-point overshoot for antipodes.
    return 2 * r * asin(min(1.0, sqrt(a)))
}

/** Smallest absolute angle between two compass bearings, 0–180 degrees. */
fun headingDelta(
    a: Double,
    b: Double,
): Double {
    // Normalise into [0,360) first so a wrap (350° -> 10°) reads as 20°, not 340°.
    val d = ((a - b) % 360.0 + 360.0) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/** Android's Location reports m/s; SmartBeaconing and the UI both work in mph. */
internal const val MPS_TO_MPH = 2.2369363

internal const val METERS_PER_MILE = 1609.344

/**
 * SmartBeaconing™ parameters (HamHUD, Tony Arnerich KD7TA / Steve Bragg KA9MVA),
 * the same scheme APRSdroid, the Kenwood D710 and most APRS trackers use.
 *
 * Two rules, both keyed on speed:
 *
 *  - **Rate**: below [slowSpeedMph] beacon every [slowRateSec]; above
 *    [fastSpeedMph] every [fastRateSec]; in between, `fastRate × fastSpeed /
 *    speed` — so the *distance* between points stays roughly constant instead of
 *    the time. A 70 mph interstate and a 25 mph town street produce a similarly
 *    dense trail.
 *  - **Corner pegging**: send immediately when the course changes by more than
 *    `minTurnAngleDeg + turnSlope / speedMph`, provided [minTurnTimeSec] has
 *    passed. The division is what makes it work at both ends: at 5 mph the
 *    threshold is enormous (only a hairpin counts, and a parked phone's drifting
 *    compass never triggers), while at 65 mph it drops to a few degrees, which is
 *    what a gentle interstate curve actually looks like.
 *
 * Two deviations from APRS, both because our "beacon" is a row in a batched HTTP
 * queue rather than an RF transmission:
 *
 *  - Rates are far tighter than APRS defaults (which exist to protect a shared
 *    2 m channel). We are drawing a map line, so the trail can be dense.
 *  - A true standstill emits *nothing* — see [stationaryRadiusM]. APRS keeps
 *    beaconing so you stay visible; RTOTA derives overnight stops from ≥4 h gaps
 *    in the trail, so silence while parked is the signal, not a failure.
 */
data class SmartBeaconProfile(
    val fastSpeedMph: Double,
    val fastRateSec: Int,
    val slowSpeedMph: Double,
    val slowRateSec: Int,
    val minTurnTimeSec: Int,
    val minTurnAngleDeg: Double,
    /** Degrees × mph. Divided by speed, added to [minTurnAngleDeg]. */
    val turnSlope: Double,
    /**
     * A corner only counts if the rover actually moved this far since the last
     * point. Pure SmartBeaconing has no such guard because an APRS tracker reads
     * course from GPS velocity, which goes undefined when stopped; Android keeps
     * reporting the last bearing (and some devices feed in a magnetometer), so a
     * phone idling at a red light swings its heading freely. Without this, every
     * fuel stop grows a little scribble of points where the truck never moved.
     */
    val turnMinDistanceM: Double,
    /** Movement under this radius since the last point counts as parked. */
    val stationaryRadiusM: Double,
    /** Fixes wobblier than this are ignored outright. */
    val maxAccuracyM: Double,
) {
    companion object {
        /**
         * The tuning RTOTA ships. It is a road-trip service — the rover is in a
         * vehicle — so there is one profile and no picker: numbers tuned for
         * highway speeds, giving ~0.6 mi between points at 70 mph.
         *
         * Tests build variants with [copy] to isolate a single rule.
         */
        val DEFAULT =
            SmartBeaconProfile(
                fastSpeedMph = 70.0,
                fastRateSec = 30,
                slowSpeedMph = 4.0,
                slowRateSec = 180,
                minTurnTimeSec = 12,
                minTurnAngleDeg = 15.0,
                turnSlope = 255.0,
                turnMinDistanceM = 40.0,
                stationaryRadiusM = 60.0,
                maxAccuracyM = 100.0,
            )
    }
}

/** Why a point was kept — surfaced in the UI and the debug log. */
enum class BeaconReason {
    /** First fix of the trip. */
    FIRST,

    /** The speed-scaled time interval elapsed. */
    RATE,

    /** Course changed more than the speed-scaled turn threshold. */
    CORNER,

    /** The rover started moving again after being parked. */
    RESUME,

    /** Anchored to a logged contact so the QSO sits exactly on the route line. */
    QSO,
}

/** A kept breadcrumb and the rule that kept it. */
data class BeaconDecision(val point: TripPoint, val reason: BeaconReason)

/**
 * Turns a 1 Hz stream of GPS fixes into the smallest set of points that still
 * draws the route honestly, using SmartBeaconing™ (see [SmartBeaconProfile]).
 *
 * The naive alternatives both fail on a road trip: a fixed time interval either
 * floods the queue in town or cuts corners on the highway, and a fixed distance
 * interval turns every curve into a chord. Speed-scaled rate plus corner pegging
 * is the scheme APRS trackers settled on after two decades of exactly this
 * problem, so this follows it rather than inventing something.
 *
 * Stateful only in the last kept point (plus a parked flag); every rule is pure
 * and unit-tested.
 */
class SmartBeaconSampler(
    /** Injected only so tests can isolate a rule; the app always uses the default. */
    private val profile: SmartBeaconProfile = SmartBeaconProfile.DEFAULT,
) {

    private var last: TripPoint? = null

    /** True once the rover has sat inside [SmartBeaconProfile.stationaryRadiusM]. */
    private var parked = false

    /** The last fix that was kept, for stamping QSOs and measuring the next one. */
    val lastAccepted: TripPoint? get() = last

    /** Whether the sampler currently considers the rover stopped. */
    val isParked: Boolean get() = parked

    /** Metres along the kept breadcrumbs — drives the live mileage readout. */
    var traveledMeters: Double = 0.0
        private set

    fun reset() {
        last = null
        parked = false
        traveledMeters = 0.0
    }

    /**
     * Offer a fix. Returns the decision when the point is worth keeping, else
     * null. Accepting makes it the reference for the next call.
     */
    fun offer(point: TripPoint): BeaconDecision? {
        val decision = evaluate(last, point, parked, profile) ?: return null
        accept(point, decision.reason == BeaconReason.RESUME)
        return decision
    }

    /**
     * Force-keep a point because a QSO was just logged there, so the route has a
     * vertex exactly where the contact happened and the QSO plots *on* the line
     * rather than beside it. Returns null when the last kept point is already
     * close enough in time and space to serve as that vertex.
     */
    fun anchorForQso(point: TripPoint): BeaconDecision? {
        val prev = last
        if (prev != null) {
            val elapsedSec = (point.timestampMs - prev.timestampMs) / 1000.0
            if (elapsedSec < 0) return null
            val moved = haversineMeters(prev.latitude, prev.longitude, point.latitude, point.longitude)
            if (elapsedSec <= QSO_ANCHOR_MERGE_SEC && moved <= QSO_ANCHOR_MERGE_M) return null
        }
        accept(point, resumed = true)
        return BeaconDecision(point, BeaconReason.QSO)
    }

    private fun accept(
        point: TripPoint,
        resumed: Boolean,
    ) {
        last?.let {
            traveledMeters += haversineMeters(it.latitude, it.longitude, point.latitude, point.longitude)
        }
        last = point
        if (resumed) parked = false
    }

    companion object {
        /** A QSO within this window of the last point needs no anchor of its own. */
        const val QSO_ANCHOR_MERGE_SEC = 20.0
        const val QSO_ANCHOR_MERGE_M = 25.0

        /**
         * Speed for a fix, in mph. Prefers the reported Doppler speed but falls
         * back to distance ÷ time, taking the larger of the two — a fix that
         * carries no speed (common indoors-to-outdoors, and on some chipsets
         * right after a cold start) would otherwise read as parked and suppress
         * the whole trail. Borrowed from APRSdroid's SmartBeaconing, which does
         * the same max() for the same reason.
         */
        internal fun effectiveSpeedMph(
            prev: TripPoint,
            next: TripPoint,
            elapsedSec: Double,
        ): Double {
            val reported = max(prev.speedMph ?: 0.0, next.speedMph ?: 0.0)
            if (elapsedSec <= 0.0) return reported
            val moved = haversineMeters(prev.latitude, prev.longitude, next.latitude, next.longitude)
            val derived = (moved / elapsedSec) * MPS_TO_MPH
            return max(reported, derived)
        }

        /**
         * SmartBeaconing rate curve: seconds between points at a given speed.
         * Constant at both ends, `fastRate × fastSpeed / speed` in between, which
         * keeps the *spacing* of the points roughly constant.
         */
        internal fun beaconRateSec(
            speedMph: Double,
            profile: SmartBeaconProfile,
        ): Int =
            when {
                speedMph <= profile.slowSpeedMph -> profile.slowRateSec
                speedMph >= profile.fastSpeedMph -> profile.fastRateSec
                else ->
                    (profile.fastRateSec * profile.fastSpeedMph / speedMph)
                        .toInt()
                        .coerceIn(profile.fastRateSec, profile.slowRateSec)
            }

        /**
         * Corner-peg threshold in degrees: `minTurnAngle + turnSlope / speed`.
         * Returns 180 (unreachable) at a standstill so a drifting compass can
         * never peg a corner while parked.
         */
        internal fun turnThresholdDeg(
            speedMph: Double,
            profile: SmartBeaconProfile,
        ): Double {
            if (speedMph <= 0.0) return 180.0
            return min(180.0, profile.minTurnAngleDeg + profile.turnSlope / speedMph)
        }

        /**
         * The whole decision, as a pure function of the previous kept point, the
         * candidate, and whether the rover was parked.
         */
        internal fun evaluate(
            prev: TripPoint?,
            next: TripPoint,
            parked: Boolean,
            profile: SmartBeaconProfile,
        ): BeaconDecision? {
            val accuracy = next.accuracyM
            if (accuracy != null && accuracy > profile.maxAccuracyM) return null
            if (prev == null) return BeaconDecision(next, BeaconReason.FIRST)

            val elapsedSec = (next.timestampMs - prev.timestampMs) / 1000.0
            // Out-of-order or duplicate fix — GPS can replay a cached one after a
            // provider restart, and keeping it would draw the route backwards.
            if (elapsedSec <= 0.0) return null

            val moved = haversineMeters(prev.latitude, prev.longitude, next.latitude, next.longitude)
            val speedMph = effectiveSpeedMph(prev, next, elapsedSec)

            // Leaving a stop is worth a point of its own: it timestamps the
            // departure and starts the next leg from the right place.
            if (parked) {
                return if (moved > profile.stationaryRadiusM) {
                    BeaconDecision(next, BeaconReason.RESUME)
                } else {
                    null
                }
            }

            // Corner pegging comes first — a turn matters more than the clock.
            val prevHeading = prev.headingDeg
            val nextHeading = next.headingDeg
            if (prevHeading != null &&
                nextHeading != null &&
                elapsedSec >= profile.minTurnTimeSec &&
                moved >= profile.turnMinDistanceM
            ) {
                val delta = headingDelta(prevHeading, nextHeading)
                if (delta >= turnThresholdDeg(speedMph, profile)) {
                    return BeaconDecision(next, BeaconReason.CORNER)
                }
            }

            if (elapsedSec < beaconRateSec(speedMph, profile)) return null

            // The interval is up, but the rover hasn't gone anywhere: this is a
            // stop, not a slow crawl. Stay silent so the gap survives into the
            // server's overnight-stop derivation.
            if (moved <= profile.stationaryRadiusM) return null

            return BeaconDecision(next, BeaconReason.RATE)
        }
    }

    /**
     * Fold the "did we just become parked" bookkeeping into the offer path.
     * Called by [RtotaTripManager] on every fix, including the dropped ones, so
     * the parked flag reflects the *raw* stream rather than only kept points.
     */
    fun noteStationary(point: TripPoint) {
        val prev = last ?: return
        if (parked) return
        val elapsedSec = (point.timestampMs - prev.timestampMs) / 1000.0
        if (elapsedSec < profile.slowRateSec) return
        val moved = haversineMeters(prev.latitude, prev.longitude, point.latitude, point.longitude)
        if (moved <= profile.stationaryRadiusM) parked = true
    }
}
