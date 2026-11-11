package com.k1af.ft8af.handoff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decisions for the operator's "Take control of radio" button — the manual, do-everything
 * version of the automatic handoff in {@link RigHandoffController}. Pure and Android-free;
 * {@code MainViewModel.hardResetRigConnection()} does the actual work.
 *
 * <p>The reset: claim the rig from sibling apps, stop any transmission, drop the CAT link,
 * forget the USB-permission dialog cooldown, ask again for every USB permission this app
 * needs and does not hold, then reconnect the rig and rebuild the audio input from scratch.
 *
 * <p>What it cannot do: Android gives an app no way to give up a USB permission it already
 * holds, so a device that is already allowed is not re-prompted — there is nothing to ask.
 */
public final class RigHardReset {

    private RigHardReset() {}

    /**
     * How long after the teardown the reconnect runs: long enough for a sibling app to act
     * on the takeover (it answers within a few hundred ms; a transmitting one takes
     * {@link RigHandoffController#UNKEY_GRACE_MS} first) and for the closed port to settle.
     */
    public static final long RECONNECT_DELAY_MS = 1_000;

    /** One attached USB device, as far as the reset cares. */
    public static final class UsbId {
        public final int vendorId;
        public final int productId;
        /** It is the operator's chosen direct-USB audio input or output. */
        public final boolean configuredAudio;
        public final boolean hasPermission;

        public UsbId(int vendorId, int productId, boolean configuredAudio,
                     boolean hasPermission) {
            this.vendorId = vendorId;
            this.productId = productId;
            this.configuredAudio = configuredAudio;
            this.hasPermission = hasPermission;
        }
    }

    /**
     * The USB audio devices to raise a permission dialog for: the ones the operator has
     * configured and this app is not currently allowed to open, each once. (The CAT serial
     * device is asked for by the reconnect itself, through the serial port's own
     * permission flow, so it is not listed here.)
     */
    public static List<UsbId> audioDevicesToAsk(List<UsbId> attached) {
        List<UsbId> ask = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (UsbId d : attached) {
            if (!d.configuredAudio || d.hasPermission) continue;
            long key = ((long) d.vendorId << 32) | (d.productId & 0xFFFFFFFFL);
            if (seen.add(key)) ask.add(d);
        }
        return ask;
    }

    /** How the rig link is brought back after the teardown. */
    public enum Reconnect {
        /** Re-enumerate USB serial ports; the port observer auto-connects to the rig. */
        SCAN_USB,
        /** Re-run the existing connector (Bluetooth, network rigs). */
        RECONNECT_EXISTING,
        /** Nothing was connected and nothing can be found automatically. */
        NONE
    }

    /**
     * @param usbCableMode the operator's connection mode is USB cable
     * @param hadConnector a rig connector existed before the reset tore it down
     */
    public static Reconnect planReconnect(boolean usbCableMode, boolean hadConnector) {
        if (usbCableMode) return Reconnect.SCAN_USB;
        return hadConnector ? Reconnect.RECONNECT_EXISTING : Reconnect.NONE;
    }
}
