package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [txStripActionState] — the pure HUNT vs CQ/STOP mutual-exclusion rule
 * extracted from TxStrip so the Composable stays a thin wrapper. No Android runtime is
 * needed: the inputs and outputs are plain booleans.
 */
class TxStripActionStateTest {

    @Test
    fun `idle, hunt off — CQ callable, hunt callable`() {
        val s = txStripActionState(isActivated = false, huntEnabled = false)
        assertThat(s.cqDisabled).isFalse()
        assertThat(s.cqIsStop).isFalse()
        assertThat(s.huntDisabled).isFalse()
        assertThat(s.huntActive).isFalse()
    }

    @Test
    fun `idle, hunt armed — CQ locked off, hunt highlighted and still toggleable`() {
        val s = txStripActionState(isActivated = false, huntEnabled = true)
        // HUNT armed but no QSO yet: CQ is locked so the two modes never overlap.
        assertThat(s.cqDisabled).isTrue()
        assertThat(s.cqIsStop).isFalse()
        assertThat(s.huntActive).isTrue()
        // Still enabled so the user can turn HUNT back off.
        assertThat(s.huntDisabled).isFalse()
    }

    @Test
    fun `active CQ, hunt off — button is STOP, hunt locked off`() {
        val s = txStripActionState(isActivated = true, huntEnabled = false)
        assertThat(s.cqIsStop).isTrue()
        // STOP must always be tappable to end the QSO.
        assertThat(s.cqDisabled).isFalse()
        // Can't arm HUNT while a CQ/QSO is running.
        assertThat(s.huntDisabled).isTrue()
        assertThat(s.huntActive).isFalse()
    }

    @Test
    fun `active while hunt enabled — STOP stays tappable, hunt stays enabled`() {
        // Hunt-driven QSO: activated AND huntEnabled. STOP must work, and HUNT
        // shouldn't be locked (huntDisabled requires hunt to be OFF).
        val s = txStripActionState(isActivated = true, huntEnabled = true)
        assertThat(s.cqIsStop).isTrue()
        assertThat(s.cqDisabled).isFalse()
        assertThat(s.huntDisabled).isFalse()
        assertThat(s.huntActive).isTrue()
    }

    @Test
    fun `full truth table — every (isActivated, huntEnabled) combination`() {
        // Exhaustive, both-directions assertion of the whole 2x2 space, so a regression in
        // any field of any cell fails here. Tuple order:
        //   isActivated, huntEnabled -> huntDisabled, huntActive, cqDisabled, cqIsStop
        val expected = mapOf(
            (false to false) to TxStripActionState(
                huntDisabled = false, huntActive = false, cqDisabled = false, cqIsStop = false),
            (false to true) to TxStripActionState(
                huntDisabled = false, huntActive = true, cqDisabled = true, cqIsStop = false),
            (true to false) to TxStripActionState(
                huntDisabled = true, huntActive = false, cqDisabled = false, cqIsStop = true),
            (true to true) to TxStripActionState(
                huntDisabled = false, huntActive = true, cqDisabled = false, cqIsStop = true),
        )
        for ((input, want) in expected) {
            val (activated, hunt) = input
            assertThat(txStripActionState(activated, hunt)).isEqualTo(want)
        }
    }

    @Test
    fun `the two calling modes are mutually exclusive in every cell`() {
        // The rule's purpose: you can never be set up to run CQ and to hunt at the same
        // time. "Able to call CQ" = the CQ button is enabled and not already STOP; "hunting"
        // = HUNT is the active calling mode and not locked. These must never both hold.
        for (activated in listOf(false, true)) {
            for (hunt in listOf(false, true)) {
                val s = txStripActionState(activated, hunt)
                val canCallCq = !s.cqDisabled && !s.cqIsStop
                val isHunting = s.huntActive && !s.huntDisabled
                assertThat(canCallCq && isHunting).isFalse()
            }
        }
    }
}
