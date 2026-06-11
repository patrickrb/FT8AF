package radio.ks3ckc.ft8us.ui.logbook

import androidx.compose.ui.res.stringResource
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

    @Test
    fun segmentedTabRow_rendersEveryTabLabel() {
        val labels = mutableListOf<String>()
        composeRule.setContent {
            labels.clear()
            LogbookTab.entries.forEach { labels.add(stringResource(it.labelRes)) }
            SegmentedTabRow(
                tabs = LogbookTab.entries,
                selected = LogbookTab.STATS,
                onSelected = {},
            )
        }

        labels.forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun segmentedTabRow_tappingTabReportsThatTab() {
        var picked: LogbookTab? = null
        lateinit var recentLabel: String
        composeRule.setContent {
            recentLabel = stringResource(LogbookTab.RECENT.labelRes)
            SegmentedTabRow(
                tabs = LogbookTab.entries,
                selected = LogbookTab.STATS,
                onSelected = { picked = it },
            )
        }

        composeRule.onNodeWithText(recentLabel).performClick()

        assertThat(picked).isEqualTo(LogbookTab.RECENT)
    }
}
