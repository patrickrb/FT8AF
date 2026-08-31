package com.k1af.ft8af.bluetooth;
/**
 * Bluetooth serial port socket
 * BG7YOZ
 * 2023-03
 */

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;

public class BluetoothSerialSocket implements Runnable {

    private static final String TAG = "BluetoothSerialSocket";
    private static final UUID BLUETOOTH_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BroadcastReceiver disconnectBroadcastReceiver;

    private final Context context;
    private BluetoothSerialListener listener;
    private final BluetoothDevice device;
    // volatile: assigned on the RFCOMM connect thread (run()), cleared to null on
    // the disconnect thread (disconnect(), driven by the background
    // INTENT_ACTION_DISCONNECT receiver or the read loop's error path), and read
    // on the CAT/TX thread (write()). Readers MUST snapshot it into a local before
    // check-and-use — see write()/writeIfConnected.
    private volatile BluetoothSocket socket;
    // volatile for the same reason as socket: set on the connect thread, cleared on
    // the disconnect thread, read on the CAT/TX thread (write()) — without it those
    // reads can see a stale value under the Java memory model.
    private volatile boolean connected;

    public BluetoothSerialSocket(Context context, BluetoothDevice device) {
        if(context instanceof Activity)
            throw new InvalidParameterException("expected non UI context");
        this.context = context;
        this.device = device;
        disconnectBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(listener != null)
                    listener.onSerialIoError(new IOException("background disconnect"));
                disconnect(); // disconnect now, else would be queued until UI re-attached
            }
        };
    }

    @SuppressLint("MissingPermission")
    String getName() {
        return device.getName() != null ? device.getName() : device.getAddress();
    }

    /**
     * connect-success and most connect-errors are returned asynchronously to listener
     */
    void connect(BluetoothSerialListener listener) throws IOException {
        this.listener = listener;
        // On API 33+ (Android 13) with targetSdk >= 33, registerReceiver() without an
        // export flag throws SecurityException for non-system broadcasts, silently aborting
        // the entire SPP/RFCOMM connection (issue #223 – CAT over Bluetooth).
        IntentFilter filter = new IntentFilter(BluetoothConstants.INTENT_ACTION_DISCONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(disconnectBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(disconnectBroadcastReceiver, filter);
        }
        Executors.newCachedThreadPool().submit(this);
    }

    void disconnect() {
        listener = null; // ignore remaining data and errors
        // connected = false; // run loop will reset connected
        if(socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }
        try {
            context.unregisterReceiver(disconnectBroadcastReceiver);
        } catch (Exception ignored) {
        }
    }

    void write(byte[] data) throws IOException {
        // Snapshot the volatile socket ONCE. disconnect() (the background
        // INTENT_ACTION_DISCONNECT receiver, or the read loop's error path) may
        // null `socket` on another thread between the connected check and the
        // getOutputStream() call. Reading the field twice let that concurrent
        // disconnect turn a passed null-check into a null dereference — an NPE
        // that escapes BluetoothRigConnector.sendCommand's IOException-only catch
        // and crashes the CAT/TX worker mid-QSO. Passing the single snapshot to
        // writeIfConnected reports a torn-down link as the "not connected"
        // IOException the caller already handles instead. Mirrors the USB-serial
        // fix in CableSerialPort.writeIfOpen.
        final BluetoothSocket s = socket;
        writeIfConnected(connected, s == null ? null : d -> s.getOutputStream().write(d), data);
    }

    /**
     * A sink that writes one frame to the live link — the socket's output stream
     * in production, a capture in tests.
     */
    @FunctionalInterface
    interface ByteSink {
        void write(byte[] data) throws IOException;
    }

    /**
     * Issue a write only if the link is still up. {@code sink} is the caller's
     * single snapshot of the (volatile) socket's writer, so a concurrent
     * {@link #disconnect()} that nulls the field afterwards cannot affect this
     * call. When the link is down ({@code !connected}) or the snapshot was
     * already null (disconnect() ran first, before {@code connected} was cleared),
     * throw the {@code "not connected"} {@link IOException} the CAT connector
     * already handles ({@code BluetoothRigConnector.sendCommand}) rather than
     * NPEing on a nulled socket and crashing the CAT/TX worker thread. Mirrors
     * {@code CableSerialPort.writeIfOpen} on the USB-serial path.
     */
    static void writeIfConnected(boolean connected, ByteSink sink, byte[] data) throws IOException {
        if (!connected || sink == null) {
            throw new IOException("not connected");
        }
        sink.write(data);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void run() { // connect & read
        try {
            Log.d(TAG, "run: creating RFCOMM socket to " + device.getAddress());
            socket = device.createRfcommSocketToServiceRecord(BLUETOOTH_SPP);
            socket.connect();
            Log.d(TAG, "run: RFCOMM connected to " + device.getAddress());
            if(listener != null)
                listener.onSerialConnect();
        } catch (Exception e) {
            Log.e(TAG, "run: RFCOMM connect failed: " + e.getMessage());
            if(listener != null)
                listener.onSerialConnectError(e);
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
            return;
        }
        connected = true;
        try {
            byte[] buffer = new byte[1024];
            int len;
            //noinspection InfiniteLoopStatement
            while (true) {
                len = socket.getInputStream().read(buffer);
                byte[] data = Arrays.copyOf(buffer, len);
                if(listener != null)
                    listener.onSerialRead(data);
            }
        } catch (Exception e) {
            connected = false;
            if (listener != null)
                listener.onSerialIoError(e);
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }
    }

}
