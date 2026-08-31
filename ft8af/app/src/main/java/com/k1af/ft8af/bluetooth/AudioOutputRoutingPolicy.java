package com.k1af.ft8af.bluetooth;

/**
 * Decides whether the TX audio path should override a "Default" output selection
 * with the paired Bluetooth A2DP device. Pure ints — no Android runtime — so the
 * whole decision is unit-testable.
 *
 * <p>Why this exists (issue #759 follow-up on Android 8.1): PR #772 makes the
 * SCO link come up reliably so the mic captures over Bluetooth. Once it does,
 * a tester reported that with both audio input and audio output set to
 * "Default", TX audio no longer reaches the transceiver — but manually picking
 * the paired device's A2DP profile as the output makes TX work again. Android's
 * routing keeps the {@code USAGE_MEDIA} stream on the SCO (narrowband
 * hands-free) path while SCO is up, and the paired transceiver only listens on
 * the A2DP profile for the FT8 tone, so TX goes into the void.
 *
 * <p>Rule: when the user chose "Default" output and an A2DP device <em>and</em>
 * a SCO device are both currently in the output list (the tell-tale state where
 * A2DP + SCO share the same paired headset), steer TX to A2DP. When SCO is not
 * present, leave the OS routing alone — a phone paired only for A2DP music has
 * never had this problem and picking A2DP over speakers would be surprising.
 */
public final class AudioOutputRoutingPolicy {

    private AudioOutputRoutingPolicy() {
    }

    /**
     * Mirror of {@code android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP}
     * (stable framework constant = 8), kept as a literal so this class stays
     * Android-free.
     */
    public static final int TYPE_BLUETOOTH_A2DP = 8;

    /**
     * Mirror of {@code android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO}
     * (stable framework constant = 7).
     */
    public static final int TYPE_BLUETOOTH_SCO = 7;

    /**
     * Sentinel from {@link #pickDefaultOutputIndex} for "let the OS pick".
     */
    public static final int LEAVE_TO_OS = -1;

    /**
     * When the user picked "Default" output, decide which enumerated device (by
     * index into {@code deviceTypes}) to hand to {@code AudioTrack.setPreferredDevice}.
     *
     * @param deviceTypes the {@code AudioDeviceInfo.getType()} of every currently
     *                    connected output device, in enumeration order
     * @return the index of the A2DP device to prefer, or {@link #LEAVE_TO_OS}
     *         when the OS's own default routing is fine
     */
    public static int pickDefaultOutputIndex(int[] deviceTypes) {
        if (deviceTypes == null) return LEAVE_TO_OS;
        int a2dpIdx = LEAVE_TO_OS;
        boolean scoPresent = false;
        for (int i = 0; i < deviceTypes.length; i++) {
            int type = deviceTypes[i];
            if (type == TYPE_BLUETOOTH_A2DP) {
                if (a2dpIdx == LEAVE_TO_OS) a2dpIdx = i;
            } else if (type == TYPE_BLUETOOTH_SCO) {
                scoPresent = true;
            }
        }
        return scoPresent ? a2dpIdx : LEAVE_TO_OS;
    }
}
