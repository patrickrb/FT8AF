package radio.ks3ckc.ft8af.ui.decode

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k1af.ft8af.R
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
 * The empty state embeds [radio.ks3ckc.ft8af.ui.components.EmptyStateWaves],
 * which drives an infinite animation, so we freeze the test clock
 * (autoAdvance = false) to stop waitForIdle from spinning forever waiting for
 * it to settle.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class DecodeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun defaultFilter_showsDefaultEmptyCopy() {
        val title = context.getString(R.string.decode_empty_default_title)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            EmptyState(selectedFilter = "All")
        }

        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun cqFilter_showsCqEmptyCopy() {
        val title = context.getString(R.string.decode_empty_cq_title)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            EmptyState(selectedFilter = "CQ Calls")
        }

        composeRule.onNodeWithText(title).assertIsDisplayed()
    }
}
