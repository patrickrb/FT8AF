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


    public WifiConnector(int controlMode, WifiRig wifiRig) {
        super(controlMode);
        this.wifiRig=wifiRig;

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
        return wifiRig.opened;
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
