package radio.ks3ckc.ft8af.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI test for the visible close affordance added to
 * [FT8AFBottomSheet]. Guards issue #782 "calling CQ -> 'more' has no
 * back-button": every sheet must expose a plainly tappable close icon so users
 * who never discover the scrim tap / drag-handle / hardware Back still have a
 * way out.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class FT8AFBottomSheetCloseButtonTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun closeButton_isDisplayed_whenVisible() {
        composeRule.setContent {
            FT8AFBottomSheet(visible = true, onDismiss = {}) {}
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun closeButton_click_dismissesSheet() {
        var dismissed = false
        composeRule.setContent {
            FT8AFBottomSheet(visible = true, onDismiss = { dismissed = true }) {}
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.waitForIdle()

        assertThat(dismissed).isTrue()
    }
}
