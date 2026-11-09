package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.FT8Common
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.database.OperationBand
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [buildBandGroups] and [OperationBand.getVisibleBandIndices] list configured
 * dials only. The ad-hoc slot that [OperationBand.getIndexByFreq] keeps for an
 * off-plan rig frequency must not show up as a band tile or alternate chip
 * (#978: SSB tuning filled the 20m alternates row with hundreds of 14.xxx chips).
 */
@RunWith(RobolectricTestRunner::class)
class FrequencyPickerBandGroupsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var savedBands: List<OperationBand.Band>
    private var savedMode = FT8Common.FT8_MODE
    private lateinit var savedExcluded: HashSet<String>

    @Before
    fun setUp() {
        savedBands = ArrayList(OperationBand.bandList)
        savedMode = GeneralVariables.operatingMode
        savedExcluded = HashSet(GeneralVariables.excludedBands)
        OperationBand.bandList.clear()
        GeneralVariables.operatingMode = FT8Common.FT8_MODE
        GeneralVariables.excludedBands = HashSet()
        OperationBand.bandList.add(OperationBand.Band(10_136_000L, "30m").apply { marked = true })
        OperationBand.bandList.add(OperationBand.Band(14_074_000L, "20m").apply { marked = true })
        OperationBand.bandList.add(OperationBand.Band(14_090_000L, "20m"))
    }

    @After
    fun tearDown() {
        OperationBand.bandList.clear()
        OperationBand.bandList.addAll(savedBands)
        GeneralVariables.operatingMode = savedMode
        GeneralVariables.excludedBands = savedExcluded
    }

    @Test
    fun adHocSlot_isNotAnAlternate() {
        for (i in 0 until 500) {
            OperationBand.getIndexByFreq(14_150_000L + i * 10L)
        }
        val twenty = buildBandGroups().single { it.waveLength == "20m" }
        assertThat(twenty.primaryIndex).isEqualTo(1)
        assertThat(twenty.alternates).containsExactly(2 to 14_090_000L)
    }

    @Test
    fun adHocSlotOnUnlistedBand_getsNoTile() {
        // 23 MHz is "13m" -- no configured dial, so no group at all.
        OperationBand.getIndexByFreq(23_000_000L)
        assertThat(buildBandGroups().map { it.waveLength }).containsExactly("30m", "20m").inOrder()
    }

    @Test
    fun adHocSlot_isNotInVisibleBandIndices() {
        val slot = OperationBand.getIndexByFreq(14_200_000L)
        assertThat(OperationBand.getVisibleBandIndices()).containsExactly(0, 1, 2).inOrder()
        assertThat(OperationBand.getVisibleBandIndices()).doesNotContain(slot)
    }

    @Test
    fun currentIndexOnAdHocSlot_rendersConfiguredDialsOnly() {
        // The rig is off-plan, so bandListIndex points at the ad-hoc slot. The sheet
        // must still render, with the 20m tile on its primary dial (no "ALT" swap to
        // the slot's frequency) and no chip for the slot.
        val slot = OperationBand.getIndexByFreq(14_200_000L)
        val groups = buildBandGroups()
        composeRule.setContent {
            FrequencyPickerContent(groups = groups, currentBandIndex = slot, onSelect = {})
        }
        composeRule.onNodeWithText("20m").assertIsDisplayed()
        composeRule.onNodeWithText("14.074").assertIsDisplayed()
        composeRule.onAllNodesWithText("14.200").assertCountEquals(0)
    }
}
