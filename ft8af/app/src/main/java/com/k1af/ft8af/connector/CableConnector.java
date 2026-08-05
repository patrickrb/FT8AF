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
    // Burst state for the escalating backoff. reconnectAttempt PERSISTS across successful
    // opens and resets only once a connection has held for
    // CatReconnectPolicy.STABLE_CONNECTION_MS — a port that opens is not a link that works,
    // and treating an open as success is what pinned the backoff at its first step and
    // produced 13,190 port opens in 88 minutes.
    //
    // AtomicInteger, not a volatile int: the increment below runs on the
    // CAT-Auto-Reconnect thread while handleSerialError() (serial IO thread) and
    // connect() (UI thread) reset it. `++` on a volatile is a read-modify-write and is
    // NOT atomic, so a lost update would hold the counter down — pinning the backoff near
    // BASE_BACKOFF_MS and reviving the exact storm this guards against.
    private final java.util.concurrent.atomic.AtomicInteger reconnectAttempt =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile long portOpenedAtMs = 0L;

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
        // Only a connection that actually HELD counts as a recovery. Without this the
        // burst restarted on every open and the backoff never escalated past its first
        // 500 ms step, which is the reconnect storm this guards against.
        // Reset and snapshot as one logical step so the value handed to decide() is the
        // one this call established, not whatever the reconnect thread has since reached.
        int attemptsSoFar;
        if (CatReconnectPolicy.shouldResetBurst(System.currentTimeMillis(), portOpenedAtMs)) {
            reconnectAttempt.set(0);
            attemptsSoFar = 0;
        } else {
            attemptsSoFar = reconnectAttempt.get();
        }
        switch (CatReconnectPolicy.decide(userDisconnected, kind, attemptsSoFar)) {
            case IGNORE:
                // User asked to disconnect; closing the port interrupts the blocking
                // read with an IOException we expect — don't surface "Lost connection".
                return;
            case RECONNECT:
                startAutoReconnect();
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

    private synchronized void startAutoReconnect() {
        if (reconnecting) return; // a burst is already being handled
        reconnecting = true;
        // Reflect an in-progress reconnect rather than an error so the UI doesn't
        // flip to "tap to retry" for a glitch we're about to absorb.
        getOnConnectorStateChanged().onConnecting();
        new Thread(() -> {
            try {
                // Unbounded in TIME by design: giving up on a still-present device would
                // strand the operator with no CAT until they noticed the retry chip. The
                // backoff escalates to MAX_BACKOFF_MS and stays there, so a persistently
                // flaky link costs one attempt every 8 s instead of two per second. But a
                // device that has LEFT the bus ends the loop: retrying can't bring it
                // back, and each fresh attempt was raising the system USB-permission
                // dialog — the "asks for permission when I unplug the cable" storm. The
                // USB ATTACH broadcast restarts auto-connect when it returns.
                while (CatReconnectPolicy.shouldKeepRetrying(
                        userDisconnected, cableSerialPort.isDevicePresent())) {
                    // The burst counter persists across opens, so a link that keeps
                    // dropping keeps escalating instead of resetting to the first step.
                    int attempt = reconnectAttempt.incrementAndGet();
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
                    if (!cableSerialPort.isDevicePresent()) {
                        break; // gone mid-backoff — surfaced after the loop
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
                        // NOT a burst reset: the port is open, which is not yet proof the
                        // link works. handleSerialError() resets the counter only once
                        // this connection has held for STABLE_CONNECTION_MS.
                        portOpenedAtMs = System.currentTimeMillis();
                        Log.d(TAG, "CAT port re-opened on attempt " + attempt);
                        return; // connect() already fired onConnected()
                    }
                }
                // The while-condition ended the loop: the device left the bus before the
                // first attempt (a deliberate user disconnect returns above instead).
                // Tear the port down rather than just notifying: each connect() attempt
                // re-registers the permission-grant receiver via prepare(), so exiting
                // without disconnect() would leak it (and any half-open port state).
                // disconnect() fires onDisconnected itself, which also moves the UI
                // out of the "connecting" state set at burst start.
                if (!userDisconnected) {
                    Log.d(TAG, "CAT auto-reconnect: device left the bus, stopping"
                            + " (attach broadcast will restart)");
                    cableSerialPort.disconnect();
                }
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
        // A user-initiated connect is a fresh start: clear any escalation left over from a
        // previous flapping session so the first glitch is again absorbed at 500 ms.
        reconnectAttempt.set(0);
        super.connect();
        getOnConnectorStateChanged().onConnecting();
        if (cableSerialPort.connect()) {
            portOpenedAtMs = System.currentTimeMillis();
        }
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
