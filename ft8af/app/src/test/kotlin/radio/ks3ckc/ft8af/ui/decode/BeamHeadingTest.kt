package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure beam-heading math ([greatCircleBearing],
 * [longPathBearing], [normalizeHeadingDeg], [formatHeading]). No Android types
 * are touched, so these run as plain JUnit (no Robolectric). Grid-decoding
 * ([computeBeamHeadings]) is covered separately in [BeamHeadingGridTest].
 */
class BeamHeadingTest {

    private val tol = 1e-6

    // ---------- greatCircleBearing ----------

    @Test
    fun `due east along the equator is 90 degrees`() {
        assertThat(greatCircleBearing(0.0, 0.0, 0.0, 10.0)).isWithin(tol).of(90.0)
    }

    @Test
    fun `due west along the equator is 270 degrees`() {
        assertThat(greatCircleBearing(0.0, 0.0, 0.0, -10.0)).isWithin(tol).of(270.0)
    }

    @Test
    fun `heading due north along a meridian is 0 degrees`() {
        assertThat(greatCircleBearing(10.0, 5.0, 40.0, 5.0)).isWithin(tol).of(0.0)
    }

    @Test
    fun `heading due south along a meridian is 180 degrees`() {
        assertThat(greatCircleBearing(40.0, 5.0, 10.0, 5.0)).isWithin(tol).of(180.0)
    }

    @Test
    fun `bearing is always normalized into 0 to 360`() {
        // A south-west leg would produce a negative raw atan2 result; it must be
        // wrapped up into the [0,360) range rather than returned negative.
        val b = greatCircleBearing(40.0, 5.0, 10.0, -20.0)
        assertThat(b).isAtLeast(0.0)
        assertThat(b).isLessThan(360.0)
        assertThat(b).isGreaterThan(180.0) // south-and-west => third quadrant
    }

    // ---------- longPathBearing ----------

    @Test
    fun `long path is the reciprocal of the short path`() {
        assertThat(longPathBearing(47.0)).isWithin(tol).of(227.0)
        assertThat(longPathBearing(0.0)).isWithin(tol).of(180.0)
    }

    @Test
    fun `long path wraps past 360 back into range`() {
        // 270 + 180 = 450 -> 90
        assertThat(longPathBearing(270.0)).isWithin(tol).of(90.0)
    }

    // ---------- normalizeHeadingDeg ----------

    @Test
    fun `rounding 359 point 7 wraps to 0 rather than 360`() {
        assertThat(normalizeHeadingDeg(359.7)).isEqualTo(0)
    }

    @Test
    fun `rounding stays within 0 to 359`() {
        assertThat(normalizeHeadingDeg(0.4)).isEqualTo(0)
        assertThat(normalizeHeadingDeg(46.6)).isEqualTo(47)
        assertThat(normalizeHeadingDeg(359.4)).isEqualTo(359)
    }

    // ---------- formatHeading ----------

    @Test
    fun `format appends a degree sign`() {
        assertThat(formatHeading(47)).isEqualTo("47°")
        assertThat(formatHeading(0)).isEqualTo("0°")
    }
}
