package com.k1af.ft8af.wave;

import android.util.Log;

/**
 * JNI bridge to hamlib, hosted inside {@code libft8af.so}.
 *
 * <p>hamlib owns the per-radio CAT protocol; the app owns the byte transport
 * (its existing USB-serial {@code CableSerialPort}). Native code presents each
 * rig to hamlib as a loopback-TCP "network" port and bridges that socket to
 * Java: bytes hamlib wants to send to the radio arrive via
 * {@link HamlibSink#onBytesToRig(byte[])}, and bytes the radio sends back are
 * pushed in via {@link #feedFromRig(long, byte[])}. See
 * {@code cpp/ft8af_glue/hamlib_jni.cpp} and {@code cpp/hamlib/HAMLIB_PIN.txt}.
 *
 * <p>All {@code native} calls except {@link #feedFromRig} are synchronous CAT
 * round-trips and must be invoked off the UI thread and off the serial read
 * thread (that thread delivers {@link #feedFromRig}); {@code HamlibRig} runs
 * them on its own single worker.
 */
public final class HamlibNative {
    private static final String TAG = "HamlibNative";
    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("ft8af");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load libft8af: " + e.getMessage());
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private HamlibNative() {}

    /** Whether {@code libft8af.so} (with the hamlib bridge) loaded. */
    public static boolean isAvailable() {
        return LIBRARY_LOADED;
    }

    /**
     * Sink for CAT bytes hamlib wants delivered to the radio. Implemented by
     * {@code HamlibRig}, which forwards them to the active connector.
     * Invoked on the native pump thread — do not block.
     */
    public interface HamlibSink {
        void onBytesToRig(byte[] data);
    }

    /**
     * Init + open a hamlib rig, wiring its transport to {@code sink}.
     *
     * @param modelId hamlib rig model number (e.g. 3085 = IC-705)
     * @param sink    receives CAT bytes bound for the radio
     * @return opaque handle (&gt; 0) for the other calls, or 0 on failure
     */
    public static native long nativeOpen(int modelId, HamlibSink sink);

    /** Push bytes received from the radio into hamlib's read side. */
    public static native void nativeFeedFromRig(long handle, byte[] data);

    /** Set the operating frequency in Hz. Returns 0 (RIG_OK) on success. */
    public static native int nativeSetFreq(long handle, long hz);

    /** Read the operating frequency in Hz, or -1 on error. */
    public static native long nativeGetFreq(long handle);

    /** Set the mode by hamlib mode string ("USB", "PKTUSB", …). 0 on success. */
    public static native int nativeSetMode(long handle, String mode);

    /** Set PTT on/off. Returns 0 (RIG_OK) on success. */
    public static native int nativeSetPtt(long handle, boolean on);

    /** Close + free the rig and its bridge. Safe to call once per handle. */
    public static native void nativeClose(long handle);
}
