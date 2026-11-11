package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import com.k1af.ft8af.handoff.RigHardReset
import org.junit.Test

/** Tests for [takeControlMessageRes], what "Take control of radio" tells the operator. */
class TakeControlCardTest {

    @Test
    fun askingWhenAPermissionPromptIsComing() {
        assertThat(takeControlMessageRes(RigHardReset.Outcome.ASKING))
            .isEqualTo(R.string.radio_take_control_asking)
    }

    @Test
    fun saysEverythingIsGrantedWhenNothingPopsUp() {
        assertThat(takeControlMessageRes(RigHardReset.Outcome.ALL_GRANTED))
            .isEqualTo(R.string.radio_take_control_all_granted)
    }

    @Test
    fun pointsAtTheCableWhenNoUsbDeviceIsAttached() {
        assertThat(takeControlMessageRes(RigHardReset.Outcome.NO_USB_DEVICE))
            .isEqualTo(R.string.radio_take_control_no_usb)
    }

    @Test
    fun everyOutcomeHasItsOwnMessage() {
        val all = RigHardReset.Outcome.values().map { takeControlMessageRes(it) }
        assertThat(all.toSet()).hasSize(RigHardReset.Outcome.values().size)
    }
}
