package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.ControlMode;

import org.junit.Test;

public class ScoPolicyTest {

    // The car-stereo bug: USB CAT rig without audio-over-CAT used to toggle
    // SCO around every TX, pausing/unpausing the car's A2DP music.
    @Test
    public void needControlSco_usbCatRig_isFalse() {
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.USB_CABLE, ControlMode.CAT, true, false)).isFalse();
    }

    @Test
    public void needControlSco_usbNonCatControl_isFalse() {
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.USB_CABLE, ControlMode.RTS, true, false)).isFalse();
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.USB_CABLE, ControlMode.DTR, true, false)).isFalse();
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.USB_CABLE, ControlMode.VOX, true, false)).isFalse();
    }

    @Test
    public void needControlSco_networkMode_isFalse() {
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.NETWORK, ControlMode.CAT, true, false)).isFalse();
    }

    @Test
    public void needControlSco_bluetoothNonCatControl_isTrue() {
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.BLUE_TOOTH, ControlMode.VOX, true, false)).isTrue();
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.BLUE_TOOTH, ControlMode.RTS, false, false)).isTrue();
    }

    @Test
    public void needControlSco_bluetoothCatRigWithoutWaveOverCat_isTrue() {
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.BLUE_TOOTH, ControlMode.CAT, true, false)).isTrue();
    }

    @Test
    public void needControlSco_bluetoothCatRigWithWaveOverCat_isFalse() {
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.BLUE_TOOTH, ControlMode.CAT, true, true)).isFalse();
    }

    @Test
    public void needControlSco_bluetoothCatNoRig_isFalse() {
        assertThat(ScoPolicy.needControlSco(
                ConnectMode.BLUE_TOOTH, ControlMode.CAT, false, false)).isFalse();
    }

    @Test
    public void shouldEnterHeadsetMode_onlyWhenBluetoothModeAndProfileConnected() {
        assertThat(ScoPolicy.shouldEnterHeadsetMode(ConnectMode.BLUE_TOOTH, true)).isTrue();
        assertThat(ScoPolicy.shouldEnterHeadsetMode(ConnectMode.BLUE_TOOTH, false)).isFalse();
        // Phone paired to a car/headphones for music while the rig is on USB
        // or network must not flip the phone into headset (SCO) mode.
        assertThat(ScoPolicy.shouldEnterHeadsetMode(ConnectMode.USB_CABLE, true)).isFalse();
        assertThat(ScoPolicy.shouldEnterHeadsetMode(ConnectMode.NETWORK, true)).isFalse();
    }
}
