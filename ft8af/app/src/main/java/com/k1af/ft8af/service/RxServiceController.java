package com.k1af.ft8af.service;

/**
 * Pure, Android-free policy for {@link RxForegroundService}, extracted so the decisions can
 * be unit-tested without a device (the service itself touches the framework and can't be).
 */
public final class RxServiceController {
    private RxServiceController() {}

    /**
     * Intent action for the notification's Exit button. Defined here (not on the service) so
     * the action-dispatch policy stays Android-free and unit-testable.
     */
    public static final String ACTION_EXIT = "com.k1af.ft8af.service.EXIT";

    /** What an incoming start command asks the service to do. */
    public enum Command { RUN, EXIT }

    /**
     * The RX foreground service should run only when receiving is active AND the microphone
     * permission is granted — starting a microphone-typed foreground service without
     * RECORD_AUDIO throws on Android 14.
     */
    public static boolean shouldRunService(boolean rxActive, boolean micGranted) {
        return rxActive && micGranted;
    }

    /**
     * Maps a start-command intent action to a {@link Command}. Anything other than the exact
     * exit action (including null, from plain {@code start()} intents or a system restart)
     * means "run normally".
     */
    public static Command commandForAction(String action) {
        return ACTION_EXIT.equals(action) ? Command.EXIT : Command.RUN;
    }
}
