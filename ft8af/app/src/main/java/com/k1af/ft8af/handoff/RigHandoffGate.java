package com.k1af.ft8af.handoff;

/**
 * Process-wide "we have handed the rig to a sibling app" flag.
 *
 * <p>Separate from {@link RigHandoffController} because the code that must respect it —
 * the USB-audio open in {@code MicRecorder}, the serial auto-connect — runs on capture and
 * retry threads that have no reference to the controller and must not need one. While
 * yielded, nothing in this app may open the rig's CAT port or claim its USB audio: every
 * automatic path (USB attach, capture-failure retry, port re-enumeration) would otherwise
 * quietly take the rig straight back from the app the operator is actually using.
 */
public final class RigHandoffGate {

    private RigHandoffGate() {}

    private static volatile String yieldedTo = null;

    /** True while a sibling app owns the rig. */
    public static boolean isYielded() {
        return yieldedTo != null;
    }

    /** The package the rig was handed to, or null when this app may use the rig. */
    public static String yieldedTo() {
        return yieldedTo;
    }

    static void yieldTo(String pkg) {
        yieldedTo = pkg;
    }

    static void clear() {
        yieldedTo = null;
    }

    /**
     * Whether the direct-USB audio input may be opened right now: one must be configured
     * ({@code audioInputDeviceId == -1} with a vendor id) and the rig must not be on loan.
     */
    public static boolean mayOpenUsbAudioInput(int audioInputDeviceId, int usbVendorId) {
        return audioInputDeviceId == -1 && usbVendorId != 0 && !isYielded();
    }
}
