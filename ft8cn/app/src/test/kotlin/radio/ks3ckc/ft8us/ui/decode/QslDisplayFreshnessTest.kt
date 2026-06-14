package radio.ks3ckc.ft8us.ui.decode

import com.bg7yoz.ft8cn.Ft8Message
import com.bg7yoz.ft8cn.GeneralVariables
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8us.ui.components.QsoStatus

/**
 * Tests that [resolveQsoStatus] uses the live QSL callsign list, not just the
 * cached [Ft8Message.isQSL_Callsign] field which is set at decode time.
 *
 * The user reported that a station completed mid-session would not show the
 * "worked" marker on the decode list even though the waterfall (which uses a
 * live check) showed it correctly.
 */
@RunWith(RobolectricTestRunner::class)
class QslDisplayFreshnessTest {

    private var savedCallsign: String? = null
    private var savedHighlightPota = false
    private var savedHighlightNewDxcc = false
    private var savedHighlightNewGrid = false
    private var savedHighlightNewBand = false
    private var savedHighlightWorked = false
    private lateinit var savedQslList: ArrayList<String>

    @Before
    fun setUp() {
        savedCallsign = GeneralVariables.myCallsign
        savedHighlightPota = GeneralVariables.highlightPota
        savedHighlightNewDxcc = GeneralVariables.highlightNewDxcc
        savedHighlightNewGrid = GeneralVariables.highlightNewGrid
        savedHighlightNewBand = GeneralVariables.highlightNewBand
        savedHighlightWorked = GeneralVariables.highlightWorked
        savedQslList = ArrayList(GeneralVariables.QSL_Callsign_list)

        GeneralVariables.myCallsign = "W1AW"
        GeneralVariables.QSL_Callsign_list.clear()
        // Disable all highlight toggles except "worked" so the when-chain
        // falls through to the WORKED branch we're testing.
        GeneralVariables.highlightPota = false
        GeneralVariables.highlightNewDxcc = false
        GeneralVariables.highlightNewGrid = false
        GeneralVariables.highlightNewBand = false
        GeneralVariables.highlightWorked = true
    }

    @After
    fun tearDown() {
        GeneralVariables.myCallsign = savedCallsign
        GeneralVariables.highlightPota = savedHighlightPota
        GeneralVariables.highlightNewDxcc = savedHighlightNewDxcc
        GeneralVariables.highlightNewGrid = savedHighlightNewGrid
        GeneralVariables.highlightNewBand = savedHighlightNewBand
        GeneralVariables.highlightWorked = savedHighlightWorked
        GeneralVariables.QSL_Callsign_list.clear()
        GeneralVariables.QSL_Callsign_list.addAll(savedQslList)
    }

    private fun makeCqMessage(from: String, cachedQsl: Boolean): Ft8Message {
        val msg = Ft8Message("CQ", from, "EM73")
        msg.isQSL_Callsign = cachedQsl
        return msg
    }

    @Test
    fun `cached field true — shows WORKED`() {
        val msg = makeCqMessage("K5ABC", cachedQsl = true)
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.WORKED)
    }

    @Test
    fun `cached field false but live list has callsign — shows WORKED`() {
        // Simulates a QSO that completed after the message was decoded:
        // the cached field is false, but the live list now includes the call.
        GeneralVariables.QSL_Callsign_list.add("K5ABC")
        val msg = makeCqMessage("K5ABC", cachedQsl = false)
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.WORKED)
    }

    @Test
    fun `both false — shows CQ`() {
        val msg = makeCqMessage("K5ABC", cachedQsl = false)
        assertThat(resolveQsoStatus(msg)).isEqualTo(QsoStatus.CQ)
    }
}
