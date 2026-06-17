package com.k1af.ft8af.rigs;

import android.annotation.SuppressLint;
import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.connector.FlexConnector;
import com.k1af.ft8af.flex.FlexCommand;
import com.k1af.ft8af.flex.FlexRadio;
import com.k1af.ft8af.ft8transmit.GenerateFT8;

public class FlexNetworkRig extends BaseRig {
    private static final String TAG = "FlexNetworkRig";
    private int commandSeq = 1;//command sequence number
    private FlexCommand flexCommand;
    private String commandStr;

    //private final int ctrAddress=0xE0;//receive address, default 0xE0; rig reply can also be 0x00
    //private byte[] dataBuffer=new byte[0];//data buffer
    @SuppressLint("DefaultLocale")
    public void sendCommand(FlexCommand command, String cmdContent) {
        if (getConnector().isConnected()) {
            commandSeq++;
            flexCommand = command;
            commandStr = String.format("C%d%03d|%s\n", commandSeq, command.ordinal()
                    , cmdContent);
            getConnector().sendData(commandStr.getBytes());
        }
    }


    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceTune(int sliceOder, String freq) {
        sendCommand(FlexCommand.SLICE_TUNE, String.format("slice t %d %s", sliceOder, freq));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceSetMode(int sliceOder, FlexRadio.FlexMode mode) {
        sendCommand(FlexCommand.SLICE_SET_TX_ANT, String.format("slice s %d mode=%s", sliceOder, mode.toString()));
    }

    @Override
    public void setPTT(boolean on) {
        getConnector().setPttOn(on);

    }

    @Override
    public boolean isConnected() {
        if (getConnector() == null) {
            return false;
        }
        return getConnector().isConnected();
    }

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            commandSliceSetMode(0, FlexRadio.FlexMode.DIGU);//set operating mode
        }
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            commandSliceTune(0, String.format("%.3f", getFreq() / 1000000f));
        }
    }


    @Override
    public void onReceiveData(byte[] data) {
        //ToastMessage.show("--"+byteToStr(data));

    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            //getConnector().sendData(IcomRigConstant.setReadFreq(ctrAddress, getCivAddress()));
        }
    }

    @Override
    public void sendWaveData(Ft8Message message) {

        if (getConnector() != null) {
            float[] data = GenerateFT8.generateFt8(message, GeneralVariables.getBaseFrequency()
                    , 24000);//Flex audio sample rate is 24000
            if (data == null) {
                setPTT(false);
                return;
            }
            getConnector().sendWaveData(data);
        }
    }

    @Override
    public String getName() {
        return "FlexRadio series";
    }


    public String getFrequencyStr() {
        return BaseRigOperation.getFrequencyStr(getFreq());
    }

    public FlexNetworkRig() {
        Log.d(TAG, "FlexRadio: Create.");
    }
}
