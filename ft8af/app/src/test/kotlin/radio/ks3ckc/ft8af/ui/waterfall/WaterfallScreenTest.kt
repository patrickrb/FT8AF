package radio.ks3ckc.ft8af.ui.waterfall

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose render + interaction tests for the Waterfall screen's stateless
 * pieces (run on the JVM via Robolectric). The spectrum/waterfall canvases are
 * native AndroidViews and out of scope; these cover the pure-Compose frequency
 * ruler and the bottom-bar toggle chips.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class WaterfallScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun frequencyRuler_rendersTickLabelsEvery500Hz() {
        composeRule.setContent {
            FrequencyRuler(spectrumWidth = 1500)
        }

        listOf("0", "500", "1000", "1500").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun toggleChip_rendersLabelAndFiresOnClick() {
        var clicked = false
        composeRule.setContent {
            ToggleChip(label = "NR", active = false, onClick = { clicked = true })
        }

        composeRule.onNodeWithText("NR").assertIsDisplayed()
        composeRule.onNodeWithText("NR").performClick()

        assertThat(clicked).isTrue()
    }

    // -- displayTxFrequencyHz -------------------------------------------------

    @Test
    fun displayTxFrequencyHz_activeCursor_usesTouchedFrequency() {
        // During a drag the red TX markers must track the blue tap cursor
        // rather than the committed base frequency (issue #782).
        assertThat(displayTxFrequencyHz(touchedFreqHz = 1450, baseFreqHz = 1200f))
            .isEqualTo(1450f)
    }

    @Test
    fun displayTxFrequencyHz_clearedCursor_fallsBackToBaseFrequency() {
        // frequencyLineTimeout expired, so touchedFreqHz is back to -1.
        assertThat(displayTxFrequencyHz(touchedFreqHz = -1, baseFreqHz = 1200f))
            .isEqualTo(1200f)
    }

    @Test
    fun displayTxFrequencyHz_rejectedTouch_fallsBackToBaseFrequency() {
        // SpectrumTouchMath.touchToFreqHz returns -1 for an off-view drag and
        // 0 is not a transmittable audio frequency; neither may displace the
        // base frequency on the markers.
        assertThat(displayTxFrequencyHz(touchedFreqHz = 0, baseFreqHz = 1200f))
            .isEqualTo(1200f)
    }
}
