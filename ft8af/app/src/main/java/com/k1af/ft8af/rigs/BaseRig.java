package com.k1af.ft8af.rigs;

import androidx.lifecycle.MutableLiveData;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.connector.BaseRigConnector;

/**
 * Abstract class for radios.
 * @author BGY70Z
 * @date 2023-03-20
 */
public abstract class BaseRig {
    private long freq;//current frequency value
    public MutableLiveData<Long> mutableFrequency = new MutableLiveData<>();
    private int controlMode;//control mode
    private OnRigStateChanged onRigStateChanged;//callback when rig state changes
    private int civAddress;//CIV address
    private int baudRate;//baud rate
    private boolean isPttOn=false;//whether PTT is on
    private BaseRigConnector connector = null;//rig connector object

    public abstract boolean isConnected();//check if rig is connected

    public abstract void setUsbModeToRig();//set rig to upper sideband (USB) mode

    public abstract void setFreqToRig();//set rig frequency

    public abstract void onReceiveData(byte[] data);//action when rig sends data back

    public abstract void readFreqFromRig();//read frequency from rig

    public abstract String getName();//get rig name

    private final OnConnectReceiveData onConnectReceiveData = new OnConnectReceiveData() {
        @Override
        public void onData(byte[] data) {
            onReceiveData(data);
        }
    };

    public void setPTT(boolean on) {//set PTT on or off
        isPttOn=on;
        if (onRigStateChanged != null) {
            onRigStateChanged.onPttChanged(on);
        }
    }

//    public void sendWaveData(float[] data) {
//        //reserved for ICOM rig use
//    }
    public void sendWaveData(Ft8Message message) {
        //reserved for ICOM rig use
    }

    public long getFreq() {
        return freq;
    }

    public void setFreq(long freq) {
        if (freq == 0) return;
        if (freq == -1) return;
        // The rig answered with a valid frequency — proof the link is alive even if the dial
        // didn't move. Fire this BEFORE the unchanged-frequency early return below so the CAT
        // liveness watchdog (which keys off onRigResponded, not onFreqChanged) sees every
        // reply and doesn't falsely trip on a stable dial.
        if (onRigStateChanged != null) {
            onRigStateChanged.onRigResponded();
        }
        if (freq == this.freq) return;
        mutableFrequency.postValue(freq);
        this.freq = freq;
        if (onRigStateChanged != null) {
            onRigStateChanged.onFreqChanged(freq);
        }
    }

    public void setConnector(BaseRigConnector connector) {
        this.connector = connector;

        this.connector.setOnRigStateChanged(onRigStateChanged);
        this.connector.setOnConnectReceiveData(new OnConnectReceiveData() {
            @Override
            public void onData(byte[] data) {
                onReceiveData(data);
            }
        });
    }

    public void setControlMode(int mode) {
        controlMode = mode;
        if (connector != null) {
            connector.setControlMode(mode);
        }
    }

    public int getControlMode() {
        return controlMode;
    }

    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }

    public BaseRigConnector getConnector() {
        return connector;
    }

    public OnRigStateChanged getOnRigStateChanged() {
        return onRigStateChanged;
    }

    public void setOnRigStateChanged(OnRigStateChanged onRigStateChanged) {
        this.onRigStateChanged = onRigStateChanged;
    }

    public int getCivAddress() {
        return civAddress;
    }

    public void setCivAddress(int civAddress) {
        this.civAddress = civAddress;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    public boolean isPttOn() {
        return isPttOn;
    }


    /**
     * 2023-08-16 Modification submitted by DS1UFX (based on v0.9), adding (tr)uSDX audio over CAT support.
     */
    public boolean supportWaveOverCAT() {
        return false;
    }

    public void onDisconnecting() {
    }

    // Meter data callback for MeterProtectionController (ALC auto-volume, SWR halt)
    public interface OnMeterData {
        void onMeterUpdate(int normalizedAlc, int normalizedSwr);
    }
    private OnMeterData onMeterData;
    public void setOnMeterData(OnMeterData listener) { this.onMeterData = listener; }
    protected void notifyMeterData(int alc, int swr) {
        if (onMeterData != null) onMeterData.onMeterUpdate(alc, swr);
    }

}
