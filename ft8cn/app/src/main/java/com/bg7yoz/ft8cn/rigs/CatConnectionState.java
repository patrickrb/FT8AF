package com.bg7yoz.ft8cn.rigs;

/**
 * Observable CAT (rig control) connection state, surfaced to the Compose UI so
 * the operator can see at a glance whether the radio is connected and, when it
 * isn't, tap to retry. Bluetooth in particular often only connects on the second
 * attempt (see BluetoothRigConnector), so a persistent indicator beats the
 * transient connect/disconnect Toasts that were the only feedback before.
 *
 * @author Patrick Burns
 */
public enum CatConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
