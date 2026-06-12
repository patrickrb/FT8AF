package radio.ks3ckc.ft8us

import com.bg7yoz.ft8cn.connector.ConnectMode

/**
 * Outcome of evaluating whether to auto-reconnect the Bluetooth (SPP/CAT) rig at startup.
 * Only [CONNECT] triggers a connection; every other value documents why we held off
 * (logged to debug.log for field diagnosis since the hardware path can't be unit-tested).
 */
internal enum class BtAutoConnectDecision {
    CONNECT,
    ALREADY_CONNECTED,
    NOT_BT_MODE,
    NO_SAVED_ADDRESS,
    ADAPTER_OFF,
    NO_PERMISSION,
    NOT_BONDED,
}

/**
 * Pure decision logic for Bluetooth CAT auto-reconnect (issue #223). Kept free of Android
 * types so it can be unit-tested directly; the Activity gathers the real adapter/permission/
 * bond state and feeds it in.
 *
 * Precedence (first match wins):
 *  1. not in Bluetooth connect mode      -> NOT_BT_MODE
 *  2. no remembered device address       -> NO_SAVED_ADDRESS
 *  3. a rig is already connected         -> ALREADY_CONNECTED  (double-connect guard, mirrors USB)
 *  4. the Bluetooth adapter is off       -> ADAPTER_OFF
 *  5. BLUETOOTH_CONNECT not yet granted  -> NO_PERMISSION      (caller retries after grant)
 *  6. the saved device isn't bonded      -> NOT_BONDED
 *  7. otherwise                          -> CONNECT
 */
internal fun decideBluetoothAutoConnect(
    connectMode: Int,
    savedAddress: String?,
    rigConnected: Boolean,
    adapterOn: Boolean,
    hasConnectPermission: Boolean,
    isBonded: Boolean,
): BtAutoConnectDecision {
    if (connectMode != ConnectMode.BLUE_TOOTH) return BtAutoConnectDecision.NOT_BT_MODE
    if (savedAddress.isNullOrBlank()) return BtAutoConnectDecision.NO_SAVED_ADDRESS
    if (rigConnected) return BtAutoConnectDecision.ALREADY_CONNECTED
    if (!adapterOn) return BtAutoConnectDecision.ADAPTER_OFF
    if (!hasConnectPermission) return BtAutoConnectDecision.NO_PERMISSION
    if (!isBonded) return BtAutoConnectDecision.NOT_BONDED
    return BtAutoConnectDecision.CONNECT
}
