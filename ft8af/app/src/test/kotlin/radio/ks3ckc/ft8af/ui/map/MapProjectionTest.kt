package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for the map's coordinate math: great-circle distance, the two
 * projections (equirectangular + azimuthal-equidistant), and bearing. No
 * Android/Compose runtime is touched, so these run as plain JVM tests.
 *
 * A quarter of the way around the globe along a meridian/equator is
 * (PI/2) * 6371 km ≈ 10007.5 km — the reference value used throughout.
 */
class MapProjectionTest {

    private val quarterArcKm = 10007.54

    @Test
    fun greatCircleKm_isZeroForCoincidentPoints() {
        // acos() of a sin^2+cos^2 sum that rounds just under 1.0 leaves a
        // sub-meter residue, so assert "within 10 m" rather than exactly 0.
        assertThat(greatCircleKm(40.0, -75.0, 40.0, -75.0)).isWithin(1e-2).of(0.0)
    }

    @Test
    fun greatCircleKm_equatorQuarterTurnIsAQuarterOfTheGlobe() {
        assertThat(greatCircleKm(0.0, 0.0, 0.0, 90.0)).isWithin(1.0).of(quarterArcKm)
    }

    @Test
    fun equirectProject_mapsCornersToNormalizedExtents() {
        val origin = equirectProject(0.0, 0.0)
        assertThat(origin.x).isWithin(1e-6f).of(0f)
        assertThat(origin.y).isWithin(1e-6f).of(0f)

        val corner = equirectProject(90.0, 180.0)
        assertThat(corner.x).isWithin(1e-6f).of(1f)
        assertThat(corner.y).isWithin(1e-6f).of(-1f)
    }

    @Test
    fun azProject_centerCollapsesToOrigin() {
        val p = azProject(12.0, 34.0, 12.0, 34.0)
        assertThat(p.x).isWithin(1e-6f).of(0f)
        assertThat(p.y).isWithin(1e-6f).of(0f)
        assertThat(p.distKm).isWithin(1e-6).of(0.0)
    }

    @Test
    fun azProject_distanceMatchesGreatCircle() {
        val p = azProject(0.0, 0.0, 0.0, 90.0)
        assertThat(p.distKm).isWithin(1.0).of(quarterArcKm)
    }

    @Test
    fun computeBearing_dueEastIs90() {
        assertThat(computeBearing(0.0, 0.0, 0.0, 90.0)).isWithin(1e-6).of(90.0)
    }

    @Test
    fun computeBearing_dueNorthIs0() {
        assertThat(computeBearing(0.0, 0.0, 90.0, 0.0)).isWithin(1e-6).of(0.0)
    }
}
