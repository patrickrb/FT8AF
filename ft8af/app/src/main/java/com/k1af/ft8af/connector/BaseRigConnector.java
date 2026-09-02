package com.k1af.ft8af.connector;
/**
 * Base class for connecting to radios. Bluetooth, USB cable, FLEX network,
 * and ICOM network connectors all inherit from this class.
 *
 * @author BG7YOZ
 * @date 2023-03-20
 */

import com.k1af.ft8af.rigs.OnConnectReceiveData;
import com.k1af.ft8af.rigs.OnRigStateChanged;


public class BaseRigConnector {
    //Whether currently connected. Volatile: written by the connector's I/O
    //callbacks (onConnected/onDisconnected/onRunError), read by rig poll timers
    //on their own threads (IcomRig.runReadFreqTick gates every tick on it) —
    //without it a Timer thread may legally keep seeing a stale value and either
    //never start polling or keep sending after a drop.
    private volatile boolean connected;
    /**
     * Bumped every time the link comes up. Lets a poller that only samples
     * {@link #isConnected()} periodically tell a link that stayed up from one
     * that dropped and reopened between two samples (the cable auto-reconnect
     * retries within 500 ms, well inside a 2 s poll period) — see
     * {@code IcomRig.runReadFreqTick}. Volatile: written on the connector's
     * I/O thread, read on rig timer threads.
     */
    private volatile int connectionGeneration;
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
            connectionGeneration++;
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

    /**
     * Whether the most recent {@link #setPttOn} write actually reached the rig.
     *
     * <p>Lets the transmit path tell "PTT-off sent" from "PTT-off attempted at a
     * port that had already gone away" — the difference between a rig that is
     * receiving and one left keyed. Connectors that cannot fail this way (network
     * rigs, which surface their own errors) keep the optimistic default; the USB
     * cable path overrides it.
     *
     * @return true if the last PTT write is believed delivered
     */
    public boolean isLastPttWriteOk(){ return true; }

    /**
     * Whether the most recent {@link #sendData} write actually reached the rig.
     *
     * <p>Same posture as {@link #isLastPttWriteOk()}: lets the band-change path tell
     * "frequency command sent" from "command attempted at a port that had already gone
     * away" — the difference between an operator selection that has been DELIVERED
     * (and may be superseded by what the rig reports back) and one that is still
     * pending. Connectors that cannot fail silently keep the optimistic default; the
     * USB cable path overrides it.
     *
     * @return true if the last CAT data write is believed delivered
     */
    public boolean isLastCatWriteOk(){ return true; }

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

    /** How many times this connector's link has come up; see the field note. */
    public int connectionGeneration() {
        return connectionGeneration;
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
