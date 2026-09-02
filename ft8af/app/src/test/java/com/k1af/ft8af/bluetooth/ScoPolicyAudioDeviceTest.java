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
    public void constantMatchesFrameworkValue() throws Exception {
        // Read the framework constant reflectively: referencing it directly lets javac inline
        // the compile-time value, which would turn this into "7 == 7" and never catch drift.
        int framework = android.media.AudioDeviceInfo.class
                .getField("TYPE_BLUETOOTH_SCO").getInt(null);
        assertThat(ScoPolicy.TYPE_BLUETOOTH_SCO).isEqualTo(framework);
    }

    // ---- headsetModeAction: cached flag cross-checked with the coordinator's link state ----
    // Args: (want, enteredByUs, linkUp, linkHeld, bluetoothRig). linkUp = tracker says
    // CONNECTING/CONNECTED; linkHeld = coordinator still holds an SCO request (wanted).

    @Test
    public void headsetModeAction_wantedAndLinkUp_keeps() {
        assertThat(ScoPolicy.headsetModeAction(true, true, true, true, false))
                .isEqualTo(ScoPolicy.HEADSET_MODE_KEEP);
    }

    @Test
    public void headsetModeAction_wantedButNeverEntered_enters() {
        assertThat(ScoPolicy.headsetModeAction(true, false, false, false, false))
                .isEqualTo(ScoPolicy.HEADSET_MODE_ENTER);
    }

    @Test
    public void headsetModeAction_wantedButLinkDroppedBehindOurBack_reEnters() {
        // Headset disconnected and came back (or the start failed and retries were
        // exhausted): the cached flag says active but the link is down — must re-enter,
        // whether or not the coordinator still nominally holds the request.
        assertThat(ScoPolicy.headsetModeAction(true, true, false, true, false))
                .isEqualTo(ScoPolicy.HEADSET_MODE_ENTER);
        assertThat(ScoPolicy.headsetModeAction(true, true, false, false, false))
                .isEqualTo(ScoPolicy.HEADSET_MODE_ENTER);
        assertThat(ScoPolicy.headsetModeAction(true, true, false, false, true))
                .isEqualTo(ScoPolicy.HEADSET_MODE_ENTER);
    }

    @Test
    public void headsetModeAction_notWantedAndNotOurs_keeps() {
        // SCO turned on by someone else (Bluetooth rig path, broadcast receiver): leave it.
        assertThat(ScoPolicy.headsetModeAction(false, false, true, true, false))
                .isEqualTo(ScoPolicy.HEADSET_MODE_KEEP);
    }

    @Test
    public void headsetModeAction_deselectedOnUsbRig_leaves() {
        assertThat(ScoPolicy.headsetModeAction(false, true, true, true, false))
                .isEqualTo(ScoPolicy.HEADSET_MODE_LEAVE);
    }

    @Test
    public void headsetModeAction_deselectedWhileCoordinatorRetrying_stillLeaves() {
        // Copilot review on #778: the link is momentarily down (retry pending) but the
        // coordinator still wants it. Deciding on AudioManager.isBluetoothScoOn() here read
        // "off" and FORGOT, so the coordinator stayed wanted and brought SCO right back.
        // The held request must be told to stop.
        assertThat(ScoPolicy.headsetModeAction(false, true, false, true, false))
                .isEqualTo(ScoPolicy.HEADSET_MODE_LEAVE);
    }

    @Test
    public void headsetModeAction_deselectedAndNothingHeld_forgets() {
        // TX stopSco()/shutdown already released the request: nothing to tear down.
        assertThat(ScoPolicy.headsetModeAction(false, true, false, false, false))
                .isEqualTo(ScoPolicy.HEADSET_MODE_FORGET);
    }

    @Test
    public void headsetModeAction_neverYanksScoFromBluetoothRig() {
        assertThat(ScoPolicy.headsetModeAction(false, true, true, true, true))
                .isEqualTo(ScoPolicy.HEADSET_MODE_KEEP);
        assertThat(ScoPolicy.headsetModeAction(false, true, false, true, true))
                .isEqualTo(ScoPolicy.HEADSET_MODE_KEEP);
        assertThat(ScoPolicy.headsetModeAction(false, true, false, false, true))
                .isEqualTo(ScoPolicy.HEADSET_MODE_KEEP);
    }

    // ---- profileChangeAction: what the BT broadcast receiver does on a profile change ----

    @Test
    public void profileChangeAction_bluetoothRig_entersAndLeavesDirectly() {
        assertThat(ScoPolicy.profileChangeAction(ConnectMode.BLUE_TOOTH, true))
                .isEqualTo(ScoPolicy.PROFILE_ENTER);
        assertThat(ScoPolicy.profileChangeAction(ConnectMode.BLUE_TOOTH, false))
                .isEqualTo(ScoPolicy.PROFILE_LEAVE);
    }

    @Test
    public void profileChangeAction_otherRigs_runSelectionAwareRefresh() {
        // Copilot review on #778: USB/VOX rig + selected BT headset — a headset reconnect
        // after the SCO retry budget ran out was ignored, so RX stayed dead until restart.
        assertThat(ScoPolicy.profileChangeAction(ConnectMode.USB_CABLE, true))
                .isEqualTo(ScoPolicy.PROFILE_REFRESH);
        assertThat(ScoPolicy.profileChangeAction(ConnectMode.USB_CABLE, false))
                .isEqualTo(ScoPolicy.PROFILE_REFRESH);
        assertThat(ScoPolicy.profileChangeAction(ConnectMode.NETWORK, true))
                .isEqualTo(ScoPolicy.PROFILE_REFRESH);
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
