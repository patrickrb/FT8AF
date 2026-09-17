package com.k1af.ft8af.connector;

import com.k1af.ft8af.serialport.UsbId;

import java.util.Locale;

/**
 * Human-readable names for the rows of the "Select Serial Port" picker (and the
 * same list on the USB Diagnostics page and the web log page).
 *
 * <p>Before this the picker printed {@code \0x03E9\0x10C4\0xEA70\0x1} — deviceId,
 * vendorId, productId and port index in raw hex — so a user with a dual-UART
 * chip (Silicon Labs CP2105: FT-891, FT-991A, FTDX10, FTDX101, FT-710, Kenwood
 * TS-890S/TS-990S, Icom IC-9700/IC-7610, …) had no way to tell which of the two
 * identical-looking rows was the CAT port (issue #817).
 *
 * <p>Everything here is a pure function of numbers the driver already knows, so
 * it is unit-tested without a USB stack. Two deliberate limits:
 * <ul>
 *   <li>The chip name comes from the <em>driver class</em> that claimed the
 *       device plus the product id; there is no lookup of rig models.</li>
 *   <li>The port role is only ever the chip's own name for the interface
 *       ("Enhanced" / "Standard" on a CP2105 — the names Windows Device Manager
 *       and every rig's setup guide use). It is NOT "CAT" / "PTT": which of the
 *       two carries CAT is vendor-specific on the very same VID:PID (Yaesu puts
 *       CAT on Enhanced, Kenwood's TS-890S manual says use Standard), so naming
 *       a role here would be wrong for someone. The picker shows that as a hint
 *       instead.</li>
 * </ul>
 */
public final class SerialPortLabel {

    private SerialPortLabel() {}

    /** Driver family keys — the simple class name of the {@code UsbSerialDriver} that claimed the device. */
    public static final String DRIVER_CP21XX = "Cp21xxSerialDriver";
    public static final String DRIVER_FTDI = "FtdiSerialDriver";
    public static final String DRIVER_PROLIFIC = "ProlificSerialDriver";
    public static final String DRIVER_CH34X = "Ch34xSerialDriver";
    public static final String DRIVER_CDC_ACM = "CdcAcmSerialDriver";

    /** CP2105 interface names, as Silicon Labs (and Windows Device Manager) call them. */
    public static final String ROLE_ENHANCED = "Enhanced";
    public static final String ROLE_STANDARD = "Standard";

    static final String SEPARATOR = " · ";

    /**
     * Chip / bridge name for a device, e.g. {@code "Silicon Labs CP2105"},
     * {@code "FTDI FT2232H"}, {@code "USB CDC serial"}. Never returns hex.
     *
     * @param driverFamily simple class name of the driver that claimed the device
     *                     (one of the {@code DRIVER_*} constants), or null/unknown
     */
    public static String chipName(int vendorId, int productId, String driverFamily) {
        if (driverFamily == null) return "USB serial";
        switch (driverFamily) {
            case DRIVER_CP21XX:
                if (vendorId == UsbId.VENDOR_SILABS) {
                    switch (productId) {
                        case UsbId.SILABS_CP2102: return "Silicon Labs CP2102";
                        case UsbId.SILABS_CP2105: return "Silicon Labs CP2105";
                        case UsbId.SILABS_CP2108: return "Silicon Labs CP2108";
                        default: break;
                    }
                }
                return "Silicon Labs CP210x";
            case DRIVER_FTDI:
                switch (productId) {
                    case UsbId.FTDI_FT232R: return "FTDI FT232R";
                    case UsbId.FTDI_FT2232H: return "FTDI FT2232H";
                    case UsbId.FTDI_FT4232H: return "FTDI FT4232H";
                    case UsbId.FTDI_FT232H: return "FTDI FT232H";
                    case UsbId.FTDI_FT231X: return "FTDI FT23xX";
                    default: return "FTDI";
                }
            case DRIVER_PROLIFIC:
                return "Prolific PL2303";
            case DRIVER_CH34X:
                return "WCH CH34x";
            case DRIVER_CDC_ACM:
                return "USB CDC serial";
            default:
                return "USB serial";
        }
    }

    /**
     * The chip's own name for interface {@code portNum}, or null when the chip has
     * no named interfaces. Only the two-port CP2105 has them: interface 0 is the
     * Enhanced Communication Interface, interface 1 the Standard one (the SCI,
     * which the driver marks {@code mIsRestrictedPort}). Fixed by the silicon,
     * not by the rig — so this is safe to state for every CP2105.
     */
    public static String portRole(int vendorId, int productId, int portNum, int portCount) {
        if (vendorId == UsbId.VENDOR_SILABS && productId == UsbId.SILABS_CP2105 && portCount == 2) {
            if (portNum == 0) return ROLE_ENHANCED;
            if (portNum == 1) return ROLE_STANDARD;
        }
        return null;
    }

    /**
     * "Port 1 of 2 · Enhanced" — the port half of a row. {@code portOfTemplate} is
     * the localized {@code "Port %1$d of %2$d"} (1-based for humans; the picker
     * index stays 0-based internally).
     */
    public static String portName(int vendorId, int productId, int portNum, int portCount,
                                  String portOfTemplate) {
        String base = String.format(Locale.US, portOfTemplate, portNum + 1, Math.max(portCount, portNum + 1));
        String role = portRole(vendorId, productId, portNum, portCount);
        return role == null ? base : base + SEPARATOR + role;
    }

    /**
     * Full picker row: {@code "Silicon Labs CP2105 · Port 1 of 2 · Enhanced"}.
     * A single-port chip reads {@code "Silicon Labs CP2102 · Port 1 of 1"}; an
     * unknown chip {@code "USB serial · Port 1 of 1"}. Never raw hex.
     */
    public static String describe(int vendorId, int productId, String driverFamily,
                                  int portNum, int portCount, String portOfTemplate) {
        return chipName(vendorId, productId, driverFamily) + SEPARATOR
                + portName(vendorId, productId, portNum, portCount, portOfTemplate);
    }

    /**
     * Whether a port list contains a chip with named interfaces, i.e. whether the
     * picker should show the "which port is CAT depends on the rig" hint.
     */
    public static boolean anyPortHasRole(Iterable<CableSerialPort.SerialPort> ports) {
        if (ports == null) return false;
        for (CableSerialPort.SerialPort p : ports) {
            if (p != null && portRole(p.vendorId, p.productId, p.portNum, p.portCount) != null) {
                return true;
            }
        }
        return false;
    }
}
