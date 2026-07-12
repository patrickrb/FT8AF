package com.k1af.ft8af.rigs;

import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.connector.X6100Connector;
import com.k1af.ft8af.ft8transmit.GenerateFT8;

/**
 * XieGu6100 ft8cns mode, only supports network mode. Ensure proper validation when setting up baseRig.
 */
public class XieGu6100NetRig extends BaseRig {
    private static final String TAG = "x6100RigNet";

    //private final int ctrAddress = 0xE0;//receive address, default 0xE0; rig reply can also be 0x00




    @Override
    public void setPTT(boolean on) {
        super.setPTT(on);
        if (getConnector() != null) {
                getConnector().setPttOn(on);
        }
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
            X6100Connector x6100Connector =(X6100Connector)getConnector();
            x6100Connector.getXieguRadio().commandSetMode("u-dig",1);
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            X6100Connector x6100Connector =(X6100Connector)getConnector();
            x6100Connector.getXieguRadio().commandTuneFreq(getFreq());
        }
    }


    @Override
    public void onReceiveData(byte[] data) {
        //command parsing is handled in X6100Radio; no action needed here
    }

    @Override
    public void sendWaveData(Ft8Message message) {//send audio data to rig, for network mode
        if (getConnector() != null) {//pass generated audio data to Connector
            //if ft8cns mode, transmit a91 data packet
            if (GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS){
                //Log.e(TAG,"generate A91");
                byte[] a91 = GenerateFT8.generateA91(message, true);
                if (a91 == null) {//invalid callsign: generateA91 aborted (toast already shown)
                    setPTT(false);
                    return;
                }
                getConnector().sendFt8A91(a91, GeneralVariables.getBaseFrequency());
            }else {//otherwise transmit audio data normally
                float[] data = GenerateFT8.generateFt8(message, GeneralVariables.getBaseFrequency()
                        , 12000);//rig audio sample rate is 12000
                if (data == null) {//invalid callsign: generateFt8 aborted (toast already shown)
                    setPTT(false);
                    return;
                }
                getConnector().sendWaveData(data);
            }
        }
    }

    @Override
    public void readFreqFromRig() {//frequency is obtained via X6100Radio status; frequency query command not needed here

    }

    @Override
    public String getName() {
        return "XIEGU X6100 series";
    }

    public XieGu6100NetRig(int civAddress) {
        Log.d(TAG, "x6100RigNet: Create.");
        setCivAddress(civAddress);
    }
}
