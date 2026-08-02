package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The point of the sampler is a route line you can trust, so this test measures
 * exactly that: replay a synthetic drive at 1 Hz, keep whatever SmartBeaconing
 * keeps, then ask how far the *real* path ever strays from the polyline those
 * points draw.
 *
 * The failure this guards against is the one plain interval sampling always has:
 * a 30-second gap at 65 mph is 870 m of road, and if a curve happens inside it
 * the drawn line cuts straight across — the trip map shows the rover driving
 * through a canyon wall. Corner pegging exists to stop that, and the control
 * case below shows the difference it makes.
 */
class SmartBeaconRouteFidelityTest {
    private val startLat = 39.0
    private val startLon = -105.0

    private companion object {
        const val START_MS = 1_700_000_000_000L
    }

    /** One second of driving: where you end up, and which way you're pointed. */
    private data class Fix(val lat: Double, val lon: Double, val headingDeg: Double, val speedMph: Double)

    /**
     * Dead-reckon a 1 Hz track: cruise, then a sweeping 90° left-hand curve,
     * then cruise again. Flat-earth metres→degrees is plenty over these few km.
     *
     * Left-hand because the turn rate is negative and the heading runs 90°
     * (due east) down to 0° (due north) — counterclockwise.
     */
    private fun syntheticDrive(): List<Fix> {
        val out = ArrayList<Fix>()
        var lat = startLat
        var lon = startLon
        var heading = 90.0 // due east
        val speedMph = 65.0
        val metersPerSec = speedMph / 2.2369363

        fun step(
            seconds: Int,
            turnRateDegPerSec: Double,
        ) {
            repeat(seconds) {
                heading = (heading + turnRateDegPerSec + 360.0) % 360.0
                val rad = Math.toRadians(heading)
                // Bearing 0 = north, 90 = east.
                lat += (metersPerSec * cos(rad)) / 111_195.0
                lon += (metersPerSec * sin(rad)) / (111_195.0 * cos(Math.toRadians(lat)))
                out.add(Fix(lat, lon, heading, speedMph))
            }
        }

        step(seconds = 240, turnRateDegPerSec = 0.0) // 4 min straight
        step(seconds = 30, turnRateDegPerSec = -3.0) // 30 s sweeping left onto north
        step(seconds = 240, turnRateDegPerSec = 0.0) // 4 min straight again
        return out
    }

    private fun replay(profile: SmartBeaconProfile): List<TripPoint> {
        val sampler = SmartBeaconSampler(profile)
        val kept = ArrayList<TripPoint>()
        syntheticDrive().forEachIndexed { i, f ->
            val point =
                TripPoint(
                    timestampMs = START_MS + i * 1000L,
                    latitude = f.lat,
                    longitude = f.lon,
                    speedMph = f.speedMph,
                    headingDeg = f.headingDeg,
                    accuracyM = 6.0,
                )
            sampler.offer(point)?.let { kept.add(it.point) }
        }
        return kept
    }

    /**
     * Worst distance from any real 1 Hz position to the drawn polyline, in
     * metres, measured only up to the last beacon. Road past the final point
     * isn't drawn yet — that is the pending interval, not an inaccuracy — and
     * including it would swamp the number this test is actually about.
     */
    private fun worstDeviationMeters(kept: List<TripPoint>): Double {
        if (kept.size < 2) return Double.MAX_VALUE
        val drive = syntheticDrive()
        val lastCovered = ((kept.last().timestampMs - START_MS) / 1000L).toInt()
        var worst = 0.0
        for (idx in 0..minOf(lastCovered, drive.size - 1)) {
            val f = drive[idx]
            var best = Double.MAX_VALUE
            for (i in 0 until kept.size - 1) {
                best = minOf(best, distanceToSegmentMeters(f.lat, f.lon, kept[i], kept[i + 1]))
            }
            worst = max(worst, best)
        }
        return worst
    }

    /**
     * Point-to-segment distance, done in a local metre plane anchored at the
     * segment start — accurate well past the few kilometres in play here.
     */
    private fun distanceToSegmentMeters(
        lat: Double,
        lon: Double,
        a: TripPoint,
        b: TripPoint,
    ): Double {
        val mPerLon = 111_195.0 * cos(Math.toRadians(a.latitude))
        val px = (lon - a.longitude) * mPerLon
        val py = (lat - a.latitude) * 111_195.0
        val bx = (b.longitude - a.longitude) * mPerLon
        val by = (b.latitude - a.latitude) * 111_195.0
        val lenSq = bx * bx + by * by
        val t = if (lenSq == 0.0) 0.0 else ((px * bx + py * by) / lenSq).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    @Test
    fun `the drawn route follows the real road through a sweeping curve`() {
        val kept = replay(SmartBeaconProfile.DEFAULT)
        val worst = worstDeviationMeters(kept)
        // Roughly a highway's own width off the truth at the worst point of the
        // curve, across 8.5 miles of driving.
        assertThat(worst).isLessThan(40.0)
    }

    @Test
    fun `corner pegging is what buys that accuracy`() {
        // Same drive, same rates, but a turn threshold nothing can reach: pure
        // interval sampling. The curve gets cut across.
        val noCorners = SmartBeaconProfile.DEFAULT.copy(minTurnAngleDeg = 180.0, turnSlope = 0.0)
        val withCorners = worstDeviationMeters(replay(SmartBeaconProfile.DEFAULT))
        val without = worstDeviationMeters(replay(noCorners))
        // Printed because the whole point of the test is the measurement; the
        // numbers are what justify the profile's turn settings.
        println(
            "worst deviation: corner-pegged=%.1f m, interval-only=%.1f m".format(withCorners, without),
        )

        assertThat(without).isGreaterThan(100.0)
        assertThat(withCorners).isLessThan(without / 3.0)
    }

    @Test
    fun `it spends few points doing it`() {
        val kept = replay(SmartBeaconProfile.DEFAULT)
        // 510 fixes in (8.5 min at 1 Hz) — a couple of dozen out, most of them
        // the 30 s interval on the straights plus a handful through the curve.
        assertThat(kept.size).isAtMost(30)
        assertThat(kept.size).isAtLeast(17)
    }
}
