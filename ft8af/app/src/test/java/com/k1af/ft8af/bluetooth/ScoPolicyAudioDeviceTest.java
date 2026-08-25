package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.ConnectMode;

import org.junit.Test;

/**
 * Tests for the audio-device-aware headset-mode decision added for issue #723: SCO must come
 * up when the operator picks a Bluetooth headset as the FT8 mic/speaker, even on a USB/VOX rig.
 * Pure JUnit — TYPE_BLUETOOTH_SCO is a compile-time constant.
 */
public class ScoPolicyAudioDeviceTest {

    // AudioDeviceInfo type constants (framework values) used by these tests.
    private static final int TYPE_BLUETOOTH_SCO = 7;
    private static final int TYPE_BUILTIN_MIC = 15;
    private static final int TYPE_USB_DEVICE = 11;
    private static final int NONE = -1;

    @Test
    public void constantMatchesFrameworkValue() {
        assertThat(ScoPolicy.TYPE_BLUETOOTH_SCO)
                .isEqualTo(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    }

    @Test
    public void audioSelectionNeedsHeadsetMode_btScoInput() {
        assertThat(ScoPolicy.audioSelectionNeedsHeadsetMode(TYPE_BLUETOOTH_SCO, NONE)).isTrue();
    }

    @Test
    public void audioSelectionNeedsHeadsetMode_btScoOutput() {
        assertThat(ScoPolicy.audioSelectionNeedsHeadsetMode(TYPE_BUILTIN_MIC, TYPE_BLUETOOTH_SCO))
                .isTrue();
    }

    @Test
    public void audioSelectionNeedsHeadsetMode_noBtDevice() {
        assertThat(ScoPolicy.audioSelectionNeedsHeadsetMode(TYPE_BUILTIN_MIC, TYPE_USB_DEVICE))
                .isFalse();
        assertThat(ScoPolicy.audioSelectionNeedsHeadsetMode(NONE, NONE)).isFalse();
    }

    @Test
    public void shouldEnterHeadsetMode_usbRigWithBtHeadsetMic_entersWhenBtConnected() {
        // The #723 scenario: USB rig, BT headset picked as mic, headset actually paired.
        assertThat(ScoPolicy.shouldEnterHeadsetMode(
                ConnectMode.USB_CABLE, true, TYPE_BLUETOOTH_SCO, NONE)).isTrue();
    }

    @Test
    public void shouldEnterHeadsetMode_btHeadsetSelectedButNothingConnected_isFalse() {
        // A stale saved BT-SCO device id must not force SCO with no headset paired.
        assertThat(ScoPolicy.shouldEnterHeadsetMode(
                ConnectMode.USB_CABLE, false, TYPE_BLUETOOTH_SCO, NONE)).isFalse();
    }

    @Test
    public void shouldEnterHeadsetMode_bluetoothRig_stillEntersRegardlessOfAudioDevice() {
        // Existing behaviour preserved: a Bluetooth rig enters headset mode even with the
        // default (non-BT) audio device selected.
        assertThat(ScoPolicy.shouldEnterHeadsetMode(
                ConnectMode.BLUE_TOOTH, true, NONE, NONE)).isTrue();
    }

    @Test
    public void shouldEnterHeadsetMode_usbRigDefaultAudio_isFalse() {
        // The car-stereo protection: USB rig + no BT audio device selected => no SCO.
        assertThat(ScoPolicy.shouldEnterHeadsetMode(
                ConnectMode.USB_CABLE, true, TYPE_BUILTIN_MIC, TYPE_BUILTIN_MIC)).isFalse();
    }

    @Test
    public void twoArgOverload_unchanged() {
        assertThat(ScoPolicy.shouldEnterHeadsetMode(ConnectMode.BLUE_TOOTH, true)).isTrue();
        assertThat(ScoPolicy.shouldEnterHeadsetMode(ConnectMode.USB_CABLE, true)).isFalse();
    }
}
