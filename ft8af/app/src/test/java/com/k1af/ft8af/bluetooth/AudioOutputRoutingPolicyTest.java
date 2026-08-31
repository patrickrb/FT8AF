package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests for the Default-output override that steers TX to A2DP when a Bluetooth
 * SCO link is up (issue #759 follow-up). Pure JUnit — the policy takes plain
 * ints so it never touches the Android runtime.
 */
public class AudioOutputRoutingPolicyTest {

    // Framework AudioDeviceInfo type constants used to build test inputs.
    private static final int TYPE_BUILTIN_SPEAKER = 2;
    private static final int TYPE_WIRED_HEADSET = 3;
    private static final int TYPE_BLUETOOTH_SCO = 7;
    private static final int TYPE_BLUETOOTH_A2DP = 8;
    private static final int TYPE_USB_DEVICE = 11;

    @Test
    public void constantsMatchFrameworkValues() throws Exception {
        int frameworkA2dp = android.media.AudioDeviceInfo.class
                .getField("TYPE_BLUETOOTH_A2DP").getInt(null);
        int frameworkSco = android.media.AudioDeviceInfo.class
                .getField("TYPE_BLUETOOTH_SCO").getInt(null);
        assertThat(AudioOutputRoutingPolicy.TYPE_BLUETOOTH_A2DP).isEqualTo(frameworkA2dp);
        assertThat(AudioOutputRoutingPolicy.TYPE_BLUETOOTH_SCO).isEqualTo(frameworkSco);
    }

    // The issue #759 follow-up: with mic SCO up and the paired transceiver also
    // reachable via A2DP, "Default" output must be steered to A2DP or TX silently
    // goes into the SCO speaker instead of on the air.
    @Test
    public void a2dpAndScoBothPresent_picksA2dp() {
        int[] types = {TYPE_BUILTIN_SPEAKER, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        assertThat(AudioOutputRoutingPolicy.pickDefaultOutputIndex(types)).isEqualTo(1);
    }

    @Test
    public void a2dpAndScoBothPresent_picksFirstA2dp() {
        // Some devices enumerate more than one A2DP node during a profile
        // transition; the first is the stable choice.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_A2DP};
        assertThat(AudioOutputRoutingPolicy.pickDefaultOutputIndex(types)).isEqualTo(0);
    }

    // No SCO in play: a phone paired to a car/headphones for music must not have
    // TX yanked off the wired headset or built-in speaker onto the A2DP link —
    // that's the car-stereo pause-loop regression the two-arg ScoPolicy guards
    // against on the input side.
    @Test
    public void onlyA2dpPresent_leavesToOs() {
        int[] types = {TYPE_BUILTIN_SPEAKER, TYPE_BLUETOOTH_A2DP};
        assertThat(AudioOutputRoutingPolicy.pickDefaultOutputIndex(types))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void onlyScoPresent_leavesToOs() {
        // Nothing better to steer to; the OS's own routing (and the SCO
        // pipeline itself) is the only choice.
        int[] types = {TYPE_BUILTIN_SPEAKER, TYPE_BLUETOOTH_SCO};
        assertThat(AudioOutputRoutingPolicy.pickDefaultOutputIndex(types))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void noBluetoothDevices_leavesToOs() {
        int[] types = {TYPE_BUILTIN_SPEAKER, TYPE_WIRED_HEADSET, TYPE_USB_DEVICE};
        assertThat(AudioOutputRoutingPolicy.pickDefaultOutputIndex(types))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void emptyList_leavesToOs() {
        assertThat(AudioOutputRoutingPolicy.pickDefaultOutputIndex(new int[0]))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void nullList_leavesToOs() {
        assertThat(AudioOutputRoutingPolicy.pickDefaultOutputIndex(null))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }
}
