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
 * Unit-tests the NEW_STATE (Worked All States) decode-row pill wired off the
 * decode-time [Ft8Message.fromNewState] flag, plus its priority relative to the
 * neighbouring NEW_ZONE and NEW_GRID badges. The filter side is covered in
 * [DecodeFilterTest]; this keeps the two in agreement on what counts as an
 * unworked US state.
 *
 * Robolectric is needed only because [Ft8Message] reaches android.util.Log on
 * construction; the logic under test is pure.
 */
@RunWith(RobolectricTestRunner::class)
class NewStateTest {

    private var savedHighlightPota = false
    private var savedHighlightNewDxcc = false
    private var savedHighlightNewZone = false
    private var savedHighlightNewState = false
    private var savedHighlightNewGrid = false
    private var savedHighlightNewBand = false
    private var savedHighlightWorked = false

    @Before
    fun setUp() {
        savedHighlightPota = GeneralVariables.highlightPota
        savedHighlightNewDxcc = GeneralVariables.highlightNewDxcc
        savedHighlightNewZone = GeneralVariables.highlightNewZone
        savedHighlightNewState = GeneralVariables.highlightNewState
        savedHighlightNewGrid = GeneralVariables.highlightNewGrid
        savedHighlightNewBand = GeneralVariables.highlightNewBand
        savedHighlightWorked = GeneralVariables.highlightWorked
        GeneralVariables.QSL_Grid_list.clear()
        GeneralVariables.QSL_Callsign_list.clear()
        // Isolate the branch under test: only the state highlight is on unless a
        // test opts another in.
        GeneralVariables.highlightPota = false
        GeneralVariables.highlightNewDxcc = false
        GeneralVariables.highlightNewZone = false
        GeneralVariables.highlightNewState = true
        GeneralVariables.highlightNewGrid = false
        GeneralVariables.highlightNewBand = false
        GeneralVariables.highlightWorked = false
    }

    @After
    fun tearDown() {
        GeneralVariables.highlightPota = savedHighlightPota
        GeneralVariables.highlightNewDxcc = savedHighlightNewDxcc
        GeneralVariables.highlightNewZone = savedHighlightNewZone
        GeneralVariables.highlightNewState = savedHighlightNewState
        GeneralVariables.highlightNewGrid = savedHighlightNewGrid
        GeneralVariables.highlightNewBand = savedHighlightNewBand
        GeneralVariables.highlightWorked = savedHighlightWorked
        GeneralVariables.QSL_Grid_list.clear()
        GeneralVariables.QSL_Callsign_list.clear()
    }

    private fun cq(from: String): Ft8Message = Ft8Message("CQ", from, "TEST")

    @Test
    fun newStatePill_surfacesWhenHighlightEnabledAndFromNewState() {
        val msg = cq("K1ABC").apply { fromNewState = true }
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.NEW_STATE)
    }

    @Test
    fun newStatePill_hiddenWhenHighlightDisabled() {
        GeneralVariables.highlightNewState = false
        val msg = cq("K1ABC").apply { fromNewState = true }
        // Falls through to the plain CQ badge instead of NEW_STATE.
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.CQ)
    }

    @Test
    fun newStatePill_hiddenWhenStateAlreadyWorked() {
        val msg = cq("K1ABC").apply { fromNewState = false }
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.CQ)
    }

    @Test
    fun newZone_outranksNewState() {
        // Only 40 CQ zones exist, so an unworked one is rarer than an unworked
        // state — NEW_ZONE wins when both apply.
        GeneralVariables.highlightNewZone = true
        val msg = cq("K1ABC").apply {
            fromCq = true
            fromNewState = true
        }
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.NEW_ZONE)
    }

    @Test
    fun newState_outranksNewGrid() {
        // WAS is far more chased than a bare new grid field, so NEW_STATE wins
        // when a station is both a new state and a new grid.
        GeneralVariables.highlightNewGrid = true
        val msg = cq("K1ABC").apply {
            fromNewState = true
            maidenGrid = "FN42" // unworked grid (QSL_Grid_list is empty)
        }
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.NEW_STATE)
    }
}
