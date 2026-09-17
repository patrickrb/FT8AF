package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.serialport.UsbId;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Pure-JVM coverage for {@link SerialPortLabel}: the human-readable rows of the
 * "Select Serial Port" picker (issue #817). Pins that a CP2105's two rows are
 * distinguishable, that nothing ever prints raw hex, and that the label states
 * the chip's interface name only — never a "CAT"/"PTT" role, which differs by
 * rig vendor on the same VID:PID.
 */
public class SerialPortLabelTest {

    private static final String PORT_OF = "Port %1$d of %2$d";

    // --- chipName ---

    @Test
    public void chipName_silabsProductIdsAreNamed() {
        assertThat(SerialPortLabel.chipName(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102, SerialPortLabel.DRIVER_CP21XX))
                .isEqualTo("Silicon Labs CP2102");
        assertThat(SerialPortLabel.chipName(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, SerialPortLabel.DRIVER_CP21XX))
                .isEqualTo("Silicon Labs CP2105");
        assertThat(SerialPortLabel.chipName(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2108, SerialPortLabel.DRIVER_CP21XX))
                .isEqualTo("Silicon Labs CP2108");
    }

    @Test
    public void chipName_cp210xWithCustomVendorIdFallsBackToFamily() {
        // Icom rigs re-badge the CP210x under their own vendor id.
        assertThat(SerialPortLabel.chipName(0x0C26, 0x0018, SerialPortLabel.DRIVER_CP21XX))
                .isEqualTo("Silicon Labs CP210x");
    }

    @Test
    public void chipName_ftdiProductsAreNamed() {
        assertThat(SerialPortLabel.chipName(UsbId.VENDOR_FTDI, UsbId.FTDI_FT232R, SerialPortLabel.DRIVER_FTDI))
                .isEqualTo("FTDI FT232R");
        assertThat(SerialPortLabel.chipName(UsbId.VENDOR_FTDI, UsbId.FTDI_FT2232H, SerialPortLabel.DRIVER_FTDI))
                .isEqualTo("FTDI FT2232H");
        assertThat(SerialPortLabel.chipName(UsbId.VENDOR_FTDI, 0x1234, SerialPortLabel.DRIVER_FTDI))
                .isEqualTo("FTDI");
    }

    @Test
    public void chipName_otherFamilies() {
        assertThat(SerialPortLabel.chipName(UsbId.VENDOR_PROLIFIC, UsbId.PROLIFIC_PL2303, SerialPortLabel.DRIVER_PROLIFIC))
                .isEqualTo("Prolific PL2303");
        assertThat(SerialPortLabel.chipName(0x1A86, 0x7523, SerialPortLabel.DRIVER_CH34X))
                .isEqualTo("WCH CH34x");
        assertThat(SerialPortLabel.chipName(0x0483, 0xF001, SerialPortLabel.DRIVER_CDC_ACM))
                .isEqualTo("USB CDC serial");
    }

    @Test
    public void chipName_unknownOrNullDriverIsGeneric() {
        assertThat(SerialPortLabel.chipName(0x1111, 0x2222, null)).isEqualTo("USB serial");
        assertThat(SerialPortLabel.chipName(0x1111, 0x2222, "SomethingElseDriver")).isEqualTo("USB serial");
    }

    // --- portRole ---

    @Test
    public void portRole_cp2105NamesBothInterfaces() {
        assertThat(SerialPortLabel.portRole(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, 0, 2))
                .isEqualTo(SerialPortLabel.ROLE_ENHANCED);
        assertThat(SerialPortLabel.portRole(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, 1, 2))
                .isEqualTo(SerialPortLabel.ROLE_STANDARD);
    }

    @Test
    public void portRole_isNeverCatOrPtt() {
        // The whole point of #817's correction: Yaesu wants CAT on Enhanced, the
        // Kenwood TS-890S manual says Standard. The label must not pick a side.
        for (int port = 0; port < 2; port++) {
            String role = SerialPortLabel.portRole(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, port, 2);
            assertThat(role).doesNotContainMatch("(?i)cat|ptt");
        }
    }

    @Test
    public void portRole_nullForSinglePortAndOtherChips() {
        assertThat(SerialPortLabel.portRole(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102, 0, 1)).isNull();
        assertThat(SerialPortLabel.portRole(UsbId.VENDOR_FTDI, UsbId.FTDI_FT2232H, 0, 2)).isNull();
        assertThat(SerialPortLabel.portRole(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2108, 0, 4)).isNull();
        // A CP2105 that (somehow) enumerated one port has no pair to name.
        assertThat(SerialPortLabel.portRole(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, 0, 1)).isNull();
    }

    // --- portName / describe ---

    @Test
    public void portName_isOneBasedWithRole() {
        assertThat(SerialPortLabel.portName(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, 0, 2, PORT_OF))
                .isEqualTo("Port 1 of 2 · Enhanced");
        assertThat(SerialPortLabel.portName(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, 1, 2, PORT_OF))
                .isEqualTo("Port 2 of 2 · Standard");
        assertThat(SerialPortLabel.portName(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102, 0, 1, PORT_OF))
                .isEqualTo("Port 1 of 1");
    }

    @Test
    public void portName_countNeverBelowIndex() {
        // A legacy SerialPort built with the 4-arg constructor has portCount == 1
        // even when its index says otherwise; "Port 2 of 1" would be nonsense.
        assertThat(SerialPortLabel.portName(0x1111, 0x2222, 1, 1, PORT_OF)).isEqualTo("Port 2 of 2");
    }

    @Test
    public void describe_cp2105RowsAreDistinct() {
        String first = SerialPortLabel.describe(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105,
                SerialPortLabel.DRIVER_CP21XX, 0, 2, PORT_OF);
        String second = SerialPortLabel.describe(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105,
                SerialPortLabel.DRIVER_CP21XX, 1, 2, PORT_OF);
        assertThat(first).isEqualTo("Silicon Labs CP2105 · Port 1 of 2 · Enhanced");
        assertThat(second).isEqualTo("Silicon Labs CP2105 · Port 2 of 2 · Standard");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    public void describe_neverPrintsHex() {
        String row = SerialPortLabel.describe(0x03E9, 0xEA70, null, 0, 1, PORT_OF);
        assertThat(row).doesNotContain("0x");
        assertThat(row).doesNotContain("\\");
        assertThat(row).isEqualTo("USB serial · Port 1 of 1");
    }

    @Test
    public void describe_usesTheCallersTemplate() {
        assertThat(SerialPortLabel.describe(UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102,
                SerialPortLabel.DRIVER_CP21XX, 0, 1, "Anschluss %1$d von %2$d"))
                .isEqualTo("Silicon Labs CP2102 · Anschluss 1 von 1");
    }

    // --- SerialPort.label (the picker entry point) ---

    @Test
    public void serialPortLabel_usesPortCountAndFamily() {
        CableSerialPort.SerialPort p = new CableSerialPort.SerialPort(
                1001, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, 1, 2, SerialPortLabel.DRIVER_CP21XX);
        assertThat(p.label(PORT_OF)).isEqualTo("Silicon Labs CP2105 · Port 2 of 2 · Standard");
    }

    @Test
    public void serialPortLabel_fallsBackWhenTemplateMissing() {
        // GeneralVariables.getStringFromResource() yields "" before the main
        // context exists; the row must still read sensibly.
        CableSerialPort.SerialPort p = new CableSerialPort.SerialPort(
                1001, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102, 0, 1, SerialPortLabel.DRIVER_CP21XX);
        assertThat(p.label(null)).isEqualTo("Silicon Labs CP2102 · Port 1 of 1");
        assertThat(p.label("")).isEqualTo("Silicon Labs CP2102 · Port 1 of 1");
    }

    @Test
    public void legacyConstructor_defaultsToSingleUnknownPort() {
        CableSerialPort.SerialPort p = new CableSerialPort.SerialPort(1001, 0x0C26, 0x0018, 0);
        assertThat(p.portCount).isEqualTo(1);
        assertThat(p.driverFamily).isNull();
        assertThat(p.label(PORT_OF)).isEqualTo("USB serial · Port 1 of 1");
    }

    // --- anyPortHasRole ---

    @Test
    public void anyPortHasRole_trueOnlyWithAnEnhancedStandardPair() {
        CableSerialPort.SerialPort single = new CableSerialPort.SerialPort(
                1, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102, 0, 1, SerialPortLabel.DRIVER_CP21XX);
        CableSerialPort.SerialPort dualA = new CableSerialPort.SerialPort(
                2, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, 0, 2, SerialPortLabel.DRIVER_CP21XX);
        CableSerialPort.SerialPort dualB = new CableSerialPort.SerialPort(
                2, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105, 1, 2, SerialPortLabel.DRIVER_CP21XX);

        assertThat(SerialPortLabel.anyPortHasRole(null)).isFalse();
        assertThat(SerialPortLabel.anyPortHasRole(Collections.emptyList())).isFalse();
        assertThat(SerialPortLabel.anyPortHasRole(Collections.singletonList(single))).isFalse();
        assertThat(SerialPortLabel.anyPortHasRole(Arrays.asList(single, dualA, dualB))).isTrue();
    }
}
