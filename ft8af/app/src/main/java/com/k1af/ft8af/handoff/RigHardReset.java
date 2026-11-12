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
        /** It is a USB serial port the rig's CAT link would use (USB-cable mode only). */
        public final boolean rigSerial;
        public final boolean hasPermission;

        public UsbId(int vendorId, int productId, boolean configuredAudio,
                     boolean hasPermission) {
            this(vendorId, productId, configuredAudio, false, hasPermission);
        }

        public UsbId(int vendorId, int productId, boolean configuredAudio,
                     boolean rigSerial, boolean hasPermission) {
            this.vendorId = vendorId;
            this.productId = productId;
            this.configuredAudio = configuredAudio;
            this.rigSerial = rigSerial;
            this.hasPermission = hasPermission;
        }

        boolean usedForRig() {
            return configuredAudio || rigSerial;
        }
    }

    /** The attached USB devices this app uses to reach the rig, each once. */
    public static List<UsbId> rigDevices(List<UsbId> attached) {
        List<UsbId> used = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (UsbId d : attached) {
            if (!d.usedForRig()) continue;
            long key = ((long) d.vendorId << 32) | (d.productId & 0xFFFFFFFFL);
            if (seen.add(key)) used.add(d);
        }
        return used;
    }

    /**
     * The rig devices this app is not allowed to open — the ones Android will raise a
     * dialog for. Empty means there is nothing to ask: every permission is in place.
     */
    public static List<UsbId> missingPermission(List<UsbId> attached) {
        List<UsbId> missing = new ArrayList<>();
        for (UsbId d : rigDevices(attached)) {
            if (!d.hasPermission) missing.add(d);
        }
        return missing;
    }

    /** What the reset found, for telling the operator what to expect. */
    public static final class Result {
        public final int usbDevicesFound;
        public final int usbPermissionsMissing;
        public final boolean usbCableMode;

        public Result(int usbDevicesFound, int usbPermissionsMissing, boolean usbCableMode) {
            this.usbDevicesFound = usbDevicesFound;
            this.usbPermissionsMissing = usbPermissionsMissing;
            this.usbCableMode = usbCableMode;
        }
    }

    /** What to tell the operator after the button is pressed. */
    public enum Outcome {
        /** A permission is missing; Android is about to ask (or is asking) for it. */
        ASKING,
        /** Nothing to ask: every permission the app needs is already granted. */
        ALL_GRANTED,
        /** Nothing to ask, but a USB-cable rig is configured and no USB device is attached. */
        NO_USB_DEVICE
    }

    /**
     * @param micPermissionMissing the microphone (audio capture) runtime permission is not
     *                             granted, so the app is asking for that too
     */
    public static Outcome outcome(Result result, boolean micPermissionMissing) {
        if (micPermissionMissing || result.usbPermissionsMissing > 0) return Outcome.ASKING;
        if (result.usbCableMode && result.usbDevicesFound == 0) return Outcome.NO_USB_DEVICE;
        return Outcome.ALL_GRANTED;
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
