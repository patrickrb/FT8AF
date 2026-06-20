package com.k1af.ft8af.service;

/**
 * Pure, Android-free policy for {@link RxForegroundService}, extracted so the decisions can
 * be unit-tested without a device (the service itself touches the framework and can't be).
 */
public final class RxServiceController {
    private RxServiceController() {}

    /**
     * The RX foreground service should run only when receiving is active AND the microphone
     * permission is granted — starting a microphone-typed foreground service without
     * RECORD_AUDIO throws on Android 14.
     */
    public static boolean shouldRunService(boolean rxActive, boolean micGranted) {
        return rxActive && micGranted;
    }

    /**
     * Whether to start the service with the {@code microphone} foreground-service type. The
     * type is enforced from Android 11 (R, API 30); below that a plain foreground service
     * already keeps mic capture alive, and passing a type can be rejected.
     */
    public static boolean usesMicrophoneType(int sdkInt) {
        return sdkInt >= 30; // Build.VERSION_CODES.R
    }
}
