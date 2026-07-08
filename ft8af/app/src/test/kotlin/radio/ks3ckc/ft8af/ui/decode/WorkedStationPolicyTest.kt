package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import radio.ks3ckc.ft8af.ui.components.QsoStatus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests for worked-station handling as wired into [GeneralVariables]
 * and the decode-list helpers ([isWorkedStation], [effectiveWorkedMode],
 * [isHiddenAsWorked], [resolveQsoStatus], [filterMessages]). Robolectric is needed
 * because [Ft8Message] reaches android.util.Log on construction and the code under
 * test reads GeneralVariables state.
 */
@RunWith(RobolectricTestRunner::class)
class WorkedStationPolicyTest {

    private fun cq(from: String) = Ft8Message("CQ", from, "FN42")
    private fun directed(to: String, from: String) = Ft8Message(to, from, "FN42")

    @Before
    fun reset() {
        // Restore defaults so each test starts from the shipped configuration.
        GeneralVariables.highlightWorked = true
        GeneralVariables.workedStationMode = 0 // HIGHLIGHT
        GeneralVariables.workedStationScope = 0 // ON_BAND
        GeneralVariables.highlightNewDxcc = true
        GeneralVariables.highlightNewGrid = false
        GeneralVariables.highlightNewBand = true
        GeneralVariables.highlightPota = true
        GeneralVariables.myCallsign = ""
        GeneralVariables.QSL_Callsign_list = arrayListOf()
        GeneralVariables.QSL_Callsign_list_other_band = arrayListOf()
        GeneralVariables.QSL_Callsign_list_today = arrayListOf()
        GeneralVariables.addWorkedStationList("")
    }

    // --- scope resolution against GeneralVariables ---------------------------

    @Test
    fun onBandScope_usesCurrentBandQslList() {
        GeneralVariables.workedStationScope = 0 // ON_BAND
        GeneralVariables.QSL_Callsign_list = arrayListOf("K1ABC")

        assertThat(isWorkedStation(cq("K1ABC"))).isTrue()
        assertThat(isWorkedStation(cq("K2DEF"))).isFalse()
    }

    @Test
    fun beforeScope_includesOtherBands() {
        GeneralVariables.QSL_Callsign_list_other_band = arrayListOf("K3GHI")

        GeneralVariables.workedStationScope = 0 // ON_BAND — other band doesn't count
        assertThat(isWorkedStation(cq("K3GHI"))).isFalse()

        GeneralVariables.workedStationScope = 1 // BEFORE — any band counts
        assertThat(isWorkedStation(cq("K3GHI"))).isTrue()
    }

    @Test
    fun todayScope_usesTodayList() {
        GeneralVariables.workedStationScope = 2 // TODAY
        GeneralVariables.QSL_Callsign_list_today = arrayListOf("K4JKL")

        assertThat(isWorkedStation(cq("K4JKL"))).isTrue()
        assertThat(isWorkedStation(cq("K1ABC"))).isFalse()
    }

    @Test
    fun fromListScope_usesUserList() {
        GeneralVariables.workedStationScope = 3 // FROM_LIST
        GeneralVariables.addWorkedStationList("k5mno, K6PQR")

        assertThat(GeneralVariables.getWorkedStationList()).isEqualTo("K5MNO,K6PQR")
        assertThat(isWorkedStation(cq("K5MNO"))).isTrue()
        assertThat(isWorkedStation(cq("K6PQR"))).isTrue()
        assertThat(isWorkedStation(cq("K1ABC"))).isFalse()
    }

    // --- effective mode / master toggle -------------------------------------

    @Test
    fun effectiveMode_isNullWhenMasterToggleOff() {
        GeneralVariables.highlightWorked = false
        GeneralVariables.workedStationMode = 2 // HIDE, but disabled
        assertThat(effectiveWorkedMode()).isNull()
    }

    @Test
    fun effectiveMode_reflectsSelectedModeWhenEnabled() {
        GeneralVariables.highlightWorked = true
        GeneralVariables.workedStationMode = 1 // IGNORE
        assertThat(effectiveWorkedMode()).isEqualTo(WorkedStationMode.IGNORE)
    }

    // --- highlight vs ignore in resolveQsoStatus ----------------------------

    @Test
    fun highlightMode_tagsWorkedStation() {
        GeneralVariables.QSL_Callsign_list = arrayListOf("K1ABC")
        GeneralVariables.workedStationMode = 0 // HIGHLIGHT

        assertThat(resolveQsoStatus(cq("K1ABC"))).isEqualTo(QsoStatus.WORKED)
    }

    @Test
    fun ignoreMode_leavesWorkedStationAsPlainCq() {
        GeneralVariables.QSL_Callsign_list = arrayListOf("K1ABC")
        GeneralVariables.workedStationMode = 1 // IGNORE — visible but not tagged

        assertThat(resolveQsoStatus(cq("K1ABC"))).isEqualTo(QsoStatus.CQ)
    }

    @Test
    fun disabledMasterToggle_doesNotTagWorkedStation() {
        GeneralVariables.QSL_Callsign_list = arrayListOf("K1ABC")
        GeneralVariables.highlightWorked = false

        assertThat(resolveQsoStatus(cq("K1ABC"))).isEqualTo(QsoStatus.CQ)
    }

    // --- hide mode ----------------------------------------------------------

    @Test
    fun hideMode_dropsWorkedStationsFromList() {
        GeneralVariables.QSL_Callsign_list = arrayListOf("K1ABC")
        GeneralVariables.workedStationMode = 2 // HIDE

        val result = filterMessages(listOf(cq("K1ABC"), cq("K2DEF")), "All")

        assertThat(result.map { it.callsignFrom }).containsExactly("K2DEF")
    }

    @Test
    fun hideMode_keepsStationsCallingMe() {
        GeneralVariables.myCallsign = "W1AW"
        GeneralVariables.QSL_Callsign_list = arrayListOf("K1ABC")
        GeneralVariables.workedStationMode = 2 // HIDE

        // K1ABC is worked, but it's answering our CQ — must stay visible.
        val result = filterMessages(listOf(directed("W1AW", "K1ABC")), "All")

        assertThat(result.map { it.callsignFrom }).containsExactly("K1ABC")
    }

    @Test
    fun hideMode_disabledByMasterToggle_showsWorkedStations() {
        GeneralVariables.QSL_Callsign_list = arrayListOf("K1ABC")
        GeneralVariables.workedStationMode = 2 // HIDE
        GeneralVariables.highlightWorked = false // master off — hiding inactive

        val result = filterMessages(listOf(cq("K1ABC")), "All")

        assertThat(result.map { it.callsignFrom }).containsExactly("K1ABC")
    }

    @Test
    fun highlightMode_doesNotHideWorkedStations() {
        GeneralVariables.QSL_Callsign_list = arrayListOf("K1ABC")
        GeneralVariables.workedStationMode = 0 // HIGHLIGHT keeps them in the list

        val result = filterMessages(listOf(cq("K1ABC")), "All")

        assertThat(result.map { it.callsignFrom }).containsExactly("K1ABC")
    }
}
