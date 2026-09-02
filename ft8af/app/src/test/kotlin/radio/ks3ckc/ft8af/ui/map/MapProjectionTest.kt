package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

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
    fun rangeRingRadius_edgeDistanceFillsTheDisc() {
        // A ring at the antipode distance (MAP_EDGE_KM) must sit exactly on the disc
        // edge, i.e. its pixel radius equals r * scale.
        val r = 500f
        assertThat(rangeRingRadiusPx(MAP_EDGE_KM, r, 1f)).isWithin(1e-3f).of(r)
        assertThat(rangeRingRadiusPx(MAP_EDGE_KM, r, 2f)).isWithin(1e-3f).of(1000f)
    }

    @Test
    fun rangeRingRadius_isLinearInDistanceAndScale() {
        val r = 400f
        // Half the edge distance -> half the radius; doubling scale doubles it.
        assertThat(rangeRingRadiusPx(MAP_EDGE_KM / 2.0, r, 1f)).isWithin(1e-3f).of(r / 2f)
        assertThat(rangeRingRadiusPx(MAP_EDGE_KM / 2.0, r, 2f)).isWithin(1e-3f).of(r)
    }

    @Test
    fun rangeRingRadius_alignsWithProjectedMarker() {
        // A station at great-circle distance d is drawn at screen radius
        // sqrt(x^2 + y^2) * r * scale after azProject. The range ring for that same
        // distance must land on top of the marker, not (as the old PI/2 factor did)
        // ~57% further out. This is the regression guard for the range-ring bug.
        val r = 640f
        val scale = 1.5f
        val opLat = 40.0
        val opLon = -75.0
        for (target in listOf(
            Pair(0.0, 0.0),
            Pair(51.5, -0.13),
            Pair(-33.9, 151.2),
            Pair(35.7, 139.7),
        )) {
            val p = azProject(opLat, opLon, target.first, target.second)
            val markerRadiusPx = sqrt(p.x * p.x + p.y * p.y) * r * scale
            val ringRadiusPx = rangeRingRadiusPx(p.distKm, r, scale)
            assertThat(ringRadiusPx).isWithin(1e-2f).of(markerRadiusPx)
        }
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
