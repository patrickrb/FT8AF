package radio.ks3ckc.ft8af.ui.logbook

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose render + interaction tests for the Logbook screen's segmented tab
 * row (run on the JVM via Robolectric). The Stats/Recent/Awards content is
 * driven by database queries through the heavyweight MainViewModel and is out
 * of scope; the tab switcher is the stateless navigation control and its golden
 * path — render every tab, tapping a tab reports that tab — is what we cover.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class LogbookScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Resolve expected labels from a Robolectric context up front rather than
    // capturing stringResource(...) inside setContent — SegmentedTabRow animates
    // (animateColorAsState), so a recomposition could re-run any in-composition
    // capture while the assertions iterate over it.
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun tabLabel(tab: LogbookTab): String = context.getString(tab.labelRes)

    @Test
    fun segmentedTabRow_rendersEveryTabLabel() {
        composeRule.setContent {
            SegmentedTabRow(
                tabs = LogbookTab.entries,
                selected = LogbookTab.STATS,
                onSelected = {},
            )
        }

        LogbookTab.entries.forEach { tab ->
            composeRule.onNodeWithText(tabLabel(tab)).assertIsDisplayed()
        }
    }

    @Test
    fun segmentedTabRow_tappingTabReportsThatTab() {
        var picked: LogbookTab? = null
        composeRule.setContent {
            SegmentedTabRow(
                tabs = LogbookTab.entries,
                selected = LogbookTab.STATS,
                onSelected = { picked = it },
            )
        }

        composeRule.onNodeWithText(tabLabel(LogbookTab.RECENT)).performClick()

        assertThat(picked).isEqualTo(LogbookTab.RECENT)
    }
}
