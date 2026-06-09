package com.bg7yoz.ft8cn.connector;
/**
 * Base class for connecting to radios. Bluetooth, USB cable, FLEX network,
 * and ICOM network connectors all inherit from this class.
 *
 * @author BG7YOZ
 * @date 2023-03-20
 */

import com.bg7yoz.ft8cn.rigs.OnConnectReceiveData;
import com.bg7yoz.ft8cn.rigs.OnRigStateChanged;


public class BaseRigConnector {
    private boolean connected;//Whether currently connected
    private OnConnectReceiveData onConnectReceiveData;//Action to take when data is received
    private int controlMode;//Control mode
    private OnRigStateChanged onRigStateChanged;
    private final OnConnectorStateChanged onConnectorStateChanged=new OnConnectorStateChanged() {
        @Override
        public void onDisconnected() {
            if (onRigStateChanged!=null){
                onRigStateChanged.onDisconnected();
            }
            connected=false;
        }

        @Override
        public void onConnected() {
            if (onRigStateChanged!=null){
                onRigStateChanged.onConnected();
            }
            connected=true;
        }

        @Override
        public void onConnecting() {
            if (onRigStateChanged!=null){
                onRigStateChanged.onConnecting();
            }
        }

        @Override
        public void onRunError(String message) {
            if (onRigStateChanged!=null){
                onRigStateChanged.onRunError(message);
            }
            connected=false;
        }
    };
    public BaseRigConnector(int controlMode) {
        this.controlMode=controlMode;
    }

    /**
     * Send data
     * @param data the data to send
     */
    public synchronized void sendData(byte[] data){};

    /**
     * Set PTT state ON/OFF. For RTS and DTR, this only applies in wired mode
     * and is overridden in CableConnector.
     * @param on whether to turn PTT on
     */
    public void setPttOn(boolean on){};

    /**
     * Set PTT state by sending a data command
     * @param command command data
     */
    public void setPttOn(byte[] command){};

    public void setControlMode(int mode){
        controlMode=mode;
    }

    public int getControlMode() {
        return controlMode;
    }

    public void setOnConnectReceiveData(OnConnectReceiveData receiveData){
        onConnectReceiveData=receiveData;
    }


    /**
     * 2023-08-16 Submitted by DS1UFX (based on v0.9) to support (tr)uSDX audio over CAT.
     * Send audio data stream, converting 16-bit int format to 32-bit float format.
     * @param data byte format, actually 16-bit int
     */
    public void sendWaveData(byte[] data){
        float[] waveFloat=new float[data.length/2];
        for (int i = 0; i <waveFloat.length ; i++) {
            waveFloat[i]=readShortBigEndianData(data,i*2)/32768.0f;
        }
        sendWaveData(waveFloat);
    }

    public void sendWaveData(float[] data){
        //Reserved for sending audio stream via network
    }
    public void sendFt8A91(byte[] a91,float baseFreq){
        //Used for X6100 FT8CNs mode
    }

    public void setRFVolume(int volume){
        //Used for X6100 FT8CNs mode
    }

    //2023-08-16 Submitted by DS1UFX (based on v0.9) to support (tr)uSDX audio over CAT.
    public void receiveWaveData(byte[] data){
        float[] waveFloat=new float[data.length/2];
        for (int i = 0; i <waveFloat.length ; i++) {
            waveFloat[i]=readShortBigEndianData(data,i*2)/32768.0f;
        }
        receiveWaveData(waveFloat);
    }
    public void receiveWaveData(short[] data){
        float[] waveFloat=new float[data.length];
        for (int i = 0; i <waveFloat.length ; i++) {
            waveFloat[i]=data[i]/32768.0f;
        }
        receiveWaveData(waveFloat);
    }
    public void receiveWaveData(float[] data){
    }

    public OnConnectReceiveData getOnConnectReceiveData() {
        return onConnectReceiveData;
    }
    public void connect(){
    }
    public void disconnect(){
    }

    public OnRigStateChanged getOnRigStateChanged() {
        return onRigStateChanged;
    }

    public void setOnRigStateChanged(OnRigStateChanged onRigStateChanged) {
        this.onRigStateChanged = onRigStateChanged;
    }

    public OnConnectorStateChanged getOnConnectorStateChanged() {
        return onConnectorStateChanged;
    }
    public boolean isConnected(){
        return connected;
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
