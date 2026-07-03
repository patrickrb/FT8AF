package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [FlexPickerLogic], the live-discovery list helpers behind the
 * FlexRadio picker polling fix.
 */
class FlexPickerLogicTest {

    @Test
    fun identityKey_prefersSerial() {
        assertThat(FlexPickerLogic.identityKey("0123-4567-89", "192.168.86.159"))
            .isEqualTo("0123-4567-89")
    }

    @Test
    fun identityKey_fallsBackToIpWhenSerialBlank() {
        assertThat(FlexPickerLogic.identityKey("  ", "192.168.86.159"))
            .isEqualTo("192.168.86.159")
        assertThat(FlexPickerLogic.identityKey(null, "192.168.86.159"))
            .isEqualTo("192.168.86.159")
    }

    @Test
    fun identityKey_trimsWhitespace() {
        assertThat(FlexPickerLogic.identityKey(" FLEX-1 ", null)).isEqualTo("FLEX-1")
    }

    @Test
    fun identityKey_emptyWhenNothingKnown() {
        assertThat(FlexPickerLogic.identityKey(null, null)).isEmpty()
    }

    @Test
    fun listChanged_falseWhenIdentical() {
        // The common per-tick case: nothing new discovered → no rebuild.
        assertThat(FlexPickerLogic.listChanged(listOf("a", "b"), listOf("a", "b"))).isFalse()
    }

    @Test
    fun listChanged_trueWhenRadioAppears() {
        // A radio surfaces after the dialog is already open — must update live.
        assertThat(FlexPickerLogic.listChanged(emptyList(), listOf("a"))).isTrue()
    }

    @Test
    fun listChanged_trueWhenRadioDropsOff() {
        assertThat(FlexPickerLogic.listChanged(listOf("a", "b"), listOf("a"))).isTrue()
    }
}
