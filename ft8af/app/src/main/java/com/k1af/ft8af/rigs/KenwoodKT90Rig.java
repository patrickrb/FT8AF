package com.k1af.ft8af.rigs;

import static com.k1af.ft8af.GeneralVariables.QUERY_FREQ_TIMEOUT;
import static com.k1af.ft8af.GeneralVariables.START_QUERY_FREQ_DELAY;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k1af.ft8af.database.ControlMode;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Gen-3 commands; different rigs vary. Frequency length for 981/991 is 9 digits, others are 8 digits.
 */
public class KenwoodKT90Rig extends BaseRig {
    private static final String TAG = "KenwoodKT90Rig";
    private final StringBuilder buffer = new StringBuilder();

    private Timer readFreqTimer = new Timer();


    @Override
    public void onDisconnecting() {
        if (readFreqTimer != null) {
            readFreqTimer.cancel();
            readFreqTimer.purge();
            readFreqTimer = null;
        }
    }
    private TimerTask readTask() {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    if (!isConnected()) {
                        return; // skip this tick; timer stays alive for reconnect
                    }
                    readFreqFromRig();
                } catch (Exception e) {
                    Log.e(TAG, "readFreq error:" + e.getMessage());
                }
            }
        };
    }

    /**
     * Clear buffer data
     */
    private void clearBufferData() {
        buffer.setLength(0);
    }

    @Override
    public void setPTT(boolean on) {
        super.setPTT(on);
        if (getConnector() != null) {
            switch (getControlMode()) {
                case ControlMode.CAT://via CAT command
                    getConnector().setPttOn(KenwoodTK90RigConstant.setPTTState(on));
                    break;
                case ControlMode.RTS:
                case ControlMode.DTR:
                    getConnector().setPttOn(on);
                    break;
            }
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
            getConnector().sendData(KenwoodTK90RigConstant.setOperationUSBMode());
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(KenwoodTK90RigConstant.setOperationFreq(getFreq()));
        }
    }

    @Override
    public void onReceiveData(byte[] data) {
        String s = new String(data);

        if (!s.contains("\r"))
        {
            buffer.append(s);
            if (buffer.length()>1000) clearBufferData();
            //data reception not yet complete.
        }else {
            if (s.indexOf("\r")>0){//received end-of-data, and delimiter is not the first character
              buffer.append(s.substring(0,s.indexOf("\r")));
            }
            //begin parsing data
            Yaesu3Command yaesu3Command = Yaesu3Command.getCommand(buffer.toString());
            clearBufferData();//clear buffer
            //put remaining data into buffer
            buffer.append(s.substring(s.indexOf("\r")+1));

            if (yaesu3Command == null) {
                return;
            }
            if (yaesu3Command.getCommandID().equalsIgnoreCase("FA")) {
                long tempFreq=Yaesu3Command.getFrequency(yaesu3Command);
                if (tempFreq!=0) {//if tempFreq==0, frequency is invalid
                    setFreq(Yaesu3Command.getFrequency(yaesu3Command));
                }
            }

        }

    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            clearBufferData();//clear buffer
            getConnector().sendData(KenwoodTK90RigConstant.setReadOperationFreq());
        }
    }

    @Override
    public String getName() {
        return "KENWOOD TK90";
    }

    public KenwoodKT90Rig() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (getConnector()!=null){
                    getConnector().sendData(KenwoodTK90RigConstant.setVFOMode());
                }
            }
        },START_QUERY_FREQ_DELAY-500);
        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY,QUERY_FREQ_TIMEOUT);
    }
}
