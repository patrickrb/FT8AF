package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SmartBeaconing™ route sampling — the HamHUD/APRSdroid scheme, in the shape a
 * road trip needs it.
 *
 * The rules that matter are the ones that decide what the drawn route looks
 * like: point spacing that follows speed, a point through every real turn, no
 * points from a drifting compass at a standstill, and silence while parked
 * (which is what lets the server derive an overnight stop). Pure math — no
 * Android runtime.
 */
class SmartBeaconSamplerTest {
    private val base = 1_700_000_000_000L
    private val car = SmartBeaconProfile.DEFAULT

    /**
     * A fix [tSec] into the trip, [metersNorth] up the road from the origin.
     * One degree of latitude is ~111,195 m, which is all the geometry these
     * tests need.
     */
    private fun fix(
        tSec: Long,
        metersNorth: Double = 0.0,
        speedMph: Double? = null,
        heading: Double? = null,
        accuracy: Double? = 8.0,
    ) = TripPoint(
        timestampMs = base + tSec * 1000L,
        latitude = 39.0 + metersNorth / 111_195.0,
        longitude = -105.0,
        speedMph = speedMph,
        headingDeg = heading,
        accuracyM = accuracy,
    )

    // -- rate curve ---------------------------------------------------------

    @Test
    fun `beacon rate is constant above the fast speed and below the slow speed`() {
        assertThat(SmartBeaconSampler.beaconRateSec(80.0, car)).isEqualTo(car.fastRateSec)
        assertThat(SmartBeaconSampler.beaconRateSec(70.0, car)).isEqualTo(car.fastRateSec)
        assertThat(SmartBeaconSampler.beaconRateSec(1.0, car)).isEqualTo(car.slowRateSec)
    }

    @Test
    fun `between the thresholds the rate scales so point spacing stays even`() {
        // fastRate * fastSpeed / speed: 30 s * 70 / 35 = 60 s.
        assertThat(SmartBeaconSampler.beaconRateSec(35.0, car)).isEqualTo(60)
        // Half the speed, twice the interval — the same ~0.6 mi between points.
        assertThat(SmartBeaconSampler.beaconRateSec(17.5, car)).isEqualTo(120)
    }

    @Test
    fun `the rate curve never runs outside the profile's own bounds`() {
        for (speed in 1..120) {
            val rate = SmartBeaconSampler.beaconRateSec(speed.toDouble(), car)
            assertThat(rate).isAtLeast(car.fastRateSec)
            assertThat(rate).isAtMost(car.slowRateSec)
        }
    }

    // -- turn threshold -----------------------------------------------------

    @Test
    fun `turn threshold tightens as speed rises`() {
        // 15 + 255/65 = ~18.9 degrees: an interstate curve counts.
        assertThat(SmartBeaconSampler.turnThresholdDeg(65.0, car)).isWithin(0.1).of(18.9)
        // 15 + 255/25 = 25.2 degrees: a town corner has to be a real one.
        assertThat(SmartBeaconSampler.turnThresholdDeg(25.0, car)).isWithin(0.1).of(25.2)
        // Crawling: 15 + 255/2 = 142.5 degrees — practically a U-turn.
        assertThat(SmartBeaconSampler.turnThresholdDeg(2.0, car)).isWithin(0.1).of(142.5)
    }

    @Test
    fun `a standstill can never peg a corner`() {
        assertThat(SmartBeaconSampler.turnThresholdDeg(0.0, car)).isEqualTo(180.0)
    }

    // -- speed derivation ---------------------------------------------------

    @Test
    fun `speed falls back to distance over time when the fix carries none`() {
        val prev = fix(0)
        val next = fix(60, metersNorth = 1609.0) // a mile in a minute = 60 mph
        assertThat(SmartBeaconSampler.effectiveSpeedMph(prev, next, 60.0)).isWithin(1.0).of(60.0)
    }

    @Test
    fun `the larger of reported and derived speed wins`() {
        val prev = fix(0, speedMph = 55.0)
        val next = fix(10, metersNorth = 20.0, speedMph = 55.0)
        // Derived is ~4 mph (a stale/lagging position); the Doppler reading is real.
        assertThat(SmartBeaconSampler.effectiveSpeedMph(prev, next, 10.0)).isWithin(0.1).of(55.0)
    }

    // -- end-to-end sampling ------------------------------------------------

    @Test
    fun `the first fix always starts the route`() {
        val sampler = SmartBeaconSampler(car)
        assertThat(sampler.offer(fix(0))?.reason).isEqualTo(BeaconReason.FIRST)
    }

    @Test
    fun `highway cruising yields a point about every fast-rate seconds`() {
        val sampler = SmartBeaconSampler(car)
        val kept = mutableListOf<Long>()
        // Ten minutes at 70 mph (31.3 m/s), a fix a second, dead straight.
        for (t in 0..600L) {
            val p = fix(t, metersNorth = t * 31.3, speedMph = 70.0, heading = 90.0)
            sampler.offer(p)?.let { kept.add(t) }
        }
        // 600 s / 30 s = 20 points, plus the first fix.
        assertThat(kept.size).isIn(19..22)
        val gaps = kept.zipWithNext { a, b -> b - a }
        assertThat(gaps.all { it in 29..31 }).isTrue()
    }

    @Test
    fun `a curve pegs a corner between interval points`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(0, speedMph = 65.0, heading = 90.0))
        // 15 s later — inside the 30 s interval — the road has bent 25 degrees,
        // past the ~18.9 degree threshold at this speed.
        val decision =
            sampler.offer(
                fix(15, metersNorth = 435.0, speedMph = 65.0, heading = 115.0),
            )
        assertThat(decision?.reason).isEqualTo(BeaconReason.CORNER)
    }

    @Test
    fun `a gentle drift on a straight highway is not a corner`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(0, speedMph = 65.0, heading = 90.0))
        // 6 degrees of lane-change wander, well under the threshold.
        assertThat(sampler.offer(fix(15, metersNorth = 435.0, speedMph = 65.0, heading = 96.0)))
            .isNull()
    }

    @Test
    fun `a corner needs the minimum turn time so a swerve cannot spam points`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(0, speedMph = 65.0, heading = 90.0))
        // Same 90-degree swing 5 s apart — under minTurnTimeSec (12).
        assertThat(sampler.offer(fix(5, metersNorth = 145.0, speedMph = 65.0, heading = 180.0)))
            .isNull()
    }

    @Test
    fun `a phone swinging its heading at a red light does not peg a corner`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(0, speedMph = 35.0, heading = 90.0))
        // Stopped at the light: 20 s, 3 m of GPS wander, heading swung 80 degrees.
        // Android keeps reporting a bearing when stationary, so only the
        // movement guard rules this out.
        assertThat(sampler.offer(fix(20, metersNorth = 3.0, speedMph = 0.0, heading = 170.0)))
            .isNull()
    }

    @Test
    fun `a parked rover records nothing, leaving the gap the server needs`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(0, speedMph = 30.0, heading = 90.0))
        // Eight hours in a motel lot: a fix a minute, GPS wandering a few metres,
        // the compass spinning freely.
        var kept = 0
        for (minute in 1..480L) {
            val p =
                fix(
                    minute * 60,
                    metersNorth = (minute % 3).toDouble(),
                    speedMph = 0.0,
                    heading = (minute * 47 % 360).toDouble(),
                )
            if (sampler.offer(p) != null) kept++
            sampler.noteStationary(p)
        }
        assertThat(kept).isEqualTo(0)
        assertThat(sampler.isParked).isTrue()
    }

    @Test
    fun `pulling back out onto the road is recorded immediately`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(0, speedMph = 30.0, heading = 90.0))
        for (minute in 1..300L) {
            val p = fix(minute * 60, metersNorth = 2.0, speedMph = 0.0, heading = 90.0)
            sampler.offer(p)
            sampler.noteStationary(p)
        }
        assertThat(sampler.isParked).isTrue()

        val rolling = fix(18_100, metersNorth = 400.0, speedMph = 25.0, heading = 90.0)
        assertThat(sampler.offer(rolling)?.reason).isEqualTo(BeaconReason.RESUME)
        assertThat(sampler.isParked).isFalse()
    }

    @Test
    fun `inaccurate fixes never enter the route`() {
        val sampler = SmartBeaconSampler(car)
        assertThat(sampler.offer(fix(0, accuracy = 450.0))).isNull()
    }

    @Test
    fun `out-of-order fixes are discarded`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(100))
        assertThat(sampler.offer(fix(40, metersNorth = 5000.0))).isNull()
    }

    // -- QSO anchoring ------------------------------------------------------

    @Test
    fun `a QSO mid-leg pins a route vertex where the contact happened`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(0, speedMph = 70.0, heading = 90.0))
        // 20 s into a 30 s interval — half a mile past the last point.
        val where = fix(20, metersNorth = 626.0, speedMph = 70.0, heading = 90.0)
        val anchor = sampler.anchorForQso(where)
        assertThat(anchor?.reason).isEqualTo(BeaconReason.QSO)
        assertThat(sampler.lastAccepted).isEqualTo(where)
    }

    @Test
    fun `a QSO right after a route point does not duplicate it`() {
        val sampler = SmartBeaconSampler(car)
        val start = fix(0, speedMph = 3.0, heading = 90.0)
        sampler.offer(start)
        // 5 s and ~7 m later: the existing vertex already marks the spot.
        assertThat(sampler.anchorForQso(fix(5, metersNorth = 7.0))).isNull()
    }

    @Test
    fun `a QSO anchor counts as movement so the next leg measures from it`() {
        val sampler = SmartBeaconSampler(car)
        sampler.offer(fix(0, speedMph = 70.0, heading = 90.0))
        sampler.anchorForQso(fix(20, metersNorth = 626.0, speedMph = 70.0, heading = 90.0))
        assertThat(sampler.traveledMeters).isWithin(5.0).of(626.0)
    }

    @Test
    fun `haversine matches a known distance`() {
        // One degree of latitude is ~111.2 km anywhere on the globe.
        assertThat(haversineMeters(39.0, -105.0, 40.0, -105.0)).isWithin(500.0).of(111_195.0)
    }

    @Test
    fun `heading delta wraps across north`() {
        assertThat(headingDelta(350.0, 10.0)).isWithin(0.001).of(20.0)
        assertThat(headingDelta(10.0, 350.0)).isWithin(0.001).of(20.0)
        assertThat(headingDelta(0.0, 180.0)).isWithin(0.001).of(180.0)
        assertThat(headingDelta(90.0, 95.0)).isWithin(0.001).of(5.0)
    }
}
