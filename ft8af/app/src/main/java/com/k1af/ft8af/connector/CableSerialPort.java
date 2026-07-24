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
import java.util.EnumSet;

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
        return GeneralVariables.instructionSet == InstructionSet.YAESU_FT710
                && GeneralVariables.connectMode == ConnectMode.USB_CABLE
                && GeneralVariables.controlMode == ControlMode.CAT;
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


        for (UsbDevice v : usbManager.getDeviceList().values()) {
            if (v.getVendorId() == vendorId) {
                device = v;
            }
        }
        if (device == null) {
            Log.e(TAG, String.format("Failed to open serial device: device 0x%04x not found", vendorId));
            return false;
        }
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
            usbPermission = UsbPermission.Requested;

            PendingIntent usbPermissionIntent =
                    UsbPermissionIntentsKt.createUsbPermissionIntent(context, INTENT_ACTION_GRANT_USB);


            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            prepare();
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
                continue;
                //Try adding the unknown device to the CDC driver
                //driver = new CdcAcmSerialDriver(device);
            }
            for (int i = 0; i < driver.getPorts().size(); i++) {
                serialPorts.add(new SerialPort(device.getDeviceId(), device.getVendorId()
                        , device.getProductId(), i));
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

        public SerialPort(int deviceId, int vendorId, int productId, int portNum) {
            this.deviceId = deviceId;
            this.vendorId = vendorId;
            this.productId = productId;
            this.portNum = portNum;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public String toString() {
            return String.format("SerialPort:deviceId=0x%04X, vendorId=0x%04X, portNum=%d"
                    , deviceId, vendorId, portNum);
        }

        @SuppressLint("DefaultLocale")
        public String information() {
            return String.format("\\0x%04X\\0x%04X\\0x%04X\\0x%d", deviceId, vendorId, productId, portNum);
        }
    }
}
