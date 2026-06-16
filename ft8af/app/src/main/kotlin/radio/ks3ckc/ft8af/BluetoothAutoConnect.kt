package radio.ks3ckc.ft8af

import com.k1af.ft8af.connector.ConnectMode

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

/**
 * What the [BluetoothRigConnector.getInstance] singleton should do when called.
 */
internal enum class BtConnectorAction {
    /** No existing connector; create one from scratch (binds the service). */
    CREATE_NEW,
    /** A connector exists but for a different address — disconnect and reconnect to the new one. */
    RECONNECT_NEW_ADDRESS,
    /** Same address, but the previous attempt failed or was disconnected — retry. */
    RETRY,
    /** Same address, already connected or a connection attempt is in progress — do nothing. */
    NO_ACTION,
}

/**
 * Pure decision logic for [BluetoothRigConnector.getInstance] (issue #223 — CAT over Bluetooth).
 * Before this was extracted, a stale singleton whose previous connect failed would silently
 * refuse to reconnect when the user re-entered Settings, because `getInstance()` only retried
 * when the address *changed*.
 *
 * @param hasExisting  true when the static singleton is non-null
 * @param sameAddress  true when the existing singleton's address equals the requested address
 * @param isConnected  true when the existing singleton has an active RFCOMM link
 * @param isPending    true when the existing singleton has a connection attempt in flight
 */
internal fun decideBtConnectorAction(
    hasExisting: Boolean,
    sameAddress: Boolean,
    isConnected: Boolean,
    isPending: Boolean,
): BtConnectorAction {
    if (!hasExisting) return BtConnectorAction.CREATE_NEW
    if (!sameAddress) return BtConnectorAction.RECONNECT_NEW_ADDRESS
    if (isConnected || isPending) return BtConnectorAction.NO_ACTION
    return BtConnectorAction.RETRY
}

/**
 * Redact a Bluetooth MAC for debug.log, which users attach to bug reports. A MAC is a stable
 * device identifier, so we keep only the first and last octet (enough to correlate log lines)
 * and hide the middle four (PR #228 review). A null/blank value becomes `<none>`; a corrupted
 * non-MAC value reveals only its length so the raw identifier never lands in a shareable log.
 */
internal fun maskBluetoothAddress(addr: String?): String {
    if (addr.isNullOrBlank()) return "<none>"
    val parts = addr.split(":")
    if (parts.size == 6) return "${parts.first()}:**:**:**:**:${parts.last()}"
    return "<malformed:${addr.length}>"
}
