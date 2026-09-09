package com.k1af.ft8af.wave;

import android.util.Log;

/**
 * JNI bridge to {@code libft8af_usb.so}: libusb-backed USB Audio Class
 * isochronous capture.
 *
 * <p>The Java side ({@link UsbAudioDevice}) opens the device, claims the
 * audio streaming interface, finds the iso input endpoint, and sets the
 * alt-setting (which is what actually starts the audio stream) using the
 * stock Android {@code UsbManager} APIs. It then hands the resulting
 * file descriptor + interface parameters to {@link #nativeStart} here.
 * Native runs the iso transfers via libusb and calls back into Java with
 * mono float samples at the target sample rate.
 *
 * <p>We do this instead of using {@link android.hardware.usb.UsbRequest}
 * because that API's iso path silently fails on some kernels — notably
 * the car-dash Android 11 tablets — throwing
 * {@code IllegalStateException: request is not initialized} on first
 * {@code queue()}.
 */
public final class UsbAudioNative {
    private static final String TAG = "UsbAudioNative";
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

    private UsbAudioNative() {}

    /**
     * Set true by {@link #cancelWrite()} when the user presses STOP, cleared by
     * {@link #resetCancel()} at the start of each transmission. Read by the
     * {@code UsbRequest} fallback loop in {@link UsbAudioDevice#writeAudio} so it
     * can break out between chunks. The libusb path uses the native cancel flag
     * instead (see {@link #nativeCancelWrite()}).
     */
    public static volatile boolean writeCancelled = false;

    /** Whether {@code libft8af_usb.so} was loaded at process start. */
    public static boolean isAvailable() {
        return LIBRARY_LOADED;
    }

    /** Clear the cancel flag before a new transmission. */
    public static void resetCancel() {
        writeCancelled = false;
    }

    /**
     * Set the TX output gain applied live by {@link #nativeWrite} as it drains
     * the PCM buffer. Unlike the old model (volume baked into the samples in
     * Java before the write started, so a slider move only took effect next
     * cycle), the native drain loop multiplies each int16 sample by this value
     * as it copies it into the iso transfer buffers — so a change here ramps the
     * on-air level within the buffered-ahead window (~tens of ms), mid-over.
     * This is the safety-critical path for protecting the rig from overdrive.
     *
     * <p>Safe to call from any thread at any time; a no-op if the native lib
     * isn't loaded. Clamped to [0, 1] on the native side.
     *
     * @param volume gain 0.0–1.0
     */
    public static void setTxVolume(float volume) {
        if (LIBRARY_LOADED) {
            nativeSetTxVolume(volume);
        }
    }

    /**
     * Abort an in-progress transmission immediately. Safe to call from the UI
     * thread while a write is blocked on the transmit worker thread: flags the
     * Java fallback loop and, when the native lib is present, signals the libusb
     * drain loop to cancel its in-flight iso transfers. No-op if nothing is
     * transmitting.
     */
    public static void cancelWrite() {
        writeCancelled = true;
        if (LIBRARY_LOADED) {
            nativeCancelWrite();
        }
    }

    /**
     * Callback for audio samples coming off the iso transfer pipeline.
     * Methods are invoked on the libusb event-loop worker thread; callers
     * must not block.
     */
    public interface AudioInputCallback {
        /**
         * Called for each decimated block of mono float samples. The
         * array is allocated fresh per callback; do whatever you need
         * with it before returning, the native side may free it.
         *
         * @param data   mono samples in [-1, 1] at the target sample rate
         * @param length number of valid samples (always == data.length here)
         */
        void onAudioData(float[] data, int length);

        /**
         * Called once when capture ends — either through an explicit
         * {@link #nativeStop} or because all in-flight transfers
         * retired (device gone, kernel refused to keep accepting URBs,
         * etc).
         *
         * @param code 0 for normal exit, non-zero is the libusb error
         *             code from the last failed operation
         */
        void onCaptureStopped(int code);
    }

    /** Sentinel string from native. Phase 1 diagnostic; safe to remove later. */
    public static native String nativeBuildString();

    /**
     * Start an iso capture session.
     *
     * @param fd                  file descriptor from
     *                            {@link android.hardware.usb.UsbDeviceConnection#getFileDescriptor()}.
     *                            Caller retains ownership; do not close it
     *                            while the session is running.
     * @param interfaceNumber     bInterfaceNumber of the audio streaming interface
     *                            (already claimed by the Java side via UsbManager).
     * @param altSetting          alt-setting already activated on that interface
     *                            (informational; native does not re-set it).
     * @param endpointAddress     bEndpointAddress of the iso IN endpoint.
     * @param maxPacketSize       wMaxPacketSize from the endpoint descriptor.
     * @param inputSampleRate     device sample rate (typically 48000).
     * @param inputChannels       1 (mono) or 2 (stereo); stereo is folded to
     *                            mono per {@code channelSelect}.
     * @param inputBytesPerSample 2 for 16-bit PCM (the only format we handle).
     * @param targetSampleRate    rate we emit to the callback (12000 for FT8).
     * @param channelSelect       stereo fold, mirroring {@link AudioChannelSelect}:
     *                            0 = mix L+R, 1 = left only, 2 = right only.
     *                            Fixed for the session; MicRecorder restarts
     *                            capture when the operator changes it.
     * @param callback            invoked from native worker thread.
     * @return                    opaque session handle for {@link #nativeStop},
     *                            or 0 on failure.
     */
    public static native long nativeStart(
            int fd,
            int interfaceNumber,
            int altSetting,
            int endpointAddress,
            int maxPacketSize,
            int inputSampleRate,
            int inputChannels,
            int inputBytesPerSample,
            int targetSampleRate,
            int channelSelect,
            AudioInputCallback callback);

    /** Stop a session started by {@link #nativeStart}. Blocks until the
     *  event thread has drained outstanding URBs and the callback's
     *  {@code onCaptureStopped} has been invoked. */
    public static native void nativeStop(long handle);

    /**
     * Synchronously push a complete PCM buffer out through an iso OUT
     * endpoint. Mirrors {@link #nativeStart} but for transmit — caller
     * already has the endpoint open and alt-setting active, and hands us
     * the fd plus the byte buffer to drain. Blocks until the kernel has
     * accepted every packet (or an error occurs).
     *
     * @param fd                   file descriptor from
     *                             {@link android.hardware.usb.UsbDeviceConnection#getFileDescriptor()}.
     * @param interfaceNumber      bInterfaceNumber of the audio streaming OUT interface.
     * @param altSetting           alt-setting already activated on that interface.
     * @param endpointAddress      bEndpointAddress of the iso OUT endpoint.
     * @param maxPacketSize        wMaxPacketSize from the endpoint descriptor.
     * @param outputSampleRate     device sample rate (informational).
     * @param outputChannels       device channel count (informational; buffer is
     *                             expected to already be interleaved correctly).
     * @param outputBytesPerSample 2 for 16-bit PCM (informational).
     * @param pcmData              interleaved little-endian int16 PCM bytes,
     *                             already at outputSampleRate × outputChannels.
     * @return                     0 on success, or a negative libusb error code
     *                             ({@code LIBUSB_ERROR_*}) on failure. Caller
     *                             should fall back to the {@code UsbRequest} path
     *                             on a non-zero return.
     */
    public static native int nativeWrite(
            int fd,
            int interfaceNumber,
            int altSetting,
            int endpointAddress,
            int maxPacketSize,
            int outputSampleRate,
            int outputChannels,
            int outputBytesPerSample,
            byte[] pcmData);

    /**
     * Request that an in-progress {@link #nativeWrite} abort. Sets a native
     * cancel flag and wakes the libusb event loop so the blocked write returns
     * within ~tens of ms, cancelling its in-flight iso transfers. Safe to call
     * from any thread; a no-op if no write is running. Prefer {@link #cancelWrite()}
     * which also covers the Java fallback path.
     */
    public static native void nativeCancelWrite();

    /**
     * Set the live TX output gain read by {@link #nativeWrite}'s drain loop.
     * Stores into a native atomic; takes effect on the next iso packet filled
     * (~tens of ms). Clamped to [0, 1] natively. Prefer {@link #setTxVolume}
     * which guards on library availability.
     *
     * @param volume gain 0.0–1.0
     */
    public static native void nativeSetTxVolume(float volume);
}
