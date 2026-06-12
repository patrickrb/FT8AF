package radio.ks3ckc.ft8us

import com.bg7yoz.ft8cn.connector.ConnectMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [decideBluetoothAutoConnect] (issue #223). Pure logic, no Android runtime:
 * [ConnectMode] is a plain class of int constants, so no Robolectric runner is needed.
 */
class BluetoothAutoConnectTest {

    /** All preconditions satisfied -> we should open the SPP/CAT link. */
    @Test
    fun connectsWhenEverythingReady() {
        val decision = decideBluetoothAutoConnect(
            connectMode = ConnectMode.BLUE_TOOTH,
            savedAddress = "AA:BB:CC:DD:EE:FF",
            rigConnected = false,
            adapterOn = true,
            hasConnectPermission = true,
            isBonded = true,
        )
        assertThat(decision).isEqualTo(BtAutoConnectDecision.CONNECT)
    }

    @Test
    fun usbModeIsNotBtMode() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.USB_CABLE,
                savedAddress = "AA:BB:CC:DD:EE:FF",
                rigConnected = false,
                adapterOn = true,
                hasConnectPermission = true,
                isBonded = true,
            ),
        ).isEqualTo(BtAutoConnectDecision.NOT_BT_MODE)
    }

    @Test
    fun networkModeIsNotBtMode() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.NETWORK,
                savedAddress = "AA:BB:CC:DD:EE:FF",
                rigConnected = false,
                adapterOn = true,
                hasConnectPermission = true,
                isBonded = true,
            ),
        ).isEqualTo(BtAutoConnectDecision.NOT_BT_MODE)
    }

    @Test
    fun nullAddressHasNothingToConnectTo() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.BLUE_TOOTH,
                savedAddress = null,
                rigConnected = false,
                adapterOn = true,
                hasConnectPermission = true,
                isBonded = true,
            ),
        ).isEqualTo(BtAutoConnectDecision.NO_SAVED_ADDRESS)
    }

    @Test
    fun blankAddressHasNothingToConnectTo() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.BLUE_TOOTH,
                savedAddress = "   ",
                rigConnected = false,
                adapterOn = true,
                hasConnectPermission = true,
                isBonded = true,
            ),
        ).isEqualTo(BtAutoConnectDecision.NO_SAVED_ADDRESS)
    }

    /** Don't double-connect when a rig is already up (mirrors the USB guard). */
    @Test
    fun alreadyConnectedSkips() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.BLUE_TOOTH,
                savedAddress = "AA:BB:CC:DD:EE:FF",
                rigConnected = true,
                adapterOn = true,
                hasConnectPermission = true,
                isBonded = true,
            ),
        ).isEqualTo(BtAutoConnectDecision.ALREADY_CONNECTED)
    }

    @Test
    fun adapterOffSkips() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.BLUE_TOOTH,
                savedAddress = "AA:BB:CC:DD:EE:FF",
                rigConnected = false,
                adapterOn = false,
                hasConnectPermission = true,
                isBonded = true,
            ),
        ).isEqualTo(BtAutoConnectDecision.ADAPTER_OFF)
    }

    @Test
    fun missingPermissionSkips() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.BLUE_TOOTH,
                savedAddress = "AA:BB:CC:DD:EE:FF",
                rigConnected = false,
                adapterOn = true,
                hasConnectPermission = false,
                isBonded = true,
            ),
        ).isEqualTo(BtAutoConnectDecision.NO_PERMISSION)
    }

    @Test
    fun unbondedDeviceSkips() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.BLUE_TOOTH,
                savedAddress = "AA:BB:CC:DD:EE:FF",
                rigConnected = false,
                adapterOn = true,
                hasConnectPermission = true,
                isBonded = false,
            ),
        ).isEqualTo(BtAutoConnectDecision.NOT_BONDED)
    }

    // ---- precedence: earlier checks win over later ones ----

    @Test
    fun notBtModeWinsOverMissingAddress() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.USB_CABLE,
                savedAddress = null,
                rigConnected = false,
                adapterOn = false,
                hasConnectPermission = false,
                isBonded = false,
            ),
        ).isEqualTo(BtAutoConnectDecision.NOT_BT_MODE)
    }

    @Test
    fun missingAddressWinsOverAlreadyConnected() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.BLUE_TOOTH,
                savedAddress = "",
                rigConnected = true,
                adapterOn = false,
                hasConnectPermission = false,
                isBonded = false,
            ),
        ).isEqualTo(BtAutoConnectDecision.NO_SAVED_ADDRESS)
    }

    @Test
    fun alreadyConnectedWinsOverAdapterOff() {
        assertThat(
            decideBluetoothAutoConnect(
                connectMode = ConnectMode.BLUE_TOOTH,
                savedAddress = "AA:BB:CC:DD:EE:FF",
                rigConnected = true,
                adapterOn = false,
                hasConnectPermission = false,
                isBonded = false,
            ),
        ).isEqualTo(BtAutoConnectDecision.ALREADY_CONNECTED)
    }

    // --- maskBluetoothAddress: keep debug.log free of the raw MAC (PR #228 review) ---

    @Test
    fun maskKeepsOnlyFirstAndLastOctet() {
        assertThat(maskBluetoothAddress("AA:BB:CC:DD:EE:FF")).isEqualTo("AA:**:**:**:**:FF")
    }

    @Test
    fun maskHidesEveryMiddleOctet() {
        // None of the four middle octets may survive anywhere in the output.
        val masked = maskBluetoothAddress("11:22:33:44:55:66")
        for (octet in listOf("22", "33", "44", "55")) {
            assertThat(masked).doesNotContain(octet)
        }
    }

    @Test
    fun maskNullOrBlankAddressIsNone() {
        assertThat(maskBluetoothAddress(null)).isEqualTo("<none>")
        assertThat(maskBluetoothAddress("")).isEqualTo("<none>")
        assertThat(maskBluetoothAddress("   ")).isEqualTo("<none>")
    }

    @Test
    fun maskMalformedAddressLeaksOnlyLengthNotValue() {
        val masked = maskBluetoothAddress("not-a-mac-address")
        assertThat(masked).isEqualTo("<malformed:17>")
        assertThat(masked).doesNotContain("mac")
    }
}
