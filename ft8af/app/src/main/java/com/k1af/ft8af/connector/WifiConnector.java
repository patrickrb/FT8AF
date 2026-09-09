package com.k1af.ft8af.connector;
/**
 * Base class for network-based connectors.
 * Note: Mostly compatible with ICom network mode, but some audio data packets differ -
 * they are Int type and need to be converted to Float type.
 *
 * @author BGY70Z
 * @date 2023-08-19
 */


import android.util.Log;

import com.k1af.ft8af.icom.WifiRig;

public class WifiConnector extends BaseRigConnector{
    private static final String TAG = "WifiConnector";
    public interface OnWifiDataReceived{
        void OnWaveReceived(int bufferLen,float[] buffer);
        void OnCivReceived(byte[] data);
    }


    public WifiRig wifiRig;
    public OnWifiDataReceived onWifiDataReceived;

    // Real link state (issue #754). wifiRig.opened flips true the instant start() is called,
    // before the UDP handshake — reading it as "connected" made the Settings header claim a
    // connection with the radio off. This tracks the actual login/close edges instead, and
    // feeds isConnected().
    private final WifiLinkState linkState = new WifiLinkState();


    public WifiConnector(int controlMode, WifiRig wifiRig) {
        super(controlMode);
        this.wifiRig=wifiRig;
        // Forward the rig's link lifecycle to the shared connector-state pipeline so the CAT
        // status chip, connect/disconnect toasts, and the liveness watchdog all light up for
        // the network path exactly as they do for USB/Bluetooth.
        this.wifiRig.setOnLinkStateChanged(new WifiRig.OnLinkStateChanged() {
            @Override
            public void onSessionBegin(int session) {
                // Fresh state for this attempt, scoped to its session id, at the instant the
                // rig advances the counter — so an event from the previous session can no
                // longer be admitted afterwards (Copilot review on #778).
                linkState.reset(session);
            }

            @Override
            public void onLoginResult(int session, boolean ok) {
                emit(linkState.onLoginResult(session, ok), "login " + (ok ? "ok" : "failed"));
            }

            @Override
            public void onSendError(int session) {
                emit(linkState.onSendError(session), "network send error");
            }

            @Override
            public void onClosed() {
                emit(linkState.onClosed(), "closed");
            }
        });
    }

    private void emit(WifiLinkState.Emit action, String reason) {
        if (action == null || getOnConnectorStateChanged() == null) return;
        switch (action) {
            case CONNECTED:
                getOnConnectorStateChanged().onConnected();
                break;
            case DISCONNECTED:
                getOnConnectorStateChanged().onDisconnected();
                break;
            case ERROR:
                getOnConnectorStateChanged().onRunError(reason);
                break;
        }
    }

    @Override
    public void sendWaveData(float[] data) {
        if (wifiRig.opened) {
            wifiRig.sendWaveData(data);
        }
    }

    @Override
    public void connect() {
        super.connect();
        // Announce the attempt so the chip shows "connecting" (amber) immediately; the
        // CONNECTED/ERROR edge follows from the login response. A reconnect reuses this same
        // connector instance, so each attempt needs a fresh link state — otherwise the
        // terminal flag from the previous close/error swallows the next login and the chip
        // never leaves "connecting" (Copilot review on #754). The reset itself happens in
        // onSessionBegin(), which the rig fires from start() as it advances the session id:
        // doing it here, before start(), left a window in which a late event from the old
        // session still matched the current id and hit the reset state (Copilot #778).
        if (getOnConnectorStateChanged() != null) {
            getOnConnectorStateChanged().onConnecting();
        }
        wifiRig.start();
    }

    @Override
    public void disconnect() {
        super.disconnect();
        wifiRig.close();
    }

    @Override
    public void sendData(byte[] data) {
        wifiRig.sendCivData(data);
    }

    @Override
    public void setPttOn(byte[] command) {
        wifiRig.sendCivData(command);
    }

    @Override
    public void setPttOn(boolean on) {
        if (wifiRig.opened){
            wifiRig.setPttOn(on);
        }
    }
    public OnWifiDataReceived getOnWifiDataReceived() {
        return onWifiDataReceived;
    }

    @Override
    public boolean isConnected() {
        // The real link state (post-login, pre-close), not wifiRig.opened which is true the
        // instant start() runs — see linkState (#754).
        return linkState.isConnected();
    }

    public void setOnWifiDataReceived(OnWifiDataReceived onDataReceived) {
        this.onWifiDataReceived = onDataReceived;
    }

    /**
     * Read a little-endian Short from stream data
     *
     * @param data  stream data
     * @param start starting offset
     * @return Int16
     */
    public static short readShortBigEndianData(byte[] data, int start) {
        if (data.length - start < 2) return 0;
        return (short) ((short) data[start] & 0xff
                | ((short) data[start + 1] & 0xff) << 8);
    }

}
