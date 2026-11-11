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
}
