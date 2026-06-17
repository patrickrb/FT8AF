package com.k1af.ft8af.connector;
/**
 * ICom network mode connector.
 * Note: ICom network mode audio data packets are Int type and need to be converted to Float type.
 *
 * @author BGY70Z
 * @date 2023-08-19
 */


import com.k1af.ft8af.icom.WifiRig;

public class IComWifiConnector extends WifiConnector{
    private static final String TAG = "IComWifiConnector";

    public IComWifiConnector(int controlMode,WifiRig wifiRig) {
        super(controlMode,wifiRig);

        this.wifiRig.setOnDataEvents(new WifiRig.OnDataEvents() {
            @Override
            public void onReceivedCivData(byte[] data) {
                if (getOnConnectReceiveData()!=null){
                    getOnConnectReceiveData().onData(data);
                }
                if (onWifiDataReceived!=null) {
                    onWifiDataReceived.OnCivReceived(data);
                }
            }

            @Override
            public void onReceivedWaveData(byte[] data) {//audio data received event; converts audio data to float format.
                if (onWifiDataReceived!=null){
                    float[] waveFloat=new float[data.length/2];
                    for (int i = 0; i <waveFloat.length ; i++) {
                        waveFloat[i]=readShortBigEndianData(data,i*2)/32768.0f;
                    }
                    onWifiDataReceived.OnWaveReceived(waveFloat.length,waveFloat);
                }
            }
        });
    }


}
