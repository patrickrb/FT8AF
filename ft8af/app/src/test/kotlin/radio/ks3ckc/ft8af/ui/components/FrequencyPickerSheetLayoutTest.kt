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
import com.k1af.ft8af.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout tests for the frequency picker's alternates list (#930): on a narrow
 * screen the alternate chips must wrap whole onto another line with every
 * label on one unclipped line, and the picker body must scroll so wrapped rows
 * stay reachable on a short screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrequencyPickerSheetLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

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
            FrequencyPickerContent(groups = groups, currentBandIndex = 0, onSelect = { picked = it })
        }

        composeRule.onNodeWithText(context.getString(R.string.freq_show_alternates))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(lastLabel).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(lastLabel).performClick()

        assertThat(picked).isEqualTo(lastIndex)
    }
}
