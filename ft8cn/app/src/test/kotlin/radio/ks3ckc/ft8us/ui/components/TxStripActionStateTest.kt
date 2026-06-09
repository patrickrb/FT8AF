package radio.ks3ckc.ft8us.ui.components

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
    fun `CQ and HUNT are never both enabled at once when idle-armed or active`() {
        // The whole point of the rule: you can't be running CQ and hunting simultaneously.
        for (activated in listOf(false, true)) {
            for (hunt in listOf(false, true)) {
                val s = txStripActionState(activated, hunt)
                val cqEnabled = !s.cqDisabled
                val huntEnabledForCalling = s.huntActive && !s.huntDisabled
                // If CQ is being actively run (activated, not stop-disabled) it's exclusive
                // with hunt being the *calling* mode.
                if (activated && !hunt) {
                    assertThat(huntEnabledForCalling).isFalse()
                }
                if (!activated && hunt) {
                    assertThat(cqEnabled).isFalse()
                }
            }
        }
    }
}
