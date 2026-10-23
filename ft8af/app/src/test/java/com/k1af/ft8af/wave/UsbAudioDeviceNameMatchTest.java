package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link UsbAudioDevice#unambiguousNameMatchIndex} -- the pure decision
 * behind {@link UsbAudioDevice#matchByProductName}, used to persist a
 * VID:PID for a framework-routed USB audio pick in Settings (confirmed on
 * real hardware: picking "USB-Audio - QMX Transceiver" from the AudioManager
 * list persisted VID:PID 0:0, since {@code AudioDeviceInfo} carries no
 * VID:PID of its own and the picker only read it off the USB-direct list).
 */
public class UsbAudioDeviceNameMatchTest {

    @Test
    public void singleMatch_byContainment_returnsItsIndex() {
        // The real-hardware mismatch: the AudioManager pick's name ("USB-Audio -
        // QMX Transceiver") is what's passed in here; the raw UsbDevice list
        // carries the shorter undecorated name ("QMX Transceiver").
        String[] names = {"QMX Transceiver", "CM108 Audio"};
        assertThat(UsbAudioDevice.unambiguousNameMatchIndex(names, "USB-Audio - QMX Transceiver"))
                .isEqualTo(0);
    }

    @Test
    public void noMatch_returnsNegativeOne() {
        String[] names = {"CM108 Audio"};
        assertThat(UsbAudioDevice.unambiguousNameMatchIndex(names, "USB-Audio - QMX Transceiver"))
                .isEqualTo(-1);
    }

    @Test
    public void ambiguousMatch_returnsNegativeOne() {
        // Two physically attached USB audio devices that could both match --
        // never guess which one the operator actually picked.
        String[] names = {"QMX Transceiver", "QMX Transceiver"};
        assertThat(UsbAudioDevice.unambiguousNameMatchIndex(names, "USB-Audio - QMX Transceiver"))
                .isEqualTo(-1);
    }

    @Test
    public void blankOrNullAudioDeviceProductName_returnsNegativeOne() {
        String[] names = {"QMX Transceiver"};
        assertThat(UsbAudioDevice.unambiguousNameMatchIndex(names, null)).isEqualTo(-1);
        assertThat(UsbAudioDevice.unambiguousNameMatchIndex(names, "  ")).isEqualTo(-1);
    }

    @Test
    public void nullEntriesInCandidateList_doNotThrow() {
        String[] names = {null, "QMX Transceiver"};
        assertThat(UsbAudioDevice.unambiguousNameMatchIndex(names, "USB-Audio - QMX Transceiver"))
                .isEqualTo(1);
    }
}
