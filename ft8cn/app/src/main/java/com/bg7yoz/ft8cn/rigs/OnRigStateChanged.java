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
}
