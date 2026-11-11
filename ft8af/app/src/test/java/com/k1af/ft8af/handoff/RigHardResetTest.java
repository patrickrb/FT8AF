package com.k1af.ft8af.handoff;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure-JVM tests for {@link RigHardReset}. */
public class RigHardResetTest {

    private static RigHardReset.UsbId usb(int vid, int pid, boolean audio, boolean permitted) {
        return new RigHardReset.UsbId(vid, pid, audio, permitted);
    }

    @Test
    public void audioDevicesToAsk_onlyConfiguredAudioWithoutPermission() {
        RigHardReset.UsbId soundCardNoPerm = usb(0x0D8C, 0x0012, true, false);
        List<RigHardReset.UsbId> ask = RigHardReset.audioDevicesToAsk(Arrays.asList(
                usb(0x10C4, 0xEA70, false, false), // CAT serial: asked by the reconnect
                soundCardNoPerm,
                usb(0x1234, 0x0001, true, true),    // already allowed: nothing to ask
                usb(0x046D, 0xC52B, false, true))); // unrelated device

        assertThat(ask).containsExactly(soundCardNoPerm);
    }

    @Test
    public void audioDevicesToAsk_asksOncePerDevice() {
        // One sound card listed as both the configured input and the configured output.
        List<RigHardReset.UsbId> ask = RigHardReset.audioDevicesToAsk(Arrays.asList(
                usb(0x0D8C, 0x0012, true, false),
                usb(0x0D8C, 0x0012, true, false)));

        assertThat(ask).hasSize(1);
    }

    @Test
    public void audioDevicesToAsk_distinguishesProductsOfOneVendor() {
        List<RigHardReset.UsbId> ask = RigHardReset.audioDevicesToAsk(Arrays.asList(
                usb(0x0D8C, 0x0012, true, false),
                usb(0x0D8C, 0x0014, true, false)));

        assertThat(ask).hasSize(2);
    }

    @Test
    public void audioDevicesToAsk_emptyWhenNothingAttached() {
        assertThat(RigHardReset.audioDevicesToAsk(
                Collections.<RigHardReset.UsbId>emptyList())).isEmpty();
    }

    @Test
    public void planReconnect_usbCableAlwaysRescans() {
        // Even with no previous connector: the scan is what finds a freshly allowed port.
        assertThat(RigHardReset.planReconnect(true, false))
                .isEqualTo(RigHardReset.Reconnect.SCAN_USB);
        assertThat(RigHardReset.planReconnect(true, true))
                .isEqualTo(RigHardReset.Reconnect.SCAN_USB);
    }

    @Test
    public void planReconnect_otherLinksReuseTheirConnector() {
        assertThat(RigHardReset.planReconnect(false, true))
                .isEqualTo(RigHardReset.Reconnect.RECONNECT_EXISTING);
        assertThat(RigHardReset.planReconnect(false, false))
                .isEqualTo(RigHardReset.Reconnect.NONE);
    }

    @Test
    public void missingPermission_countsTheSerialPortAsWellAsAudio() {
        RigHardReset.UsbId cat = new RigHardReset.UsbId(0x10C4, 0xEA70, false, true, false);
        RigHardReset.UsbId card = usb(0x0D8C, 0x0012, true, false);
        List<RigHardReset.UsbId> attached = Arrays.asList(
                cat, card,
                usb(0x046D, 0xC52B, false, false)); // unrelated: never asked, never counted

        assertThat(RigHardReset.rigDevices(attached)).containsExactly(cat, card);
        assertThat(RigHardReset.missingPermission(attached)).containsExactly(cat, card);
    }

    @Test
    public void missingPermission_emptyWhenEverythingIsAllowed() {
        List<RigHardReset.UsbId> attached = Arrays.asList(
                new RigHardReset.UsbId(0x10C4, 0xEA70, false, true, true),
                usb(0x0D8C, 0x0012, true, true));

        assertThat(RigHardReset.rigDevices(attached)).hasSize(2);
        assertThat(RigHardReset.missingPermission(attached)).isEmpty();
    }

    @Test
    public void rigDevices_oneCompositeDeviceCountsOnce() {
        // A rig whose single USB device is both the CAT port and the sound card.
        assertThat(RigHardReset.rigDevices(Arrays.asList(
                new RigHardReset.UsbId(0x0C26, 0x0036, true, true, false),
                new RigHardReset.UsbId(0x0C26, 0x0036, true, true, false)))).hasSize(1);
    }

    @Test
    public void outcome_askingWhenAUsbPermissionIsMissing() {
        assertThat(RigHardReset.outcome(new RigHardReset.Result(2, 1, true), false))
                .isEqualTo(RigHardReset.Outcome.ASKING);
    }

    @Test
    public void outcome_askingWhenOnlyTheMicrophoneIsMissing() {
        assertThat(RigHardReset.outcome(new RigHardReset.Result(2, 0, true), true))
                .isEqualTo(RigHardReset.Outcome.ASKING);
    }

    @Test
    public void outcome_allGrantedWhenNothingToAsk() {
        assertThat(RigHardReset.outcome(new RigHardReset.Result(2, 0, true), false))
                .isEqualTo(RigHardReset.Outcome.ALL_GRANTED);
    }

    @Test
    public void outcome_usbCableRigWithNothingAttachedSaysSo() {
        // "All granted" would be true but useless: the radio is not plugged in.
        assertThat(RigHardReset.outcome(new RigHardReset.Result(0, 0, true), false))
                .isEqualTo(RigHardReset.Outcome.NO_USB_DEVICE);
    }

    @Test
    public void outcome_nonUsbRigNeedsNoUsbDevice() {
        // Bluetooth / network rig: no USB device is expected.
        assertThat(RigHardReset.outcome(new RigHardReset.Result(0, 0, false), false))
                .isEqualTo(RigHardReset.Outcome.ALL_GRANTED);
    }
}
