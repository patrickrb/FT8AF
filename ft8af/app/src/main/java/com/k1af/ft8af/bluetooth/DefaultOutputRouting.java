package com.k1af.ft8af.bluetooth;

import android.media.AudioDeviceInfo;
import android.os.Build;

/**
 * The Android side of the Default-output A2DP steering: turns the enumerated
 * output devices into the plain arrays {@link AudioOutputRoutingPolicy} decides
 * on, and applies its answer to the track through a {@link Sink}.
 *
 * <p>Split out of {@code FT8TransmitSignal.applyDefaultOutputRoutingOverride}
 * so the wiring — device enumeration to policy to
 * {@code AudioTrack.setPreferredDevice}, including the rejection diagnostic —
 * is exercised by a Robolectric test instead of only on a rig (Copilot review
 * on #790). Everything that needs a real {@code AudioTrack} or the app's
 * {@code GeneralVariables} goes through the sink, so the test can capture it.
 */
public final class DefaultOutputRouting {

    private DefaultOutputRouting() {
    }

    /** The two things the adapter needs from the caller. */
    public interface Sink {
        /** {@code AudioTrack.setPreferredDevice(device)}; false when the framework refuses. */
        boolean setPreferredDevice(AudioDeviceInfo device);

        /** One line for debug.log. */
        void log(String line);
    }

    /** debug.log line when the route was applied. */
    public static final String LOG_STEERED =
            "playFT8Signal: default output steered to A2DP (app SCO session up)";
    /** debug.log line when {@code setPreferredDevice} refused the route. */
    public static final String LOG_REJECTED =
            "playFT8Signal: A2DP steering REJECTED by setPreferredDevice;"
                    + " TX audio stays on the OS route";

    /**
     * Decide and apply. Silent when the policy leaves routing to the OS.
     *
     * @param outputs        {@code AudioManager.getDevices(GET_DEVICES_OUTPUTS)}
     * @param scoHeldForTx   {@code MainViewModel.isScoHeldForTx()} — our SCO link
     *                       was up when this over was keyed
     * @param scoAddress     {@code MainViewModel.scoAddressForTx()}, may be null
     * @param sink           applies the route and logs the outcome
     * @return true only when a route was applied and accepted
     */
    public static boolean apply(AudioDeviceInfo[] outputs, boolean scoHeldForTx,
                                String scoAddress, Sink sink) {
        if (outputs == null) return false;
        int[] types = new int[outputs.length];
        String[] addresses = new String[outputs.length];
        for (int i = 0; i < outputs.length; i++) {
            types[i] = outputs[i].getType();
            addresses[i] = deviceAddressOrNull(outputs[i]);
        }
        int idx = AudioOutputRoutingPolicy.pickDefaultOutputIndex(
                types, addresses, scoHeldForTx, scoAddress);
        if (idx == AudioOutputRoutingPolicy.LEAVE_TO_OS) return false;
        // setPreferredDevice returns false when the framework refuses the route.
        // Log what actually happened: an unconditional "steered" line is worse
        // than no line at all, because the next person debugging a dead TX would
        // rule this out on the strength of it.
        boolean applied = sink.setPreferredDevice(outputs[idx]);
        sink.log(applied ? LOG_STEERED : LOG_REJECTED);
        return applied;
    }

    /**
     * {@code AudioDeviceInfo.getAddress()} exists only from API 28. Calling it
     * on the Android 8.1 device this steering exists for (issue #759 follow-up)
     * throws {@link NoSuchMethodError} — a linkage error, which the
     * {@code catch (Exception)} blocks around TX do not catch — before any
     * audio plays. On Android 12+ a Bluetooth address additionally needs the
     * runtime {@code BLUETOOTH_CONNECT} permission, which the user can deny;
     * that must not abort the over either. In both cases the address is simply
     * unknown, and the policy's single-pair fallback still performs the fix.
     */
    static String deviceAddressOrNull(AudioDeviceInfo device) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return null;
        }
    }
}
