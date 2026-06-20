package radio.ks3ckc.ft8af.ui.components

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
 * Compose UI test running on the JVM via Robolectric. Smoke-covers the
 * stateless FilterChips composable: it renders each option, and clicking a
 * chip invokes the `onSelected` callback with that option's value.
 *
 * Establishes the pattern for future screen-level tests; not exhaustive.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class FilterChipsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val options = listOf("All", "CQ", "QSL", "Active")

    @Test
    fun rendersAllOptionLabels() {
        composeRule.setContent {
            FilterChips(options = options, selected = "All", onSelected = {})
        }

        options.forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun clickingChip_invokesOnSelectedWithThatOption() {
        var picked: String? = null
        composeRule.setContent {
            FilterChips(
                options = options,
                selected = "All",
                onSelected = { picked = it },
            )
        }

        composeRule.onNodeWithText("CQ").performClick()

        assertThat(picked).isEqualTo("CQ")
    }

    @Test
    fun selectedChip_doesNotErrorWhenClicked() {
        // Tapping the already-selected chip should still fire the callback
        // (it's the caller's job to decide if that's a no-op).
        var picked: String? = null
        composeRule.setContent {
            FilterChips(
                options = options,
                selected = "All",
                onSelected = { picked = it },
            )
        }

        composeRule.onNodeWithText("All").performClick()

        assertThat(picked).isEqualTo("All")
    }
}
