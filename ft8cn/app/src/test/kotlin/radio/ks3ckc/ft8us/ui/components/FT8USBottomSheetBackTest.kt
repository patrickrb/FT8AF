package radio.ks3ckc.ft8us.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI test (JVM via Robolectric) for the back-press behaviour of
 * [FT8USBottomSheet] — the fix for the band picker not closing on Android Back
 * (issue #201).
 *
 * The sheet installs a [androidx.activity.compose.BackHandler] that is enabled
 * only while the sheet is visible. We register a sentinel "app-level" back
 * callback first (lower priority) to stand in for the activity's exit handler,
 * then assert that:
 *   - while visible, Back dismisses the sheet and never reaches the app handler;
 *   - while hidden, the sheet ignores Back and it propagates to the app handler.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class FT8USBottomSheetBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun pressBack() = composeRule.runOnUiThread {
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
    }

    /** Stand-in for the activity's own back handler (added before the sheet, so
     * lower priority than the sheet's BackHandler). */
    private fun addAppHandler(onBack: () -> Unit) = composeRule.runOnUiThread {
        composeRule.activity.onBackPressedDispatcher.addCallback(
            composeRule.activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = onBack()
            },
        )
    }

    @Test
    fun backPress_whenVisible_dismissesSheet_andDoesNotReachAppHandler() {
        var dismissed = false
        var appHandled = false
        addAppHandler { appHandled = true }
        composeRule.setContent {
            FT8USBottomSheet(visible = true, onDismiss = { dismissed = true }) {}
        }
        composeRule.waitForIdle()

        pressBack()
        composeRule.waitForIdle()

        assertThat(dismissed).isTrue()
        assertThat(appHandled).isFalse()
    }

    @Test
    fun backPress_whenHidden_propagatesToAppHandler_notSheet() {
        var dismissed = false
        var appHandled = false
        addAppHandler { appHandled = true }
        composeRule.setContent {
            FT8USBottomSheet(visible = false, onDismiss = { dismissed = true }) {}
        }
        composeRule.waitForIdle()

        pressBack()
        composeRule.waitForIdle()

        assertThat(dismissed).isFalse()
        assertThat(appHandled).isTrue()
    }
}
