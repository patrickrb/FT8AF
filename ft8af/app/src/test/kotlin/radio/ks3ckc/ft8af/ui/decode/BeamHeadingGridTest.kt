package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [computeBeamHeadings], which decodes both grids through
 * [com.k1af.ft8af.maidenhead.MaidenheadGrid] (Play-Services `LatLng`) and so
 * needs Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class BeamHeadingGridTest {

    @Test
    fun `returns null when either grid is missing`() {
        assertThat(computeBeamHeadings(null, "FN42")).isNull()
        assertThat(computeBeamHeadings("FN42", null)).isNull()
        assertThat(computeBeamHeadings("", "FN42")).isNull()
        assertThat(computeBeamHeadings("FN42", "")).isNull()
    }

    @Test
    fun `returns null when a grid cannot be parsed`() {
        // "ABC" is an unsupported length -> gridToLatLng returns null.
        assertThat(computeBeamHeadings("FN42", "ABC")).isNull()
    }

    @Test
    fun `returns null for identical grids (bearing to yourself is undefined)`() {
        assertThat(computeBeamHeadings("FN42", "FN42")).isNull()
    }

    @Test
    fun `long path is always the reciprocal of the short path`() {
        val h = computeBeamHeadings("FN42", "IO91")
        assertThat(h).isNotNull()
        val expectedLp = (h!!.shortPathDeg + 180) % 360
        assertThat(h.longPathDeg).isEqualTo(expectedLp)
    }

    @Test
    fun `headings stay within 0 to 359`() {
        val h = computeBeamHeadings("FN42", "JO65")
        assertThat(h).isNotNull()
        assertThat(h!!.shortPathDeg).isIn(0..359)
        assertThat(h.longPathDeg).isIn(0..359)
    }

    @Test
    fun `Boston to London beams roughly north-east`() {
        // Boston FN42 -> London IO91 is a classic north-easterly great-circle
        // heading (~50 deg); allow slack for grid-centre quantisation.
        val h = computeBeamHeadings("FN42", "IO91")
        assertThat(h).isNotNull()
        assertThat(h!!.shortPathDeg).isIn(30..70)
    }

    @Test
    fun `a station due east reads near 90 degrees`() {
        // Two equatorial grids on the same field row: JJ00 (~0N,20E) ->
        // KJ00 (~0N,40E) is due east.
        val h = computeBeamHeadings("JJ00", "KJ00")
        assertThat(h).isNotNull()
        assertThat(h!!.shortPathDeg).isIn(80..100)
    }

    // ---------- computeBeamHeadingText (decode-row wrapper) ----------

    @Test
    fun `row heading text is blank when the operator grid is unset`() {
        GeneralVariables.setMyMaidenheadGrid("")
        val msg = Ft8Message(0).apply { maidenGrid = "IO91" }
        assertThat(computeBeamHeadingText(msg)).isEmpty()
    }

    @Test
    fun `row heading text is blank when the message has no grid`() {
        GeneralVariables.setMyMaidenheadGrid("FN42")
        val msg = Ft8Message(0).apply { maidenGrid = null }
        assertThat(computeBeamHeadingText(msg)).isEmpty()
    }

    @Test
    fun `row heading text is the formatted short-path bearing`() {
        GeneralVariables.setMyMaidenheadGrid("FN42")
        val msg = Ft8Message(0).apply { maidenGrid = "IO91" }
        val expected = formatHeading(computeBeamHeadings("FN42", "IO91")!!.shortPathDeg)
        assertThat(computeBeamHeadingText(msg)).isEqualTo(expected)
    }
}
