package com.k1af.ft8af.connector;
/**
 * Class for USB serial port operations. USB serial drivers are in the serialport directory,
 * mainly CDC, CH34x, CP21xx, FTDI, etc.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.k1af.ft8af.BuildConfig;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.rigs.InstructionSet;
import com.k1af.ft8af.serialport.CdcAcmSerialDriver;
import com.k1af.ft8af.serialport.UsbSerialDriver;
import com.k1af.ft8af.serialport.UsbSerialPort;
import com.k1af.ft8af.serialport.UsbSerialProber;
import com.k1af.ft8af.serialport.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import radio.ks3ckc.ft8af.UsbPermissionIntentsKt;


public class CableSerialPort {
    private static final String TAG = "CableSerialPort";
    private OnConnectorStateChanged onStateChanged;
    public static final int SEND_TIMEOUT = 2000;

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID;

    public enum UsbPermission {Unknown, Requested, Granted, Denied}

    private UsbPermission usbPermission = UsbPermission.Unknown;

    private BroadcastReceiver broadcastReceiver;
    private final Context context;

    private int vendorId = 0x0c26;//Device ID
    // Product id and the enumeration-time deviceId of the picked port. Both were
    // dropped before and prepare() matched on vendorId alone, so with two Silicon
    // Labs devices on the bus (an FT-891's CP2105 plus a Digirig's CP2102, say) it
    // opened whichever HashMap iteration happened to visit last (issue #817).
    // productId == 0 means "unknown" (legacy caller) and falls back to vendor-only.
    private int productId = 0;
    private int deviceId = 0;
    private int portNum = 0;//Port number
    private int baudRate = 19200;//Baud rate

    // volatile: written on the connect thread (open) and cleared to null on the
    // disconnect thread (disconnect()), read on the CAT/TX threads (sendData,
    // setRTS/DTR). Readers MUST snapshot it into a local before check-and-use —
    // reading the field twice let a concurrent disconnect null it between the
    // null-check and usbSerialPort.write(), NPE-ing a delayed CAT freq write on
    // the FT-891 (Yaesu39Rig.setFreqToRig) as the rig dropped.
    private volatile UsbSerialPort usbSerialPort;
    private SerialInputOutputManager usbIoManager;
    public SerialInputOutputManager.Listener ioListener = null;

    private UsbManager usbManager;
    private UsbDeviceConnection usbConnection;
    private UsbSerialDriver driver;


    private boolean connected = false;//Whether currently connected

    public CableSerialPort(Context mContext, SerialPort serialPort, int baud, OnConnectorStateChanged connectorStateChanged) {
        vendorId = serialPort.vendorId;
        productId = serialPort.productId;
        deviceId = serialPort.deviceId;
        portNum = serialPort.portNum;
        baudRate = baud;
        context = mContext;
        this.onStateChanged=connectorStateChanged;
        doBroadcast();
    }

    public CableSerialPort(Context mContext) {
        context = mContext;
        doBroadcast();
    }

    private void doBroadcast() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
    }

    // FT-710 CAT cable mode: the rig stops producing RF when the host runs a
    // serial read loop against its CAT port. We still need to send CAT writes
    // (frequency, PTT), so we open the port but skip starting the read manager.
    // Tracked in upstream FT8CN PR #168.
    private boolean shouldUseFt710WriteOnlyCatMode() {
        return isFt710WriteOnlyCatMode(GeneralVariables.instructionSet,
                GeneralVariables.connectMode, GeneralVariables.controlMode);
    }

    /**
     * The FT-710 cable-CAT combination above, as a pure function so the USB
     * Diagnostics page can tell "the rig never answers" (a fault) from "we never
     * listen" (this mode, by design) — its CAT Response row stays red forever
     * here and used to look like a broken link.
     */
    public static boolean isFt710WriteOnlyCatMode(int instructionSet, int connectMode, int controlMode) {
        return instructionSet == InstructionSet.YAESU_FT710
                && connectMode == ConnectMode.USB_CABLE
                && controlMode == ControlMode.CAT;
    }

    /**
     * A USB device reduced to the three ids {@link #matchDevice} needs. Plain
     * value class so the matching rule is testable without {@code UsbDevice}
     * (final, framework-only).
     */
    static final class UsbIdentity {
        final int deviceId;
        final int vendorId;
        final int productId;

        UsbIdentity(int deviceId, int vendorId, int productId) {
            this.deviceId = deviceId;
            this.vendorId = vendorId;
            this.productId = productId;
        }
    }

    /**
     * Which attached device the picked port belongs to. In order:
     * <ol>
     *   <li>the very device that was enumerated for the pick ({@code deviceId}
     *       matches, and the vendor/product still agree — deviceIds are recycled
     *       by the kernel, so an id alone is not proof);</li>
     *   <li>otherwise the first device with the same vendor <em>and</em> product
     *       id (the pick was made before a re-plug, which hands out a new
     *       deviceId);</li>
     *   <li>otherwise, only when the product id is unknown ({@code 0}, a legacy
     *       caller), the first device with the same vendor id.</li>
     * </ol>
     * Returns null when nothing matches. "First" is the list order the caller
     * passes; the point is that a second, different chip from the same vendor can
     * no longer shadow the one the user picked.
     */
    static UsbIdentity matchDevice(List<UsbIdentity> present, int deviceId, int vendorId, int productId) {
        if (present == null) return null;
        if (productId != 0) {
            for (UsbIdentity d : present) {
                if (d.deviceId == deviceId && d.vendorId == vendorId && d.productId == productId) {
                    return d;
                }
            }
            for (UsbIdentity d : present) {
                if (d.vendorId == vendorId && d.productId == productId) {
                    return d;
                }
            }
            return null;
        }
        for (UsbIdentity d : present) {
            if (d.vendorId == vendorId) {
                return d;
            }
        }
        return null;
    }

    /** {@link #matchDevice} over the live bus; the matched {@link UsbDevice} or null. */
    private UsbDevice findPickedDevice(UsbManager manager) {
        Collection<UsbDevice> devices = manager.getDeviceList().values();
        List<UsbIdentity> ids = new ArrayList<>(devices.size());
        List<UsbDevice> byIndex = new ArrayList<>(devices.size());
        for (UsbDevice v : devices) {
            ids.add(new UsbIdentity(v.getDeviceId(), v.getVendorId(), v.getProductId()));
            byIndex.add(v);
        }
        UsbIdentity hit = matchDevice(ids, deviceId, vendorId, productId);
        return hit == null ? null : byIndex.get(ids.indexOf(hit));
    }

    /**
     * Whether a USB device's interface list makes it a CDC-ACM serial candidate:
     * it carries at least one Communications-class control interface. This is
     * the gate {@link #listSerialPorts} (and the USB Diagnostics "CDC Serial Found"
     * check) uses before offering an unrecognised
     * device through the CDC fallback driver. Without the gate every unknown
     * device on the bus — the rig's own USB audio codec, a hub — would be listed
     * as a serial port, because {@code CdcAcmSerialDriver} synthesises a port
     * even when it finds no CDC interfaces at all.
     */
    public static boolean hasCdcControlInterface(int[] interfaceClasses) {
        if (interfaceClasses == null) return false;
        for (int cls : interfaceClasses) {
            if (cls == UsbConstants.USB_CLASS_COMM) return true;
        }
        return false;
    }

    public static int[] interfaceClassesOf(UsbDevice device) {
        int[] classes = new int[device.getInterfaceCount()];
        for (int i = 0; i < classes.length; i++) {
            classes[i] = device.getInterface(i).getInterfaceClass();
        }
        return classes;
    }

    /**
     * Whether {@code portNum} is a valid 0-based index into a driver port list
     * of {@code portCount} ports, i.e. in {@code 0 .. portCount-1}.
     *
     * <p>{@link #prepare()} calls {@code driver.getPorts().get(portNum)} right
     * after this check. The previous guard was {@code portCount < portNum},
     * which is off by one: it let {@code portNum == portCount} (a persisted
     * multi-port selection replayed against a device that now enumerates fewer
     * ports, or an empty port list with the default {@code portNum == 0}) pass,
     * so {@code get(portNum)} threw {@link IndexOutOfBoundsException} out of
     * {@code prepare()} → {@code connect()}. On the CAT auto-reconnect worker
     * that exception is uncaught and crashes the app. Requiring
     * {@code portNum < portCount} (and non-negative) makes the guard actually
     * cover the index it protects.
     *
     * <p>Package-visible for testing.
     */
    static boolean isValidPortIndex(int portCount, int portNum) {
        return portNum >= 0 && portNum < portCount;
    }

    /**
     * Whether a USB device with this port's vendor id is currently on the bus (the same
     * match {@link #prepare()} uses to find the device). The auto-reconnect loop consults
     * this to stop retrying a device that has been unplugged — see
     * {@link CatReconnectPolicy#shouldKeepRetrying(boolean, boolean)}.
     * Package-private on purpose: an internal reconnect helper, not part of the
     * port's supported surface.
     */
    boolean isDevicePresent() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return false;
        return findPickedDevice(manager) != null;
    }

    private boolean prepare() {
        registerRigSerialPort(context);
        UsbDevice device = null;
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        //Set connection to null here so we can check permissions by null status later.
        usbConnection = null;
        //Should we do a permission check here?
        if (usbManager == null) {
            return false;
        }


        device = findPickedDevice(usbManager);
        if (device == null) {
            Log.e(TAG, String.format("Failed to open serial device: device %04x:%04x not found",
                    vendorId, productId));
            return false;
        }
        fileLog(String.format("serial.prepare: matched %04x:%04x deviceId=%d port=%d",
                device.getVendorId(), device.getProductId(), device.getDeviceId(), portNum));
        driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            //Try adding the unknown device to the CDC driver
            driver = new CdcAcmSerialDriver(device);
        }
        if (!isValidPortIndex(driver.getPorts().size(), portNum)) {
            Log.e(TAG, "Serial port number does not exist, cannot open.");
            return false;
        }
        Log.d(TAG, "connect: port size:" + String.valueOf(driver.getPorts().size()));
        usbSerialPort = driver.getPorts().get(portNum);
        usbConnection = usbManager.openDevice(driver.getDevice());

        return true;

    }

    @SuppressLint("UnspecifiedImmutableFlag")
    public boolean connect() {
        connected = false;
        if (!prepare()) {
            //return false;
        }
        if (driver == null) {
            if (onStateChanged!=null){
                onStateChanged.onRunError(GeneralVariables.getStringFromResource(R.string.serial_no_driver));
            }
            return false;
        }
        if (usbConnection == null && usbPermission == UsbPermission.Unknown
                && !usbManager.hasPermission(driver.getDevice())) {
            // The Unknown guard above is per-instance and every auto-connect builds a
            // fresh instance, so on a flapping link it alone raised a system dialog per
            // bounce. The process-wide throttle is what actually spaces the dialogs out.
            if (UsbPermissionThrottle.shouldRequestNow(vendorId, System.currentTimeMillis())) {
                usbPermission = UsbPermission.Requested;
                UsbPermissionThrottle.markRequested(vendorId, System.currentTimeMillis());

                PendingIntent usbPermissionIntent =
                        UsbPermissionIntentsKt.createUsbPermissionIntent(context, INTENT_ACTION_GRANT_USB);


                usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
                prepare();
            } else {
                fileLog(String.format("usbPermission: request for vendor 0x%04x throttled"
                        + " (asked <%ds ago)", vendorId,
                        UsbPermissionThrottle.REQUEST_COOLDOWN_MS / 1000));
            }
        }
        if (usbConnection == null) {
            if (onStateChanged!=null){
                onStateChanged.onRunError(GeneralVariables.getStringFromResource(R.string.serial_connect_no_access));
            }

            return false;
        }
        try {
            usbSerialPort.open(usbConnection);
            //Baud rate, stop bits
            //usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            Log.d(TAG,String.format("serial:baud rate：%d,data bits:%d,stop bits:%d,parity bit:%d"
                    ,baudRate,GeneralVariables.serialDataBits
                    ,GeneralVariables.serialStopBits
                    ,GeneralVariables.serialParity));
            usbSerialPort.setParameters(baudRate, GeneralVariables.serialDataBits
                    , GeneralVariables.serialStopBits, GeneralVariables.serialParity);
            usbIoManager = new SerialInputOutputManager(usbSerialPort, new SerialInputOutputManager.Listener() {
                @Override
                public void onNewData(byte[] data) {
                    if (ioListener != null) {
                        ioListener.onNewData(data);
                    }
                }

                @Override
                public void onRunError(Exception e) {
                    if (ioListener != null) {
                        ioListener.onRunError(e);
                    }
                    disconnect();
                }
            });
            if (!shouldUseFt710WriteOnlyCatMode()) {
                usbIoManager.start();
            } else {
                Log.d(TAG, "FT-710 CAT write-only mode: skipping usbIoManager.start()");
            }
            Log.d(TAG, "Serial port opened successfully!");
            connected = true;

            if (onStateChanged!=null){
                onStateChanged.onConnected();
            }


        } catch (Exception e) {
            Log.e(TAG, "Failed to open serial port: " + e.getMessage());
            if (onStateChanged!=null){
                onStateChanged.onRunError(GeneralVariables.getStringFromResource(R.string.serial_connect_failed)
                        + e.getMessage());
            }
            disconnect();
            return false;
        }
        return true;
    }

    private void fileLog(String msg) {
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx == null) return;
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date());
            new java.io.FileWriter(new java.io.File(dir, "debug.log"), true)
                    .append(ts + " " + msg + "\n").close();
        } catch (Exception ignored) {}
        Log.d(TAG, msg);
    }

    /**
     * A single raw port write, extracted so the disconnect-race guard is
     * unit-testable without the full {@link UsbSerialPort} surface (or a live
     * device).
     */
    @FunctionalInterface
    interface RawWrite {
        void write(byte[] src, int timeout) throws IOException;
    }

    /**
     * Write {@code src} to a port reference the caller has already snapshotted.
     * A {@code null} writer means the port was disconnected (closed and nulled
     * by {@link #disconnect()}): no write, returns {@code false} — never NPEs.
     * The caller passes the snapshot's {@code ::write}, so a concurrent
     * disconnect that nulls the field afterwards cannot affect this call.
     *
     * @return true if the write was issued, false if the port was not open
     */
    static boolean writeIfOpen(RawWrite writer, byte[] src, int timeout) throws IOException {
        if (writer == null) return false;
        writer.write(src, timeout);
        return true;
    }

    public boolean sendData(final byte[] src) {
        // Snapshot the volatile field ONCE; disconnect() may null it on another
        // thread at any moment. Using the local for both the check and the write
        // removes the check-then-act NPE (see the field's comment).
        final UsbSerialPort port = usbSerialPort;
        if (port == null) {
            fileLog("serial.send: port not open!");
            return false;
        }
        try {
            String preview = new String(src).replace("\r", "\\r").replace("\n", "\\n");
            // Only log non-periodic commands (skip FA; reads to avoid log spam)
            if (!preview.equals("FA;") && !preview.startsWith("RM")) {
                StringBuilder hex = new StringBuilder();
                for (byte b : src) hex.append(String.format("%02X ", b));
                fileLog("serial.send[" + src.length + "]: " + preview + " | hex: " + hex.toString().trim());
            }
            writeIfOpen(port::write, src, SEND_TIMEOUT);
            if (!preview.equals("FA;") && !preview.startsWith("RM")) {
                fileLog("serial.send: write completed OK");
            }
        } catch (IOException e) {
            e.printStackTrace();
            fileLog("serial.send ERROR: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // Backstop. A CAT write must never be fatal: sendData runs on the TX
            // pool thread via the PTT path, so an uncaught RuntimeException takes
            // the whole process down — and if it happens between TX1 and TX0 the
            // rig is left keyed with nothing alive to unkey it (observed in the
            // field: process died 3ms after TX1, transmitter keyed for 89s).
            // The known case is an NPE from a yanked USB endpoint, now also
            // guarded at source in CommonUsbSerialPort; this catch covers the
            // rest of the driver stack, which is third-party and NPEs freely
            // once the device disappears mid-transfer.
            e.printStackTrace();
            fileLog("serial.send ERROR (runtime, port died mid-write): " + e);
            return false;
        }
        return true;
    }

    public void disconnect() {
        connected = false;
        if (onStateChanged!=null){
            onStateChanged.onDisconnected();
        }
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            if (usbSerialPort != null) {
                usbSerialPort.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Error closing serial port: " + e.getMessage());
        }
        usbSerialPort = null;
        try {
            context.unregisterReceiver(broadcastReceiver);
        } catch (IllegalArgumentException e) {
            // Already unregistered
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void registerRigSerialPort(Context context) {
        Log.d(TAG, "registerRigSerialPort: registered!");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));
        }
    }

    public void unregisterRigSerialPort(Activity activity) {
        Log.d(TAG, "unregisterRigSerialPort: unregistered!");
        activity.unregisterReceiver(broadcastReceiver);
    }


    /**
     * Whether a control-line toggle can actually be driven: the port must report
     * the requested line as supported. Pure so the "unsupported line reports
     * failure" decision (used by {@link #setRTS_On}/{@link #setDTR_On}) is
     * unit-testable without a live USB device.
     */
    static boolean controlLineSupported(EnumSet<UsbSerialPort.ControlLine> supported,
                                        UsbSerialPort.ControlLine line) {
        return supported != null && supported.contains(line);
    }

    /**
     * Toggle RTS on and off
     *
     * @param rts_on true: on, false: off
     */
    public boolean setRTS_On(boolean rts_on) {
        // Snapshot once — disconnect() may null the field on another thread
        // between the check and the setRTS() call (same race as sendData).
        final UsbSerialPort port = usbSerialPort;
        if (port == null) {
            fileLog("serial.setRTS: port not open!");
            return false;
        }
        try {
            if (!controlLineSupported(port.getSupportedControlLines(),
                    UsbSerialPort.ControlLine.RTS)) {
                // Report failure rather than a silent no-op: callers use the return
                // value to decide whether a PTT toggle actually happened, so a port
                // that can't drive RTS must not read as a successful toggle.
                fileLog("serial.setRTS: RTS not supported by this port");
                return false;
            }
            port.setRTS(rts_on);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            fileLog("serial.setRTS ERROR: " + e.getMessage());
            return false;
        }
    }

    public boolean setDTR_On(boolean dtr_on) {
        // Snapshot once (see setRTS_On) — the field can be nulled concurrently.
        final UsbSerialPort port = usbSerialPort;
        if (port == null) {
            fileLog("serial.setDTR: port not open!");
            return false;
        }
        try {
            if (!controlLineSupported(port.getSupportedControlLines(),
                    UsbSerialPort.ControlLine.DTR)) {
                // Report failure rather than a silent no-op (see setRTS_On): a port
                // that can't drive DTR must not read as a successful PTT toggle.
                fileLog("serial.setDTR: DTR not supported by this port");
                return false;
            }
            port.setDTR(dtr_on);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG, "setDTR_On: " + e.getMessage());
            fileLog("serial.setDTR ERROR: " + e.getMessage());
            return false;
        }
    }

    public OnConnectorStateChanged getOnStateChanged() {
        return onStateChanged;
    }

    public void setOnStateChanged(OnConnectorStateChanged onStateChanged) {
        this.onStateChanged = onStateChanged;
    }

    public int getVendorId() {
        return vendorId;
    }

    public void setVendorId(int deviceId) {
        this.vendorId = deviceId;
    }

    public int getPortNum() {
        return portNum;
    }

    public void setPortNum(int portNum) {
        this.portNum = portNum;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    /**
     * Get the list of available serial port devices on this device
     *
     * @param context context
     * @return list of serial port devices
     */
    public static ArrayList<SerialPort> listSerialPorts(Context context) {
        ArrayList<SerialPort> serialPorts = new ArrayList<>();
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return serialPorts;

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver == null) {
                // prepare() falls back to the CDC-ACM driver for a device no prober
                // knows, so a CDC-class rig could connect (auto-connect never sees it
                // though) yet never appear here. List it too — but only when it really
                // has a CDC control interface, see hasCdcControlInterface().
                if (!hasCdcControlInterface(interfaceClassesOf(device))) {
                    continue;
                }
                driver = new CdcAcmSerialDriver(device);
            }
            int portCount = driver.getPorts().size();
            // Class-literal mapping, not getSimpleName(): R8 renames the drivers in release.
            String family = SerialPortLabel.driverFamily(driver.getClass());
            for (int i = 0; i < portCount; i++) {
                serialPorts.add(new SerialPort(device.getDeviceId(), device.getVendorId()
                        , device.getProductId(), i, portCount, family));
            }
        }
        return serialPorts;
    }

    public boolean isConnected() {
        return connected;
    }


    public static class SerialPort {
        public int deviceId = 0;
        public int vendorId = 0x0c26;//Vendor ID
        public int productId = 0;//Product ID
        public int portNum = 0;//Port number
        /** How many ports the driver enumerated on this device (1 for a single-UART chip). */
        public int portCount = 1;
        /** Family key of the driver that claimed the device; see {@link SerialPortLabel#driverFamily}. */
        public String driverFamily = null;

        public SerialPort(int deviceId, int vendorId, int productId, int portNum) {
            this(deviceId, vendorId, productId, portNum, 1, null);
        }

        public SerialPort(int deviceId, int vendorId, int productId, int portNum,
                          int portCount, String driverFamily) {
            this.deviceId = deviceId;
            this.vendorId = vendorId;
            this.productId = productId;
            this.portNum = portNum;
            this.portCount = portCount;
            this.driverFamily = driverFamily;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public String toString() {
            return String.format("SerialPort:deviceId=0x%04X, vendorId=0x%04X, productId=0x%04X, portNum=%d/%d"
                    , deviceId, vendorId, productId, portNum, portCount);
        }

        /** Fallback for {@link #information()} when no resources are reachable. */
        static final String PORT_OF_FALLBACK = "Port %1$d of %2$d";

        /**
         * The picker row for this port, e.g. {@code "Silicon Labs CP2105 · Port 1 of 2 · Enhanced"}.
         * {@code portOfTemplate} is the localized {@code "Port %1$d of %2$d"}.
         */
        public String label(String portOfTemplate) {
            // getStringFromResource() returns "" with no main context (early startup,
            // unit tests); an empty template would format to nothing.
            boolean usable = portOfTemplate != null && !portOfTemplate.isEmpty();
            return SerialPortLabel.describe(vendorId, productId, driverFamily, portNum, portCount,
                    usable ? portOfTemplate : PORT_OF_FALLBACK);
        }

        /**
         * {@link #label} with the app-localized template. Used to print raw hex
         * ({@code \0x03E9\0x10C4\0xEA70\0x1}) — the reason nobody could tell the
         * two CP2105 rows apart (issue #817).
         */
        public String information() {
            String template;
            try {
                template = GeneralVariables.getStringFromResource(R.string.serial_port_label_port_of);
            } catch (RuntimeException e) {
                template = null;
            }
            return label(template);
        }
    }
}
