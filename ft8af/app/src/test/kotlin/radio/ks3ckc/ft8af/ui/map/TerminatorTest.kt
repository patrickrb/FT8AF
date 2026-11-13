package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Pure unit tests for the day/night terminator (gray-line) math. No Android or
 * Compose runtime is touched, so these run as plain JVM tests.
 */
class TerminatorTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    // -----------------------------------------------------------------------
    // Sub-solar point
    // -----------------------------------------------------------------------

    @Test
    fun subsolar_summerSolstice_isNearMaxNorthernDeclination() {
        // ~June 21: the Sun is overhead near the Tropic of Cancer (+23.44°).
        val p = subsolarPoint(utc(2024, Calendar.JUNE, 21, 12))
        assertThat(p.lat).isWithin(0.6).of(23.44)
    }

    @Test
    fun subsolar_winterSolstice_isNearMaxSouthernDeclination() {
        // ~Dec 21: the Sun is overhead near the Tropic of Capricorn (−23.44°).
        val p = subsolarPoint(utc(2024, Calendar.DECEMBER, 21, 12))
        assertThat(p.lat).isWithin(0.6).of(-23.44)
    }

    @Test
    fun subsolar_equinox_isNearEquator() {
        val p = subsolarPoint(utc(2024, Calendar.MARCH, 20, 12))
        assertThat(abs(p.lat)).isLessThan(1.0)
    }

    @Test
    fun subsolar_longitudeTracksUtcHour() {
        // At 12:00 UTC the Sun is near the prime meridian (± the equation of time).
        assertThat(abs(subsolarPoint(utc(2024, Calendar.MARCH, 20, 12)).lon)).isLessThan(2.0)
        // Each hour before/after noon shifts the sub-solar meridian 15° east/west.
        assertThat(subsolarPoint(utc(2024, Calendar.MARCH, 20, 6)).lon).isWithin(2.0).of(90.0)
        assertThat(subsolarPoint(utc(2024, Calendar.MARCH, 20, 18)).lon).isWithin(2.0).of(-90.0)
    }

    @Test
    fun normalizeLon_wrapsIntoRange() {
        assertThat(normalizeLon(190.0)).isWithin(1e-9).of(-170.0)
        assertThat(normalizeLon(-190.0)).isWithin(1e-9).of(170.0)
        assertThat(normalizeLon(45.0)).isWithin(1e-9).of(45.0)
        assertThat(normalizeLon(540.0)).isWithin(1e-9).of(180.0)
    }

    // -----------------------------------------------------------------------
    // Solar elevation / night test
    // -----------------------------------------------------------------------

    @Test
    fun solarElevation_isMaxAtSubsolarPoint_andMinAtAntipode() {
        val sub = SubsolarPoint(lat = 10.0, lon = 40.0)
        assertThat(solarElevationDeg(10.0, 40.0, sub)).isWithin(1e-6).of(90.0)
        assertThat(solarElevationDeg(-10.0, normalizeLon(40.0 + 180.0), sub)).isWithin(1e-6).of(-90.0)
    }

    @Test
    fun isNight_subsolarIsDay_antipodeIsNight() {
        val sub = SubsolarPoint(lat = 0.0, lon = 0.0)
        assertThat(isNight(0.0, 0.0, sub)).isFalse()
        assertThat(isNight(0.0, 180.0, sub)).isTrue()
    }

    // -----------------------------------------------------------------------
    // Terminator latitude
    // -----------------------------------------------------------------------

    @Test
    fun terminatorLatitude_liesOnTheZeroElevationBoundary() {
        // Well away from the equinox so |δ| isn't floored: the returned latitude
        // is exactly where the Sun grazes the horizon (elevation ≈ 0).
        val sub = subsolarPoint(utc(2024, Calendar.JUNE, 21, 9))
        for (lon in -180..180 step 30) {
            val lat = terminatorLatitude(lon.toDouble(), sub)
            assertThat(solarElevationDeg(lat, lon.toDouble(), sub)).isWithin(1e-6).of(0.0)
        }
    }

    @Test
    fun terminatorLatitude_survivesEquinoxWithoutBlowingUp() {
        // δ ≈ 0: without the floor this divides by ~0. Assert it stays finite and
        // in-range instead of producing NaN/∞.
        val sub = SubsolarPoint(lat = 0.0, lon = 0.0)
        val lat = terminatorLatitude(45.0, sub)
        assertThat(lat.isFinite()).isTrue()
        assertThat(abs(lat)).isAtMost(90.0)
    }

    // -----------------------------------------------------------------------
    // Night polygon
    // -----------------------------------------------------------------------

    @Test
    fun terminatorCurve_spansFullLongitudeRange() {
        val curve = terminatorCurveLatLon(SubsolarPoint(20.0, 0.0), stepDeg = 3.0)
        assertThat(curve.size % 2).isEqualTo(0)
        assertThat(curve.first()).isWithin(1e-3f).of(-180f) // first lon
        assertThat(curve[curve.size - 2]).isWithin(1e-3f).of(180f) // last lon
    }

    @Test
    fun nightPolygon_closesToTheDarkPole() {
        // Sun in the north ⇒ the south pole is dark: the ring closes at lat −90.
        val north = nightPolygonLatLon(SubsolarPoint(20.0, 0.0), stepDeg = 3.0)
        assertThat(north[north.size - 1]).isWithin(1e-3f).of(-90f)
        assertThat(north[north.size - 3]).isWithin(1e-3f).of(-90f)

        // Sun in the south ⇒ the north pole is dark: the ring closes at lat +90.
        val south = nightPolygonLatLon(SubsolarPoint(-20.0, 0.0), stepDeg = 3.0)
        assertThat(south[south.size - 1]).isWithin(1e-3f).of(90f)
        assertThat(south[south.size - 3]).isWithin(1e-3f).of(90f)
    }

    @Test
    fun nightPolygon_hasFourMoreCoordsThanTheCurve() {
        val sub = SubsolarPoint(15.0, -30.0)
        val curve = terminatorCurveLatLon(sub, stepDeg = 5.0)
        val ring = nightPolygonLatLon(sub, stepDeg = 5.0)
        assertThat(ring.size).isEqualTo(curve.size + 4)
    }
}
