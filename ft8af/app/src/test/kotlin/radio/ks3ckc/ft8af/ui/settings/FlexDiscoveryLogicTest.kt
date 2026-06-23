package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [shouldAutoConnectFlex] — the rule that decides whether the Flex
 * picker connects with no user action. Pure logic, no Android/Compose types.
 */
class FlexDiscoveryLogicTest {

    /** All five preconditions met → auto-connect. */
    private fun autoConnect(
        discoveredCount: Int = 1,
        userTypedIp: Boolean = false,
        alreadyTriggered: Boolean = false,
        startedEmpty: Boolean = true,
        rigAlreadyConnected: Boolean = false,
    ) = shouldAutoConnectFlex(
        discoveredCount = discoveredCount,
        userTypedIp = userTypedIp,
        alreadyTriggered = alreadyTriggered,
        startedEmpty = startedEmpty,
        rigAlreadyConnected = rigAlreadyConnected,
    )

    @Test
    fun autoConnects_freshSingleDiscovery_noTyping_notConnected() {
        assertThat(autoConnect()).isTrue()
    }

    @Test
    fun doesNotAutoConnect_whenNothingFound() {
        assertThat(autoConnect(discoveredCount = 0)).isFalse()
    }

    @Test
    fun doesNotAutoConnect_whenMultipleFound() {
        // Ambiguous which one the user wants — leave it to a tap.
        assertThat(autoConnect(discoveredCount = 3)).isFalse()
    }

    @Test
    fun doesNotAutoConnect_whenUserIsTypingAnIp() {
        assertThat(autoConnect(userTypedIp = true)).isFalse()
    }

    @Test
    fun doesNotAutoConnect_onceAlreadyTriggered() {
        // One-shot: a later discovery event can't re-fire the auto-connect.
        assertThat(autoConnect(alreadyTriggered = true)).isFalse()
    }

    @Test
    fun doesNotAutoConnect_whenPickerOpenedWithCachedRadio() {
        // The bug that made the dialog "close the instant you open it": a radio
        // cached in the FlexRadioFactory singleton seeds the list, so the picker
        // did NOT start empty — must not auto-connect to it.
        assertThat(autoConnect(startedEmpty = false)).isFalse()
    }

    @Test
    fun doesNotAutoConnect_whenRigAlreadyConnected() {
        // Reconnecting a live session would tear it down.
        assertThat(autoConnect(rigAlreadyConnected = true)).isFalse()
    }

    // ---- mergeDiscovered ----

    @Test
    fun mergeDiscovered_includesTheNewRadio_evenWhenFactoryListLagsBehind() {
        // The factory fires the callback before appending, so existing is empty
        // when the very first radio arrives — it must still appear.
        assertThat(mergeDiscovered(emptyList<String>(), "192.168.1.9") { it })
            .containsExactly("192.168.1.9")
    }

    @Test
    fun mergeDiscovered_dedupesByKey() {
        // A later event that already includes the radio must not duplicate it.
        assertThat(mergeDiscovered(listOf("192.168.1.9"), "192.168.1.9") { it })
            .containsExactly("192.168.1.9")
        assertThat(mergeDiscovered(listOf("192.168.1.9"), "10.0.0.5") { it })
            .containsExactly("192.168.1.9", "10.0.0.5").inOrder()
    }
}
