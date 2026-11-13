package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.rigs.InstructionSet;
import com.k1af.ft8af.serialport.UsbId;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM coverage for the device-selection and listing rules in
 * {@link CableSerialPort} that issue #817 tightened:
 * <ul>
 *   <li>{@link CableSerialPort#matchDevice}: {@code prepare()} used to keep the
 *       <em>last</em> device whose vendor id matched, so an FT-891 (CP2105) plus
 *       a Digirig (CP2102, same Silicon Labs vendor id) opened whichever the
 *       device map happened to iterate last.</li>
 *   <li>{@link CableSerialPort#hasCdcControlInterface}: the picker's CDC fallback
 *       must not list every unknown device (the rig's audio codec) as a port.</li>
 *   <li>{@link CableSerialPort#isFt710WriteOnlyCatMode}: the diagnostics page's
 *       "CAT never answers here by design" gate.</li>
 * </ul>
 */
public class CableSerialPortDeviceMatchTest {

    private static final int FT891_DEV = 1003;
    private static final int DIGIRIG_DEV = 1004;
    private static final CableSerialPort.UsbIdentity FT891 =
            new CableSerialPort.UsbIdentity(FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105);
    private static final CableSerialPort.UsbIdentity DIGIRIG =
            new CableSerialPort.UsbIdentity(DIGIRIG_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102);
    private static final CableSerialPort.UsbIdentity ICOM =
            new CableSerialPort.UsbIdentity(1005, 0x0C26, 0x0018);

    // --- matchDevice ---

    @Test
    public void match_prefersTheEnumeratedDevice() {
        List<CableSerialPort.UsbIdentity> bus = Arrays.asList(DIGIRIG, FT891, ICOM);
        assertThat(CableSerialPort.matchDevice(bus, FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105))
                .isSameInstanceAs(FT891);
    }

    @Test
    public void match_sameVendorDifferentProductIsNotShadowed() {
        // The #817 scenario: two Silicon Labs chips. Whichever order the bus map
        // yields, the pick must land on the product the user chose.
        List<CableSerialPort.UsbIdentity> lastWins = Arrays.asList(FT891, DIGIRIG);
        List<CableSerialPort.UsbIdentity> firstWins = Arrays.asList(DIGIRIG, FT891);
        for (List<CableSerialPort.UsbIdentity> bus : Arrays.asList(lastWins, firstWins)) {
            assertThat(CableSerialPort.matchDevice(bus, FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105))
                    .isSameInstanceAs(FT891);
            assertThat(CableSerialPort.matchDevice(bus, DIGIRIG_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102))
                    .isSameInstanceAs(DIGIRIG);
        }
    }

    @Test
    public void match_fallsBackToVendorAndProductAfterReplug() {
        // A re-plug hands out a new deviceId; the vendor/product pair still
        // identifies the chip the pick was made for.
        CableSerialPort.UsbIdentity replugged =
                new CableSerialPort.UsbIdentity(2001, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105);
        List<CableSerialPort.UsbIdentity> bus = Arrays.asList(DIGIRIG, replugged);
        assertThat(CableSerialPort.matchDevice(bus, FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105))
                .isSameInstanceAs(replugged);
    }

    @Test
    public void match_recycledDeviceIdOnAnotherProductDoesNotMatch() {
        // The kernel reuses deviceIds; an id alone is not proof of identity.
        CableSerialPort.UsbIdentity impostor =
                new CableSerialPort.UsbIdentity(FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2102);
        assertThat(CableSerialPort.matchDevice(Collections.singletonList(impostor),
                FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105)).isNull();
    }

    @Test
    public void match_unknownProductIdUsesVendorOnly() {
        // Legacy caller (productId 0): the old vendor-only rule, but deterministic.
        List<CableSerialPort.UsbIdentity> bus = Arrays.asList(ICOM, DIGIRIG, FT891);
        assertThat(CableSerialPort.matchDevice(bus, 0, UsbId.VENDOR_SILABS, 0)).isSameInstanceAs(DIGIRIG);
        assertThat(CableSerialPort.matchDevice(bus, 0, 0x0C26, 0)).isSameInstanceAs(ICOM);
    }

    @Test
    public void match_nothingOnTheBus() {
        assertThat(CableSerialPort.matchDevice(Collections.emptyList(),
                FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105)).isNull();
        assertThat(CableSerialPort.matchDevice(null,
                FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105)).isNull();
        assertThat(CableSerialPort.matchDevice(Collections.singletonList(ICOM),
                FT891_DEV, UsbId.VENDOR_SILABS, UsbId.SILABS_CP2105)).isNull();
    }

    // --- hasCdcControlInterface ---

    private static final int USB_CLASS_COMM = 2;
    private static final int USB_CLASS_CDC_DATA = 10;
    private static final int USB_CLASS_AUDIO = 1;
    private static final int USB_CLASS_HID = 3;
    private static final int USB_CLASS_VENDOR = 0xFF;

    @Test
    public void cdc_listedOnlyWithACommunicationsInterface() {
        assertThat(CableSerialPort.hasCdcControlInterface(new int[]{USB_CLASS_COMM, USB_CLASS_CDC_DATA})).isTrue();
        assertThat(CableSerialPort.hasCdcControlInterface(new int[]{USB_CLASS_VENDOR, USB_CLASS_COMM})).isTrue();
    }

    @Test
    public void cdc_audioCodecOrHidIsNotAPort() {
        // The rig's own USB sound card (or a CM108 with a HID PTT) must not show
        // up in the picker just because no serial prober claimed it.
        assertThat(CableSerialPort.hasCdcControlInterface(new int[]{USB_CLASS_AUDIO, USB_CLASS_AUDIO})).isFalse();
        assertThat(CableSerialPort.hasCdcControlInterface(new int[]{USB_CLASS_AUDIO, USB_CLASS_HID})).isFalse();
        assertThat(CableSerialPort.hasCdcControlInterface(new int[]{USB_CLASS_VENDOR})).isFalse();
        assertThat(CableSerialPort.hasCdcControlInterface(new int[0])).isFalse();
        assertThat(CableSerialPort.hasCdcControlInterface(null)).isFalse();
    }

    // --- isFt710WriteOnlyCatMode ---

    @Test
    public void ft710_writeOnlyOnlyForCableCat() {
        assertThat(CableSerialPort.isFt710WriteOnlyCatMode(
                InstructionSet.YAESU_FT710, ConnectMode.USB_CABLE, ControlMode.CAT)).isTrue();
        // Another rig on the same cable/CAT combination is read normally.
        assertThat(CableSerialPort.isFt710WriteOnlyCatMode(
                InstructionSet.YAESU_DX10, ConnectMode.USB_CABLE, ControlMode.CAT)).isFalse();
        // FT-710 over Bluetooth or with RTS/DTR keying is not the write-only path.
        assertThat(CableSerialPort.isFt710WriteOnlyCatMode(
                InstructionSet.YAESU_FT710, ConnectMode.BLUE_TOOTH, ControlMode.CAT)).isFalse();
        assertThat(CableSerialPort.isFt710WriteOnlyCatMode(
                InstructionSet.YAESU_FT710, ConnectMode.USB_CABLE, ControlMode.RTS)).isFalse();
    }
}
