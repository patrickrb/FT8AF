package radio.ks3ckc.ft8us.ui.decode

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bg7yoz.ft8cn.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose render test for the Decode screen's empty state, run on the JVM via
 * Robolectric + createComposeRule (no emulator). Verifies the screen renders
 * filter-specific empty-state copy — the path that breaks silently when a
 * refactor or theme change drops a string binding.
 *
 * The empty state embeds [radio.ks3ckc.ft8us.ui.components.EmptyStateWaves],
 * which drives an infinite animation, so we freeze the test clock
 * (autoAdvance = false) to stop waitForIdle from spinning forever waiting for
 * it to settle.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class DecodeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultFilter_showsDefaultEmptyCopy() {
        composeRule.mainClock.autoAdvance = false
        lateinit var title: String
        composeRule.setContent {
            title = stringResource(R.string.decode_empty_default_title)
            EmptyState(selectedFilter = "All")
        }

        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun cqFilter_showsCqEmptyCopy() {
        composeRule.mainClock.autoAdvance = false
        lateinit var title: String
        composeRule.setContent {
            title = stringResource(R.string.decode_empty_cq_title)
            EmptyState(selectedFilter = "CQ Calls")
        }

        composeRule.onNodeWithText(title).assertIsDisplayed()
    }
}
