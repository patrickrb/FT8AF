package com.k1af.ft8af.flows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.flows.Flows.waitForMainScreen
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Flow 1: a cold launch gets past the splash to the Decode screen without crashing. */
@RunWith(AndroidJUnit4::class)
class LaunchFlowTest {

    private val compose = Flows.composeRule()

    @get:Rule
    val rules: TestRule = Flows.chain(compose)

    @Test
    fun launch_reachesMainScreen() {
        compose.waitForMainScreen()

        // Every tab in the bottom bar is there, and Decode (the first tab) is showing.
        for (tab in listOf("Decode", "Map", "Waterfall", "POTA", "Logbook", "Settings")) {
            compose.onAllNodes(hasText(tab) and hasClickAction()).onFirst().assertIsDisplayed()
        }
        // The decode list's default filter chip.
        compose.onAllNodesWithText("All").onFirst().assertIsDisplayed()
        compose.activity.let { assertThat(it.isFinishing).isFalse() }

        Flows.screenshot("01_launch_main_screen")
    }
}
