package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
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
 * Compose render + interaction tests for the shared Settings chrome (run on the
 * JVM via Robolectric). The category detail screens (radio model picker,
 * control-mode toggle, QRZ test-connection) are wired to the heavyweight
 * MainViewModel and are out of scope for the first pass; the stateless section
 * header and drill-down scaffold are the testable golden paths and back out of
 * the way of the cursor-blink animations that make the editor dialogs
 * non-deterministic under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsSection_rendersTitleAndContent() {
        composeRule.setContent {
            SettingsSection(title = "RADIO & AUDIO") {
                Text("Rig model")
            }
        }

        composeRule.onNodeWithText("RADIO & AUDIO").assertIsDisplayed()
        composeRule.onNodeWithText("Rig model").assertIsDisplayed()
    }

    @Test
    fun settingsDetailScaffold_rendersTitleAndContent() {
        composeRule.setContent {
            SettingsDetailScaffold(title = "Transmission", onBack = {}) {
                Text("Control mode")
            }
        }

        composeRule.onNodeWithText("Transmission").assertIsDisplayed()
        composeRule.onNodeWithText("Control mode").assertIsDisplayed()
    }

    @Test
    fun settingsDetailScaffold_backButtonInvokesOnBack() {
        var backPressed = false
        composeRule.setContent {
            SettingsDetailScaffold(title = "Transmission", onBack = { backPressed = true }) {
                Text("Control mode")
            }
        }

        // The back chevron is the only node with a click action in the scaffold
        // (TopBar exposes none), so this uniquely targets it.
        composeRule.onNode(hasClickAction()).performClick()

        assertThat(backPressed).isTrue()
    }
}
