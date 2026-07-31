package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Sampling rules for the route breadcrumb trail. Pure math — no Android runtime.
 */
class TripPointSamplerTest {
    private val base = 1_700_000_000_000L

    private fun point(
        tOffsetSec: Long,
        lat: Double = 39.0,
        lon: Double = -105.0,
        heading: Double? = null,
        accuracy: Double? = 8.0,
    ) = TripPoint(
        timestampMs = base + tOffsetSec * 1000L,
        latitude = lat,
        longitude = lon,
        headingDeg = heading,
        accuracyM = accuracy,
    )

    @Test
    fun `first fix is always accepted`() {
        val sampler = TripPointSampler()
        assertThat(sampler.offer(point(0))).isNotNull()
    }

    @Test
    fun `fix inside the time floor is dropped even when far away`() {
        val sampler = TripPointSampler(SamplerConfig(minIntervalMs = 30_000L, minDistanceM = 100.0))
        sampler.offer(point(0))
        // 20 s later, ~1 km down the road: too soon.
        assertThat(sampler.offer(point(20, lat = 39.009))).isNull()
    }

    @Test
    fun `stationary rover emits nothing, so the server can derive an overnight stop`() {
        val sampler = TripPointSampler(SamplerConfig(minIntervalMs = 30_000L, minDistanceM = 120.0))
        sampler.offer(point(0))
        // Six hours parked, one fix a minute: not a single extra breadcrumb.
        for (minute in 1..360) {
            assertThat(sampler.offer(point(minute * 60L, lat = 39.00001))).isNull()
        }
    }

    @Test
    fun `moving past both floors is accepted`() {
        val sampler = TripPointSampler(SamplerConfig(minIntervalMs = 30_000L, minDistanceM = 120.0))
        sampler.offer(point(0))
        // ~330 m north after 40 s.
        assertThat(sampler.offer(point(40, lat = 39.003))).isNotNull()
    }

    @Test
    fun `a turn is recorded even below the distance floor`() {
        val sampler =
            TripPointSampler(
                SamplerConfig(minIntervalMs = 30_000L, minDistanceM = 500.0, turnMinDistanceM = 30.0),
            )
        sampler.offer(point(0, heading = 90.0))
        // 60 m along, but the course swung 90 degrees — a corner worth keeping.
        assertThat(sampler.offer(point(40, lat = 39.00054, heading = 180.0))).isNotNull()
    }

    @Test
    fun `a wobbling compass at a standstill is not a turn`() {
        val sampler =
            TripPointSampler(
                SamplerConfig(minIntervalMs = 30_000L, minDistanceM = 500.0, turnMinDistanceM = 30.0),
            )
        sampler.offer(point(0, heading = 90.0))
        // Heading flipped right around, but the phone moved ~1 m.
        assertThat(sampler.offer(point(40, lat = 39.00001, heading = 270.0))).isNull()
    }

    @Test
    fun `inaccurate fixes are discarded`() {
        val sampler = TripPointSampler(SamplerConfig(maxAccuracyM = 100.0))
        assertThat(sampler.offer(point(0, accuracy = 450.0))).isNull()
    }

    @Test
    fun `out-of-order fixes are discarded`() {
        val sampler = TripPointSampler()
        sampler.offer(point(100))
        assertThat(sampler.offer(point(40, lat = 39.05))).isNull()
    }

    @Test
    fun `traveled distance accumulates over accepted points only`() {
        val sampler = TripPointSampler(SamplerConfig(minIntervalMs = 1_000L, minDistanceM = 100.0))
        sampler.offer(point(0))
        sampler.offer(point(60, lat = 39.01)) // ~1.11 km
        sampler.offer(point(120, lat = 39.02)) // another ~1.11 km
        assertThat(sampler.traveledMeters).isWithin(50.0).of(2224.0)
    }

    @Test
    fun `reset clears the route`() {
        val sampler = TripPointSampler()
        sampler.offer(point(0))
        sampler.offer(point(600, lat = 39.5))
        sampler.reset()
        assertThat(sampler.lastAccepted).isNull()
        assertThat(sampler.traveledMeters).isEqualTo(0.0)
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
