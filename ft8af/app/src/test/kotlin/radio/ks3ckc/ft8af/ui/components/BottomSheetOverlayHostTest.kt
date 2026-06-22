package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression test for the POTA park picker "tap does nothing" bug.
 *
 * [FT8AFBottomSheet] renders a fillMaxSize scrim + sheet. When it is emitted as
 * a trailing sibling of a fillMaxSize content node inside a [Column], the column
 * gives the greedy first child all the height and the sheet lays out into zero
 * leftover height — visible = true, but nothing on screen. Hosting both in a
 * [Box] (as FrequencyPickerSheet does in FT8AFApp, and as ActivateTab now does)
 * lets the sheet overlay the content. These two cases pin that behaviour.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class BottomSheetOverlayHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sheetLabel = "PICKER_CONTENT"

    @Composable
    private fun sheetContent() {
        FT8AFBottomSheet(visible = true, onDismiss = {}) {
            Text(sheetLabel)
        }
    }

    @Test
    fun sheetInColumnAfterFillMaxSizeSibling_isNotDisplayed() {
        // Reproduces the bug: greedy fillMaxSize sibling starves the sheet of
        // height, so its content composes but is laid out at zero size.
        composeRule.setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize())
                sheetContent()
            }
        }

        composeRule.onNodeWithText(sheetLabel).assertIsNotDisplayed()
    }

    @Test
    fun sheetWrappedInBoxOverlay_isDisplayed() {
        // The fix: a Box host lets the sheet overlay the fillMaxSize content.
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize())
                sheetContent()
            }
        }

        composeRule.onNodeWithText(sheetLabel).assertIsDisplayed()
    }
}
