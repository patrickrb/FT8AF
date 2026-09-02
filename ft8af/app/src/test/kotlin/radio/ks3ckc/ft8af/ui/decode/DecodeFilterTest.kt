package radio.ks3ckc.ft8af.ui.decode

import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit-tests the pure [filterMessages] decode-list filter. Robolectric is
 * needed only because [Ft8Message] reaches android.util.Log on construction.
 *
 * These exercise the chip filters that depend on message content plus the
 * operator's worked-grid list (kept at its defaults except where a test seeds
 * it explicitly): "All" passes everything through, "CQ Calls" keeps only CQ
 * messages, and "New Grid" keeps only CQ stations from an unworked grid.
 */
@RunWith(RobolectricTestRunner::class)
class DecodeFilterTest {

    private fun cq(from: String) = Ft8Message("CQ", from, "FN42")
    private fun directed(to: String, from: String) = Ft8Message(to, from, "FN42")

    /** A CQ message carrying a decoded Maidenhead grid (the field the filter keys on). */
    private fun cqFromGrid(from: String, grid: String) = cq(from).apply { maidenGrid = grid }

    @Before
    fun clearWorkedGrids() {
        GeneralVariables.QSL_Grid_list.clear()
    }

    @After
    fun tearDown() {
        GeneralVariables.QSL_Grid_list.clear()
    }

    @Test
    fun all_returnsEveryMessage() {
        val messages = listOf(cq("K1ABC"), directed("W1AW", "K2DEF"))

        val result = filterMessages(messages, "All")

        assertThat(result).hasSize(2)
        assertThat(result.map { it.callsignFrom }).containsExactly("K1ABC", "K2DEF")
    }

    @Test
    fun cqCalls_keepsOnlyCqMessages() {
        val cqMsg = cq("K1ABC")
        val directedMsg = directed("W1AW", "K2DEF")

        val result = filterMessages(listOf(cqMsg, directedMsg), "CQ Calls")

        assertThat(result).containsExactly(cqMsg)
    }

    @Test
    fun newState_keepsCqFromUnworkedState() {
        // fromNewState is the decode-time "unworked US state" flag; the filter
        // keeps only CQ stations that carry it.
        val fresh = cq("K1ABC").apply { fromNewState = true }
        val worked = cq("K2DEF").apply { fromNewState = false }

        val result = filterMessages(listOf(fresh, worked), "New State")

        assertThat(result).containsExactly(fresh)
    }

    @Test
    fun newState_dropsDirectedMessages() {
        // A directed reply isn't a callable CQ even if it's from a new state.
        val directedNewState = directed("W1AW", "K2DEF").apply { fromNewState = true }

        val result = filterMessages(listOf(directedNewState), "New State")

        assertThat(result).isEmpty()
    }

    @Test
    fun newGrid_keepsCqFromUnworkedGrid() {
        // No grids worked yet, so every 4-char grid counts as new.
        val cqMsg = cqFromGrid("K1ABC", "FN42")

        val result = filterMessages(listOf(cqMsg), "New Grid")

        assertThat(result).containsExactly(cqMsg)
    }

    @Test
    fun newGrid_dropsCqFromAlreadyWorkedGrid() {
        GeneralVariables.QSL_Grid_list.add("FN42")
        val worked = cqFromGrid("K1ABC", "FN42")   // grid already logged
        val fresh = cqFromGrid("K2DEF", "EN37")     // still needed

        val result = filterMessages(listOf(worked, fresh), "New Grid")

        assertThat(result).containsExactly(fresh)
    }

    @Test
    fun newZone_keepsCqFromUnworkedZone() {
        // fromCq is the decode-time "unworked CQ zone" flag; the filter keeps
        // only CQ stations that carry it.
        val fresh = cq("K1ABC").apply { fromCq = true }
        val worked = cq("K2DEF").apply { fromCq = false }

        val result = filterMessages(listOf(fresh, worked), "New Zone")

        assertThat(result).containsExactly(fresh)
    }

    @Test
    fun newZone_dropsDirectedMessages() {
        // A directed reply isn't a callable CQ even if it's from a new zone.
        val directedNewZone = directed("W1AW", "K2DEF").apply { fromCq = true }

        val result = filterMessages(listOf(directedNewZone), "New Zone")

        assertThat(result).isEmpty()
    }

    @Test
    fun newGrid_dropsDirectedAndGridlessMessages() {
        val directedNewGrid = directed("W1AW", "K2DEF").apply { maidenGrid = "EN37" }
        val cqNoGrid = cq("K3GHI").apply { maidenGrid = null }

        val result = filterMessages(listOf(directedNewGrid, cqNoGrid), "New Grid")

        // Directed messages aren't callable CQs; a CQ without a grid can't be
        // judged new — both are excluded.
        assertThat(result).isEmpty()
    }
}
