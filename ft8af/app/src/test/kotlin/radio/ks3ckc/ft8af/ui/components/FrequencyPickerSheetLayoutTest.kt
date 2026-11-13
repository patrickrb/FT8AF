package radio.ks3ckc.ft8af.ui.components

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.FT8Common
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout tests for the frequency picker's alternates list (#930): on a narrow
 * screen the alternate chips must wrap whole onto another line with every
 * label on one unclipped line, and wrapped rows must stay reachable by
 * scrolling on a short screen. The picker is hosted in [FT8AFBottomSheet] as in
 * the app, because the sheet owns the scroll: a picker that added its own
 * nested verticalScroll crashed on open (#972) while rendering fine on its own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrequencyPickerSheetLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // buildBandGroups() reads three process-wide statics. Robolectric shares them
    // across every test in the sandbox, so pin all three here and restore them
    // after, rather than trusting whatever an earlier test left behind.
    private lateinit var savedBandList: List<OperationBand.Band>
    private var savedOperatingMode = 0
    private lateinit var savedExcludedBands: Set<String>

    @Before
    fun pinBandStatics() {
        savedBandList = ArrayList(OperationBand.bandList)
        savedOperatingMode = GeneralVariables.operatingMode
        savedExcludedBands = HashSet(GeneralVariables.excludedBands)
        OperationBand.bandList.clear()
        OperationBand.bandList.add(OperationBand.Band("*:14074000:20m"))
        OperationBand.bandList.add(OperationBand.Band(" :14090000:20m"))
        OperationBand.bandList.add(OperationBand.Band("*:7074000:40m"))
        GeneralVariables.operatingMode = FT8Common.FT8_MODE
        GeneralVariables.excludedBands = HashSet()
    }

    @After
    fun restoreBandStatics() {
        OperationBand.bandList.clear()
        OperationBand.bandList.addAll(savedBandList)
        GeneralVariables.operatingMode = savedOperatingMode
        GeneralVariables.excludedBands = HashSet(savedExcludedBands)
    }

    private val twentyMetres = BandGroup(
        waveLength = "20m",
        primaryIndex = 0,
        primaryFreqHz = 14_074_000L,
        alternates = listOf(
            1 to 14_080_000L,
            2 to 14_090_000L,
            3 to 14_113_000L,
            4 to 14_140_000L,
        ),
    )

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /**
     * The label is laid out on a single line holding every character: not
     * broken onto a second (hidden, under maxLines = 1) line and not ellipsized.
     */
    private fun assertWholeTextOnOneLine(text: String) {
        val layout = textLayout(text)
        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(layout.getLineEnd(0, visibleEnd = true)).isEqualTo(text.length)
        assertThat(layout.isLineEllipsized(0)).isFalse()
    }

    @Test
    @Config(qualifiers = "w240dp-h640dp-xhdpi")
    fun narrowScreen_chipsWrapWholeWithSingleLineLabels() {
        composeRule.setContent {
            AlternatesList(groups = listOf(twentyMetres), currentBandIndex = 0, onChipClick = {})
        }

        val labels = twentyMetres.alternates.map { formatMhz(it.second) }
        for (label in labels) {
            assertWholeTextOnOneLine(label)
        }
        // Four chips don't fit beside the band label in 240dp, so they must
        // spill onto a second line rather than squeeze the last one.
        val tops = labels.map {
            composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }.toSet()
        assertThat(tops.size).isGreaterThan(1)
    }

    @Test
    @Config(qualifiers = "w240dp-h640dp-xhdpi")
    fun largeFontScale_bandLabelStaysOnOneUnclippedLine() {
        val group = twentyMetres.copy(waveLength = "2200m")
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                AlternatesList(groups = listOf(group), currentBandIndex = 0, onChipClick = {})
            }
        }

        assertWholeTextOnOneLine("2200m")
    }

    @Test
    @Config(qualifiers = "w640dp-h240dp-xhdpi")
    fun shortScreen_lastAlternateIsReachableByScrolling() {
        val groups = (0 until 10).map { band ->
            val base = (band + 1) * 1_000_000L * 3
            BandGroup(
                waveLength = "${band}m",
                primaryIndex = band * 10,
                primaryFreqHz = base,
                alternates = (1..3).map { alt -> band * 10 + alt to base + alt * 1_000L },
            )
        }
        val lastIndex = 9 * 10 + 3
        val lastLabel = formatMhz(30_000_000L + 3_000L)
        var picked: Int? = null
        composeRule.setContent {
            FT8AFBottomSheet(visible = true, onDismiss = {}) {
                FrequencyPickerContent(groups = groups, currentBandIndex = 0, onSelect = { picked = it })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.freq_show_alternates))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(lastLabel).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(lastLabel).performClick()

        assertThat(picked).isEqualTo(lastIndex)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun frequencyPickerSheet_opensWithoutCrashing() {
        // #972: opening "All bands & custom frequency" crashed because the picker
        // nested a second verticalScroll inside the sheet's own. The band list is
        // the three-entry one pinned in pinBandStatics().
        composeRule.setContent {
            FrequencyPickerSheet(visible = true, currentBandIndex = 0, onDismiss = {}, onSelect = {})
        }
        composeRule.onNodeWithText(context.getString(R.string.freq_select_title)).assertIsDisplayed()
        composeRule.onNodeWithText("40m").assertIsDisplayed()

        // The sheet owns the scroll now, so expanding the alternates in portrait
        // must leave the last chip reachable too, not only in the landscape test.
        composeRule.onNodeWithText(context.getString(R.string.freq_show_alternates))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(formatMhz(14_090_000L)).performScrollTo().assertIsDisplayed()
    }
}
