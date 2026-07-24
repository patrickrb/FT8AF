package com.k1af.ft8af.connector;

import android.content.Context;
import android.util.Log;

import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.rigs.BaseRig;
import com.k1af.ft8af.serialport.util.SerialInputOutputManager;

/**
 * Connector for wired (USB) connections, inherits from BaseRigConnector
 *
 * @author BG7YOZ
 * @date 2023-03-20
 */
public class CableConnector extends BaseRigConnector {
    private static final String TAG = "CableConnector";

    //2023-08-16 Submitted by DS1UFX (based on v0.9) to support (tr)uSDX audio over CAT.
    public interface OnCableDataReceived {
        void OnWaveReceived(int bufferLen, float[] buffer);
    }

    private final CableSerialPort cableSerialPort;

    private final BaseRig cableConnectedRig;
    private OnCableDataReceived onCableDataReceived;

    // Auto-reconnect state. A single serial glitch (marginal connector, RFI into
    // the cable during TX) used to drop the whole session and strand the user on
    // the manual "tap to retry" chip; instead we attempt a bounded, backed-off
    // auto-reconnect first (CatReconnectPolicy).
    private volatile boolean userDisconnected = false;
    private volatile boolean reconnecting = false;

    public CableConnector(Context context, CableSerialPort.SerialPort serialPort, int baudRate
                          //, int controlMode) {
            , int controlMode, BaseRig cableConnectedRig) {
        super(controlMode);
        this.cableConnectedRig = cableConnectedRig;
        cableSerialPort = new CableSerialPort(context, serialPort, baudRate, getOnConnectorStateChanged());
        cableSerialPort.ioListener = new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                if (getOnConnectReceiveData() != null) {
                    getOnConnectReceiveData().onData(data);
                }
            }

            @Override
            public void onRunError(Exception e) {
                Log.e(TAG, "CableConnector error: " + e.getMessage());
                handleSerialError(e);
            }
        };
        //connect();
    }

    /**
     * Reacts to a serial read-loop error. A transient glitch triggers a bounded,
     * backed-off auto-reconnect (the device usually re-enumerates with the same
     * VID/PID); a fatal error — or an exhausted reconnect budget — surfaces the
     * manual retry state. Decision logic lives in {@link CatReconnectPolicy} so
     * it can be unit-tested; this method is the thin wrapper.
     */
    private void handleSerialError(Exception e) {
        CatReconnectPolicy.Kind kind = CatReconnectPolicy.classify(e);
        switch (CatReconnectPolicy.decide(userDisconnected, kind, 0)) {
            case IGNORE:
                // User asked to disconnect; closing the port interrupts the blocking
                // read with an IOException we expect — don't surface "Lost connection".
                return;
            case RECONNECT:
                startAutoReconnect(e);
                return;
            case SURFACE:
            default:
                surfaceLostConnection(e);
        }
    }

    private void surfaceLostConnection(Exception e) {
        getOnConnectorStateChanged().onRunError(
                "Lost connection to serial port: " + (e == null ? "" : e.getMessage()));
    }

    private synchronized void startAutoReconnect(final Exception firstError) {
        if (reconnecting) return; // a burst is already being handled
        reconnecting = true;
        // Reflect an in-progress reconnect rather than an error so the UI doesn't
        // flip to "tap to retry" for a glitch we're about to absorb.
        getOnConnectorStateChanged().onConnecting();
        new Thread(() -> {
            try {
                for (int attempt = 1;
                     CatReconnectPolicy.shouldAutoReconnect(
                             CatReconnectPolicy.Kind.TRANSIENT, attempt - 1);
                     attempt++) {
                    if (userDisconnected) {
                        return;
                    }
                    long backoff = CatReconnectPolicy.backoffMs(attempt);
                    Log.d(TAG, "CAT auto-reconnect attempt " + attempt
                            + " in " + backoff + "ms");
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (userDisconnected) {
                        return;
                    }
                    // The port already tore itself down (SerialInputOutputManager
                    // calls disconnect() after onRunError); re-open it fresh.
                    if (cableSerialPort.connect() && cableSerialPort.isConnected()) {
                        // The user may have disconnected during the connect() call
                        // (which fires onConnected() internally); honor that intent
                        // by tearing the just-opened port back down.
                        if (userDisconnected) {
                            cableSerialPort.disconnect();
                            return;
                        }
                        Log.d(TAG, "CAT auto-reconnect succeeded on attempt " + attempt);
                        return; // connect() already fired onConnected()
                    }
                }
                // Budget exhausted — hand back to the manual retry state.
                Log.e(TAG, "CAT auto-reconnect exhausted; surfacing manual retry");
                surfaceLostConnection(firstError);
            } finally {
                reconnecting = false;
            }
        }, "CAT-Auto-Reconnect").start();
    }

    @Override
    public synchronized void sendData(byte[] data) {
        cableSerialPort.sendData(data);
    }


    /** See {@link #isLastPttWriteOk()}. Optimistic until a write actually fails. */
    private volatile boolean lastPttWriteOk = true;

    @Override
    public void setPttOn(boolean on) {
        //Only handle RTS and DTR
        int mode = getControlMode();
        if (mode != ControlMode.DTR && mode != ControlMode.RTS) {
            return;
        }
        boolean ok = toggleControlLine(mode, on);
        // Fail-safe: a failed PTT-OFF write on a dropped port must never leave
        // the rig keyed, so retry it a bounded number of times.
        // See CatReconnectPolicy.shouldRetryPtt.
        for (int attempt = 0; CatReconnectPolicy.shouldRetryPtt(on, ok, attempt); attempt++) {
            ok = toggleControlLine(mode, on);
        }
        lastPttWriteOk = ok;
        if (!on && !ok) {
            Log.e(TAG, "PTT-off write failed after retries; port likely dropped");
        }
    }

    private boolean toggleControlLine(int mode, boolean on) {
        if (mode == ControlMode.DTR) {
            return cableSerialPort.setDTR_On(on);//Toggle DTR on/off
        }
        return cableSerialPort.setRTS_On(on);//Toggle RTS on/off
    }

    @Override
    public void setPttOn(byte[] command) {
        boolean ok = cableSerialPort.sendData(command);//Send PTT via CAT command
        // CAT PTT commands are idempotent, so re-sending a failed write is safe
        // and fail-safes PTT-off in particular (never leave the rig keyed).
        for (int attempt = 0; CatReconnectPolicy.shouldRetryPtt(false, ok, attempt); attempt++) {
            ok = cableSerialPort.sendData(command);
        }
        lastPttWriteOk = ok;
        if (!ok) {
            Log.e(TAG, "CAT PTT write failed after retries; port likely dropped");
        }
    }

    /**
     * Result of the last {@link #setPttOn} write. False means the port was closed
     * or the write threw, so the rig never saw the command — if that command was
     * PTT-off, the transmitter is still keyed and the caller owes it a retry once
     * the link is back (see {@code PttSafetyLatch}).
     */
    @Override
    public boolean isLastPttWriteOk() {
        return lastPttWriteOk;
    }


    //The following is (tr)uSDX wave-related code, submitted 2023-08-16 by DS1UFX (based on v0.9) to support (tr)uSDX audio over CAT.
    @Override
    public void sendWaveData(byte[] data) {
        sendData(data);
    }

//    @Override
//    public void sendWaveData(float[] data) {
//        // TODO float to byte
//        byte[] wave = new byte[data.length * 4];
//
//        sendWaveData(wave);
//    }

    @Override
    public void receiveWaveData(float[] data) {
        Log.i(TAG, "received wave data");

        if (onCableDataReceived != null) {
            Log.i(TAG, "call onCableDataReceived.OnWaveReceived");
            onCableDataReceived.OnWaveReceived(data.length, data);
        }
    }

    public void setOnCableDataReceived(OnCableDataReceived onCableDataReceived) {
        this.onCableDataReceived = onCableDataReceived;
    }


    @Override
    public void connect() {
        userDisconnected = false;
        super.connect();
        getOnConnectorStateChanged().onConnecting();
        cableSerialPort.connect();
    }

    @Override
    public void disconnect() {
        // A deliberate user disconnect must cancel any in-flight auto-reconnect.
        userDisconnected = true;
        cableConnectedRig.onDisconnecting();
        super.disconnect();
        cableSerialPort.disconnect();
    }
}
