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
 * Unit-tests the shared [isNewPrefixStation] predicate (and its NEW_PREFIX pill /
 * "New Prefix" filter) that back the Worked-All-Prefixes (WPX) chase, so the pill
 * and the filter never disagree on what counts as an unworked prefix.
 *
 * Robolectric is needed only because [Ft8Message] reaches android.util.Log on
 * construction; the logic under test is pure.
 */
@RunWith(RobolectricTestRunner::class)
class NewPrefixTest {

    private var savedHighlightPota = false
    private var savedHighlightNewDxcc = false
    private var savedHighlightNewZone = false
    private var savedHighlightNewState = false
    private var savedHighlightNewGrid = false
    private var savedHighlightNewPrefix = false
    private var savedHighlightNewBand = false
    private var savedHighlightWorked = false

    @Before
    fun setUp() {
        savedHighlightPota = GeneralVariables.highlightPota
        savedHighlightNewDxcc = GeneralVariables.highlightNewDxcc
        savedHighlightNewZone = GeneralVariables.highlightNewZone
        savedHighlightNewState = GeneralVariables.highlightNewState
        savedHighlightNewGrid = GeneralVariables.highlightNewGrid
        savedHighlightNewPrefix = GeneralVariables.highlightNewPrefix
        savedHighlightNewBand = GeneralVariables.highlightNewBand
        savedHighlightWorked = GeneralVariables.highlightWorked
        GeneralVariables.QSL_Prefix_list.clear()
        GeneralVariables.QSL_Callsign_list.clear()
    }

    @After
    fun tearDown() {
        GeneralVariables.highlightPota = savedHighlightPota
        GeneralVariables.highlightNewDxcc = savedHighlightNewDxcc
        GeneralVariables.highlightNewZone = savedHighlightNewZone
        GeneralVariables.highlightNewState = savedHighlightNewState
        GeneralVariables.highlightNewGrid = savedHighlightNewGrid
        GeneralVariables.highlightNewPrefix = savedHighlightNewPrefix
        GeneralVariables.highlightNewBand = savedHighlightNewBand
        GeneralVariables.highlightWorked = savedHighlightWorked
        GeneralVariables.QSL_Prefix_list.clear()
        GeneralVariables.QSL_Callsign_list.clear()
    }

    private fun cqFrom(from: String): Ft8Message = Ft8Message("CQ", from, "TEST")

    @Test
    fun unworkedPrefix_isNew() {
        assertThat(isNewPrefixStation(cqFrom("W1ABC"))).isTrue()
    }

    @Test
    fun workedPrefix_isNotNew() {
        GeneralVariables.QSL_Prefix_list.add("W1")
        assertThat(isNewPrefixStation(cqFrom("W1ABC"))).isFalse()
    }

    @Test
    fun differentSuffixSamePrefix_isNotNew() {
        // Prefix keys on "W1", not the whole call, so a different W1 station is
        // no longer a new prefix once any W1 has been worked.
        GeneralVariables.QSL_Prefix_list.add("W1")
        assertThat(isNewPrefixStation(cqFrom("W1XYZ"))).isFalse()
        // ...but a different prefix from the same country is still new.
        assertThat(isNewPrefixStation(cqFrom("W5XYZ"))).isTrue()
    }

    @Test
    fun nonCallsignFrom_isNotNew() {
        // A token that yields no WPX prefix must never be flagged.
        assertThat(isNewPrefixStation(cqFrom("73"))).isFalse()
    }

    @Test
    fun newPrefixPill_surfacesWhenHighlightEnabled() {
        GeneralVariables.highlightPota = false
        GeneralVariables.highlightNewDxcc = false
        GeneralVariables.highlightNewZone = false
        GeneralVariables.highlightNewState = false
        GeneralVariables.highlightNewGrid = false
        GeneralVariables.highlightNewPrefix = true
        GeneralVariables.highlightNewBand = false
        GeneralVariables.highlightWorked = false

        assertThat(resolveQsoStatus(cqFrom("DL1ABC")))
            .isEqualTo(QsoStatus.NEW_PREFIX)
    }

    @Test
    fun newPrefixPill_suppressedWhenPrefixWorked() {
        GeneralVariables.highlightPota = false
        GeneralVariables.highlightNewDxcc = false
        GeneralVariables.highlightNewZone = false
        GeneralVariables.highlightNewState = false
        GeneralVariables.highlightNewGrid = false
        GeneralVariables.highlightNewPrefix = true
        GeneralVariables.highlightNewBand = false
        GeneralVariables.highlightWorked = false
        GeneralVariables.QSL_Prefix_list.add("DL1")

        // Worked prefix falls through to the plain CQ pill.
        assertThat(resolveQsoStatus(cqFrom("DL1ABC")))
            .isEqualTo(QsoStatus.CQ)
    }

    @Test
    fun newPrefixFilter_keepsOnlyUnworkedCqPrefixes() {
        GeneralVariables.QSL_Prefix_list.add("W1")
        val messages = listOf(
            cqFrom("W1ABC"),                        // worked prefix -> dropped
            cqFrom("VE3XYZ"),                       // new prefix, CQ -> kept
            Ft8Message("K9AAA", "DL1ABC", "TEST"),  // new prefix but not a CQ -> dropped
        )
        val filtered = filterMessages(messages, "New Prefix")
        assertThat(filtered.map { it.callsignFrom }).containsExactly("VE3XYZ")
    }
}
