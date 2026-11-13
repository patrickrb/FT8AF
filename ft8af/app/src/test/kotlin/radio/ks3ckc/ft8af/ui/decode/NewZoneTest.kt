package radio.ks3ckc.ft8af.ui.decode

import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.ui.components.QsoStatus

/**
 * Unit-tests the NEW_ZONE (Worked All Zones) decode-row pill wired off the
 * decode-time [Ft8Message.fromCq] flag, plus its priority relative to the
 * neighbouring NEW (DXCC) and NEW_GRID badges. The filter side is covered in
 * [DecodeFilterTest]; this keeps the two in agreement on what counts as an
 * unworked CQ zone.
 *
 * Robolectric is needed only because [Ft8Message] reaches android.util.Log on
 * construction; the logic under test is pure.
 */
@RunWith(RobolectricTestRunner::class)
class NewZoneTest {

    private var savedHighlightPota = false
    private var savedHighlightNewDxcc = false
    private var savedHighlightNewZone = false
    private var savedHighlightNewGrid = false
    private var savedHighlightNewBand = false
    private var savedHighlightWorked = false

    @Before
    fun setUp() {
        savedHighlightPota = GeneralVariables.highlightPota
        savedHighlightNewDxcc = GeneralVariables.highlightNewDxcc
        savedHighlightNewZone = GeneralVariables.highlightNewZone
        savedHighlightNewGrid = GeneralVariables.highlightNewGrid
        savedHighlightNewBand = GeneralVariables.highlightNewBand
        savedHighlightWorked = GeneralVariables.highlightWorked
        GeneralVariables.QSL_Grid_list.clear()
        GeneralVariables.QSL_Callsign_list.clear()
        // Isolate the branch under test: only the zone highlight is on unless a
        // test opts another in.
        GeneralVariables.highlightPota = false
        GeneralVariables.highlightNewDxcc = false
        GeneralVariables.highlightNewZone = true
        GeneralVariables.highlightNewGrid = false
        GeneralVariables.highlightNewBand = false
        GeneralVariables.highlightWorked = false
    }

    @After
    fun tearDown() {
        GeneralVariables.highlightPota = savedHighlightPota
        GeneralVariables.highlightNewDxcc = savedHighlightNewDxcc
        GeneralVariables.highlightNewZone = savedHighlightNewZone
        GeneralVariables.highlightNewGrid = savedHighlightNewGrid
        GeneralVariables.highlightNewBand = savedHighlightNewBand
        GeneralVariables.highlightWorked = savedHighlightWorked
        GeneralVariables.QSL_Grid_list.clear()
        GeneralVariables.QSL_Callsign_list.clear()
    }

    private fun cq(from: String): Ft8Message = Ft8Message("CQ", from, "TEST")

    @Test
    fun newZonePill_surfacesWhenHighlightEnabledAndFromCq() {
        val msg = cq("K1ABC").apply { fromCq = true }
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.NEW_ZONE)
    }

    @Test
    fun newZonePill_hiddenWhenHighlightDisabled() {
        GeneralVariables.highlightNewZone = false
        val msg = cq("K1ABC").apply { fromCq = true }
        // Falls through to the plain CQ badge instead of NEW_ZONE.
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.CQ)
    }

    @Test
    fun newZonePill_hiddenWhenZoneAlreadyWorked() {
        val msg = cq("K1ABC").apply { fromCq = false }
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.CQ)
    }

    @Test
    fun newDxcc_outranksNewZone() {
        // A station that is both a new DXCC and a new zone shows the DXCC badge —
        // DXCC is the headline award and NEW is higher priority.
        GeneralVariables.highlightNewDxcc = true
        val msg = cq("K1ABC").apply {
            fromDxcc = true
            fromCq = true
        }
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.NEW)
    }

    @Test
    fun newZone_outranksNewGrid() {
        // Only 40 CQ zones exist, so an unworked one is rarer than an unworked
        // grid — NEW_ZONE wins when both apply.
        GeneralVariables.highlightNewGrid = true
        val msg = cq("K1ABC").apply {
            fromCq = true
            maidenGrid = "FN42" // unworked grid (QSL_Grid_list is empty)
        }
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.NEW_ZONE)
    }
}
