package com.k1af.ft8af.flows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.flows.Flows.openTab
import com.k1af.ft8af.flows.Flows.waitForMainScreen
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Flow 4: a callsign entered in Settings → Operator identity is saved to the
 * config database and is still shown after the activity is destroyed and
 * recreated.
 */
@RunWith(AndroidJUnit4::class)
class SettingPersistsFlowTest {

    private val compose = Flows.composeRule()

    @get:Rule
    val rules: TestRule = Flows.chain(compose)

    @Test
    fun changedCallsign_survivesActivityRestart() {
        compose.waitForMainScreen()
        val vm = MainViewModel.peekInstance()!!
        val originalCallsign = GeneralVariables.myCallsign.orEmpty()
        val newCallsign = if (originalCallsign == TEST_CALL_A) TEST_CALL_B else TEST_CALL_A

        try {
            compose.openTab("Settings")
            compose.onAllNodesWithText("Tap to edit").onFirst().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Edit Operator Identity").fetchSemanticsNodes().isNotEmpty()
            }
            // The callsign is the dialog's first text field.
            compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
                .onFirst()
                .performTextReplacement(newCallsign.lowercase())
            compose.onAllNodes(hasText("Save") and hasAnyAncestor(isDialog())).onFirst().performClick()
            compose.waitForIdle()

            // Saved, uppercased, to the config table (writeConfig is async).
            compose.waitUntil(5_000) { storedCallsign(vm) == newCallsign }

            compose.activityRule.scenario.recreate()
            compose.waitForMainScreen()
            // The selected tab is saveable state, but don't let the flow depend on it.
            if (compose.onAllNodesWithText("Tap to edit").fetchSemanticsNodes().isEmpty()) {
                compose.openTab("Settings")
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(newCallsign).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText(newCallsign).onFirst().assertIsDisplayed()
            assertThat(storedCallsign(vm)).isEqualTo(newCallsign)

            Flows.screenshot("04_setting_persists_after_restart")
        } finally {
            restoreCallsign(originalCallsign)
        }
    }

    private fun storedCallsign(vm: MainViewModel): String? =
        vm.databaseOpr.db.rawQuery(
            "select Value from config where KeyName = ?",
            arrayOf("callsign"),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Put the operator's own callsign back so a run on a real phone leaves it unchanged. */
    private fun restoreCallsign(callsign: String) {
        if (GeneralVariables.myCallsign == callsign) return
        GeneralVariables.myCallsign = callsign
        // Best effort, so a throw here can't replace the real failure.
        runCatching {
            MainViewModel.peekInstance()?.databaseOpr?.writeConfig("callsign", callsign, null)
        }
    }

    private companion object {
        // Valid-looking but unassigned-format test calls; two so a rerun always changes it.
        const val TEST_CALL_A = "K1TST"
        const val TEST_CALL_B = "K2TST"
    }
}
