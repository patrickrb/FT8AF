package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [shouldAutoConnectFlex] — the rule that decides whether the Flex
 * picker connects with no user action. Pure logic, no Android/Compose types.
 */
class FlexDiscoveryLogicTest {

    @Test
    fun autoConnects_whenExactlyOneFound_noTyping_notYetTriggered() {
        assertThat(
            shouldAutoConnectFlex(discoveredCount = 1, userTypedIp = false, alreadyTriggered = false),
        ).isTrue()
    }

    @Test
    fun doesNotAutoConnect_whenNothingFound() {
        assertThat(
            shouldAutoConnectFlex(discoveredCount = 0, userTypedIp = false, alreadyTriggered = false),
        ).isFalse()
    }

    @Test
    fun doesNotAutoConnect_whenMultipleFound() {
        // Ambiguous which one the user wants — leave it to a tap.
        assertThat(
            shouldAutoConnectFlex(discoveredCount = 3, userTypedIp = false, alreadyTriggered = false),
        ).isFalse()
    }

    @Test
    fun doesNotAutoConnect_whenUserIsTypingAnIp() {
        // A deliberate manual entry must not be hijacked.
        assertThat(
            shouldAutoConnectFlex(discoveredCount = 1, userTypedIp = true, alreadyTriggered = false),
        ).isFalse()
    }

    @Test
    fun doesNotAutoConnect_onceAlreadyTriggered() {
        // One-shot: a later discovery event can't re-fire the auto-connect.
        assertThat(
            shouldAutoConnectFlex(discoveredCount = 1, userTypedIp = false, alreadyTriggered = true),
        ).isFalse()
    }
}
