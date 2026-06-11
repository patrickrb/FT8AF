package com.bg7yoz.ft8cn.rigs;

/**
 * Callback for rig state changes.
 * @author BGY70Z
 * @date 2023-03-20
 */
public interface OnRigStateChanged {
    void onDisconnected();
    void onConnected();
    void onPttChanged(boolean isOn);
    void onFreqChanged(long freq);
    void onRunError(String message);

    /**
     * Called when a connection attempt has started but not yet succeeded or
     * failed. Default no-op so existing implementers don't need to change.
     */
    default void onConnecting() {}

    /**
     * Called whenever the rig answers with a valid frequency, even if the dial hasn't
     * moved. {@link #onFreqChanged(long)} only fires on an actual change, so it can't be
     * used as a liveness signal — a rig parked on one frequency would look dead. The CAT
     * liveness watchdog keys off this instead. Default no-op so existing implementers don't
     * need to change.
     */
    default void onRigResponded() {}
}
