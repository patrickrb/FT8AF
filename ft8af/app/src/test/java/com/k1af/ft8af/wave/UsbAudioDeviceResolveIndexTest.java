package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link UsbAudioDevice#resolveDeviceIndex} -- the stale-id fallback
 * behind a real-hardware bug: a QMX power-cycle alone re-enumerated its USB
 * audio interface with a new {@code AudioDeviceInfo} id, reverting the
 * Settings audio-device picker (and the actual {@code AudioRecord}/{@code
 * AudioTrack} routing) to "Default" even though the same physical device
 * was still attached under its stable product name.
 */
public class UsbAudioDeviceResolveIndexTest {

    @Test
    public void exactIdMatch_returnsThatIndex() {
        int[] ids = {5, 7, 9};
        String[] names = {"A", "B", "C"};
        assertThat(UsbAudioDevice.resolveDeviceIndex(ids, names, 7, "unused")).isEqualTo(1);
    }

    @Test
    public void noExactMatch_fallsBackToProductName() {
        // The persisted id (1285) is stale after a re-enumeration; the device is
        // still present under the same product name at a new id (1340).
        int[] ids = {1340, 42};
        String[] names = {"QMX Transceiver", "Built-in Mic"};
        assertThat(UsbAudioDevice.resolveDeviceIndex(ids, names, 1285, "QMX Transceiver"))
                .isEqualTo(0);
    }

    @Test
    public void noExactMatch_noFallbackName_returnsNegativeOne() {
        int[] ids = {1340};
        String[] names = {"QMX Transceiver"};
        assertThat(UsbAudioDevice.resolveDeviceIndex(ids, names, 1285, null)).isEqualTo(-1);
    }

    @Test
    public void noExactMatch_fallbackNameNotPresent_returnsNegativeOne() {
        int[] ids = {42};
        String[] names = {"Built-in Mic"};
        assertThat(UsbAudioDevice.resolveDeviceIndex(ids, names, 1285, "QMX Transceiver"))
                .isEqualTo(-1);
    }

    @Test
    public void fallsBackByContainment_whenAudioManagerDecoratesTheRawProductName() {
        // The exact real-hardware mismatch found via dumpsys: UsbDevice.getProductName()
        // gives "QMX Transceiver" (what callers persist/pass as fallbackProductName), but
        // the AudioManager/AudioDeviceInfo list name for the same card is prefixed:
        // "USB-Audio - QMX Transceiver". These are never byte-identical, so matching must
        // be containment, not equality.
        int[] ids = {1340};
        String[] names = {"USB-Audio - QMX Transceiver"};
        assertThat(UsbAudioDevice.resolveDeviceIndex(ids, names, 1285, "QMX Transceiver"))
                .isEqualTo(0);
    }

    @Test
    public void nullProductNameEntries_doNotThrow() {
        // AudioDeviceInfo.getProductName() can legitimately return null.
        int[] ids = {1, 2};
        String[] names = {null, "QMX Transceiver"};
        assertThat(UsbAudioDevice.resolveDeviceIndex(ids, names, 99, "QMX Transceiver"))
                .isEqualTo(1);
        assertThat(UsbAudioDevice.resolveDeviceIndex(ids, names, 99, null)).isEqualTo(-1);
    }

    @Test
    public void emptyLists_returnNegativeOne() {
        assertThat(UsbAudioDevice.resolveDeviceIndex(new int[0], new String[0], 5, "anything"))
                .isEqualTo(-1);
    }
}
