package com.k1af.ft8af.connector;

/**
 * Connector callback interface
 * @author BGY70Z
 * @date 2023-03-20
 */
public interface OnConnectorStateChanged {
    void onDisconnected();
    void onConnected();
    void onRunError(String message);

    /**
     * Called when a connection attempt has started but not yet succeeded or
     * failed. Default no-op so existing implementers don't need to change.
     */
    default void onConnecting() {}
}
