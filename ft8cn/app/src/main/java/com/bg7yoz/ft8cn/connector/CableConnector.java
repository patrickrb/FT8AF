package com.bg7yoz.ft8cn.connector;

import android.content.Context;
import android.util.Log;

import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.rigs.BaseRig;
import com.bg7yoz.ft8cn.serialport.util.SerialInputOutputManager;

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
                getOnConnectorStateChanged().onRunError("Lost connection to serial port: " + e.getMessage());
            }
        };
        //connect();
    }

    @Override
    public synchronized void sendData(byte[] data) {
        cableSerialPort.sendData(data);
    }


    @Override
    public void setPttOn(boolean on) {
        //Only handle RTS and DTR
        switch (getControlMode()) {
            case ControlMode.DTR:
                cableSerialPort.setDTR_On(on);//Toggle DTR on/off
                break;
            case ControlMode.RTS:
                cableSerialPort.setRTS_On(on);//Toggle RTS on/off
                break;
        }
    }

    @Override
    public void setPttOn(byte[] command) {
        cableSerialPort.sendData(command);//Send PTT via CAT command
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
        super.connect();
        getOnConnectorStateChanged().onConnecting();
        cableSerialPort.connect();
    }

    @Override
    public void disconnect() {
        cableConnectedRig.onDisconnecting();
        super.disconnect();
        cableSerialPort.disconnect();
    }
}
