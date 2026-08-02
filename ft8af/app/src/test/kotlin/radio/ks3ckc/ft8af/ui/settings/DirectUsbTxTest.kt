package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests the decision logic behind the "Direct USB TX" toggle
 * ([isDirectUsbTxEnabled], [directUsbTxSelection], [systemDefaultTxSelection]).
 *
 * The toggle is a second view of the audio-output selection also driven by the
 * device picker, so its predicate must match the TX-path branch in
 * {@code FT8TransmitSignal.playFT8Signal} (deviceId == -1 && vendorId != 0)
 * exactly, and each direction must produce field values that round-trip through
 * that predicate.
 */
class DirectUsbTxTest {

    @Test
    fun enabledOnlyWhenDirectDeviceAndVendorPresent() {
        // Both conditions met: direct sentinel device id + a real VID.
        assertThat(isDirectUsbTxEnabled(-1, 0x0D8C)).isTrue()
    }

    @Test
    fun notEnabledForAudioTrackDevice() {
        // System default (0) and a specific AudioManager device (>0) are the
        // AudioTrack sink regardless of any stale VID left in the fields.
        assertThat(isDirectUsbTxEnabled(0, 0x0D8C)).isFalse()
        assertThat(isDirectUsbTxEnabled(5, 0x0D8C)).isFalse()
        assertThat(isDirectUsbTxEnabled(0, 0)).isFalse()
    }

    @Test
    fun notEnabledWhenVendorMissingEvenIfDirectDeviceId() {
        // deviceId == -1 alone is not enough — the path needs a VID to locate
        // the device, so a -1 with vendor 0 must read as off (and it would in
        // fact fall through to AudioTrack).
        assertThat(isDirectUsbTxEnabled(-1, 0)).isFalse()
    }

    @Test
    fun directSelectionRoundTripsToEnabled() {
        val sel = directUsbTxSelection(0x0D8C, 0x0012)
        assertThat(sel.audioOutputDeviceId).isEqualTo(-1)
        assertThat(sel.usbVendorId).isEqualTo(0x0D8C)
        assertThat(sel.usbProductId).isEqualTo(0x0012)
        assertThat(isDirectUsbTxEnabled(sel.audioOutputDeviceId, sel.usbVendorId)).isTrue()
    }

    @Test
    fun systemDefaultSelectionRoundTripsToDisabled() {
        val sel = systemDefaultTxSelection()
        assertThat(sel.audioOutputDeviceId).isEqualTo(0)
        assertThat(sel.usbVendorId).isEqualTo(0)
        assertThat(sel.usbProductId).isEqualTo(0)
        assertThat(isDirectUsbTxEnabled(sel.audioOutputDeviceId, sel.usbVendorId)).isFalse()
    }
}
