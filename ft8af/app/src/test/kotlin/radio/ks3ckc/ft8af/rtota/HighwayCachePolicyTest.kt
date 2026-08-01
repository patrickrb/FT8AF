package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When to re-geocode, and how long an answer keeps applying.
 *
 * Both gates exist because either one alone fails a real case: parked at a fuel
 * stop the rover never moves, and at 80 mph it covers a mile before a
 * time-only gate would notice a junction. The expiry rules matter more still —
 * a label that never expired would carry "I-70" through a two-hour dead zone and
 * invent a route the rover may have left at the first exit.
 */
class HighwayCachePolicyTest {
    private val denver = 39.7392 to -104.9903
    private val now = 1_700_000_000_000L

    private fun metersEast(from: Pair<Double, Double>, meters: Double): Pair<Double, Double> {
        // ~111.32 km per degree of longitude at the equator, shrunk by cos(latitude).
        val degPerMeter = 1.0 / (111_320.0 * Math.cos(Math.toRadians(from.first)))
        return from.first to (from.second + meters * degPerMeter)
    }

    @Test
    fun `the first fix always refreshes`() {
        assertThat(
            shouldRefreshHighway(0L, 0.0, 0.0, now, denver.first, denver.second),
        ).isTrue()
    }

    @Test
    fun `a fix seconds later in the same spot does not refresh`() {
        assertThat(
            shouldRefreshHighway(now, denver.first, denver.second, now + 2_000L, denver.first, denver.second),
        ).isFalse()
    }

    @Test
    fun `enough elapsed time refreshes even while parked`() {
        assertThat(
            shouldRefreshHighway(
                now, denver.first, denver.second,
                now + HIGHWAY_REFRESH_MIN_INTERVAL_MS, denver.first, denver.second,
            ),
        ).isTrue()
    }

    @Test
    fun `enough distance refreshes even within the interval`() {
        val far = metersEast(denver, HIGHWAY_REFRESH_MIN_METERS + 100.0)
        assertThat(
            shouldRefreshHighway(now, denver.first, denver.second, now + 1_000L, far.first, far.second),
        ).isTrue()
    }

    @Test
    fun `a fresh nearby label is reused`() {
        val label =
            highwayLabelForPoint(
                "I-70", now, denver.first, denver.second,
                now + 5_000L, denver.first, denver.second,
            )
        assertThat(label).isEqualTo("I-70")
    }

    @Test
    fun `a label expires with time`() {
        val label =
            highwayLabelForPoint(
                "I-70", now, denver.first, denver.second,
                now + HIGHWAY_STALE_AFTER_MS + 1, denver.first, denver.second,
            )
        assertThat(label).isNull()
    }

    @Test
    fun `a label expires with distance`() {
        val far = metersEast(denver, HIGHWAY_STALE_AFTER_METERS + 500.0)
        val label =
            highwayLabelForPoint(
                "I-70", now, denver.first, denver.second,
                now + 1_000L, far.first, far.second,
            )
        assertThat(label).isNull()
    }

    @Test
    fun `no cached label yields null rather than an empty string`() {
        assertThat(
            highwayLabelForPoint(null, now, denver.first, denver.second, now, denver.first, denver.second),
        ).isNull()
        assertThat(
            highwayLabelForPoint("", now, denver.first, denver.second, now, denver.first, denver.second),
        ).isNull()
        // A label with no timestamp was never actually resolved.
        assertThat(
            highwayLabelForPoint("I-70", 0L, denver.first, denver.second, now, denver.first, denver.second),
        ).isNull()
    }
}
