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
 * Unit-tests the shared [isNewGridStation] predicate that backs both the
 * decode row's NEW_GRID pill and the "New Grid" decode filter, so the two never
 * disagree on what counts as an unworked grid.
 *
 * Robolectric is needed only because [Ft8Message] reaches android.util.Log on
 * construction; the logic under test is pure.
 */
@RunWith(RobolectricTestRunner::class)
class NewGridTest {

    private var savedHighlightPota = false
    private var savedHighlightNewDxcc = false
    private var savedHighlightNewGrid = false
    private var savedHighlightNewBand = false
    private var savedHighlightWorked = false

    @Before
    fun setUp() {
        savedHighlightPota = GeneralVariables.highlightPota
        savedHighlightNewDxcc = GeneralVariables.highlightNewDxcc
        savedHighlightNewGrid = GeneralVariables.highlightNewGrid
        savedHighlightNewBand = GeneralVariables.highlightNewBand
        savedHighlightWorked = GeneralVariables.highlightWorked
        GeneralVariables.QSL_Grid_list.clear()
        GeneralVariables.QSL_Callsign_list.clear()
    }

    @After
    fun tearDown() {
        GeneralVariables.highlightPota = savedHighlightPota
        GeneralVariables.highlightNewDxcc = savedHighlightNewDxcc
        GeneralVariables.highlightNewGrid = savedHighlightNewGrid
        GeneralVariables.highlightNewBand = savedHighlightNewBand
        GeneralVariables.highlightWorked = savedHighlightWorked
        GeneralVariables.QSL_Grid_list.clear()
        GeneralVariables.QSL_Callsign_list.clear()
    }

    private fun cqFromGrid(from: String, grid: String?): Ft8Message =
        Ft8Message("CQ", from, "TEST").apply { maidenGrid = grid }

    @Test
    fun unworkedFourCharGrid_isNew() {
        assertThat(isNewGridStation(cqFromGrid("K1ABC", "FN42"))).isTrue()
    }

    @Test
    fun workedGrid_isNotNew() {
        GeneralVariables.QSL_Grid_list.add("FN42")
        assertThat(isNewGridStation(cqFromGrid("K1ABC", "FN42"))).isFalse()
    }

    @Test
    fun workedGridMatchIsCaseAndSubSquareInsensitive() {
        // checkQSLGrid keys on the upper-cased 4-char field, so a 6-char decode
        // from the same square is still "worked".
        GeneralVariables.QSL_Grid_list.add("FN42")
        assertThat(isNewGridStation(cqFromGrid("K1ABC", "fn42ab"))).isFalse()
    }

    @Test
    fun missingOrTooShortGrid_isNotNew() {
        assertThat(isNewGridStation(cqFromGrid("K1ABC", null))).isFalse()
        assertThat(isNewGridStation(cqFromGrid("K1ABC", ""))).isFalse()
        assertThat(isNewGridStation(cqFromGrid("K1ABC", "FN"))).isFalse()
    }

    @Test
    fun newGridPill_surfacesWhenHighlightEnabled() {
        GeneralVariables.highlightPota = false
        GeneralVariables.highlightNewDxcc = false
        GeneralVariables.highlightNewGrid = true
        GeneralVariables.highlightNewBand = false
        GeneralVariables.highlightWorked = false

        assertThat(resolveQsoStatus(cqFromGrid("K1ABC", "FN42")))
            .isEqualTo(QsoStatus.NEW_GRID)
    }
}
