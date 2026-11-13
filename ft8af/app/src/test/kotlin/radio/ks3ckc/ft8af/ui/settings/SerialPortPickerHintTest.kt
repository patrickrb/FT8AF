package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.connector.CableSerialPort
import com.k1af.ft8af.connector.SerialPortLabel
import com.k1af.ft8af.serialport.UsbId
import org.junit.Test

/**
 * [shouldShowDualPortHint] — the picker's "which port is CAT depends on your
 * rig" note appears only when a dual-UART chip with named interfaces (CP2105)
 * is in the list, so a single-port user never sees an irrelevant paragraph.
 */
class SerialPortPickerHintTest {
    private fun port(productId: Int, portNum: Int, portCount: Int) = CableSerialPort.SerialPort(
        1001, UsbId.VENDOR_SILABS, productId, portNum, portCount, SerialPortLabel.DRIVER_CP21XX,
    )

    @Test
    fun hidden_forNullOrEmpty() {
        assertThat(shouldShowDualPortHint(null)).isFalse()
        assertThat(shouldShowDualPortHint(emptyList())).isFalse()
    }

    @Test
    fun hidden_forSinglePortChips() {
        assertThat(shouldShowDualPortHint(listOf(port(UsbId.SILABS_CP2102, 0, 1)))).isFalse()
    }

    @Test
    fun shown_whenACp2105PairIsListed() {
        val ports = listOf(
            port(UsbId.SILABS_CP2102, 0, 1),
            port(UsbId.SILABS_CP2105, 0, 2),
            port(UsbId.SILABS_CP2105, 1, 2),
        )
        assertThat(shouldShowDualPortHint(ports)).isTrue()
    }
}
