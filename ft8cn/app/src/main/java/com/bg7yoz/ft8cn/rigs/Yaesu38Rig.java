package com.bg7yoz.ft8cn.rigs;

import static com.bg7yoz.ft8cn.GeneralVariables.QUERY_FREQ_TIMEOUT;
import static com.bg7yoz.ft8cn.GeneralVariables.START_QUERY_FREQ_DELAY;

import android.util.Log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.util.Timer;
import java.util.TimerTask;

public class Yaesu38Rig extends BaseRig {
    private static final String TAG = "Yaesu3Rig";
    private final StringBuilder buffer = new StringBuilder();

    private int swr = 0;
    private int alc = 0;
    private boolean alcMaxAlert = false;
    private boolean swrAlert = false;

    private Timer readFreqTimer = new Timer();

    private TimerTask readTask() {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    if (!isConnected()) {
                        return; // skip this tick; timer stays alive for reconnect
                    }
                    if (isPttOn()) {
                        readMeters();
                    } else {
                        readFreqFromRig();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "readFreq error:" + e.getMessage());
                }
            }
        };
    }

    /**
     * Read Meter RM;
     */
    private void readMeters() {
        if (getConnector() != null) {
            clearBufferData();//clear buffer
            getConnector().sendData(Yaesu3RigConstant.setRead39Meters_ALC());
            getConnector().sendData(Yaesu3RigConstant.setRead39Meters_SWR());
        }
    }

    private void showAlert() {
        if ((swr >= Yaesu3RigConstant.swr_39_alert_max)
                && GeneralVariables.swr_switch_on) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }
        if ((alc > Yaesu3RigConstant.alc_39_alert_max)
                && GeneralVariables.alc_switch_on) {//ALC alert
            if (!alcMaxAlert) {
                alcMaxAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_high_alert));
            }
        } else {
            alcMaxAlert = false;
        }

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
                    getConnector().setPttOn(Yaesu3RigConstant.setPTTState(on));
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
            getConnector().sendData(Yaesu3RigConstant.setOperationUSBMode());
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(Yaesu3RigConstant.setOperationFreq8Byte(getFreq()));
        }
    }

    @Override
    public void onReceiveData(byte[] data) {
        String s = new String(data);
        if (!s.contains(";")) {
            buffer.append(s);
            if (buffer.length() > 1000) clearBufferData();
            return;//data reception not yet complete.
        } else {
            if (s.indexOf(";") > 0) {//received end-of-data, and delimiter is not the first character
                buffer.append(s.substring(0, s.indexOf(";")));
            }

            //begin parsing data
            Yaesu3Command yaesu3Command = Yaesu3Command.getCommand(buffer.toString());
            clearBufferData();//clear buffer
            //put remaining data into buffer
            buffer.append(s.substring(s.indexOf(";") + 1));

            if (yaesu3Command == null) {
                return;
            }
            //long tempFreq = Yaesu3Command.getFrequency(yaesu3Command);
            //if (tempFreq != 0) {//if tempFreq==0, frequency is invalid
            //    setFreq(Yaesu3Command.getFrequency(yaesu3Command));
            //}


            if (yaesu3Command.getCommandID().equalsIgnoreCase("FA")
                    || yaesu3Command.getCommandID().equalsIgnoreCase("FB")) {
                long tempFreq = Yaesu3Command.getFrequency(yaesu3Command);
                if (tempFreq != 0) {//if tempFreq==0, frequency is invalid
                    setFreq(Yaesu3Command.getFrequency(yaesu3Command));
                }
            } else if (yaesu3Command.getCommandID().equalsIgnoreCase("RM")) {//METER
                if (Yaesu3Command.isSWRMeter38(yaesu3Command)) {
                    swr = Yaesu3Command.getALCOrSWR38(yaesu3Command);
                }
                if (Yaesu3Command.isALCMeter38(yaesu3Command)) {
                    alc = Yaesu3Command.getALCOrSWR38(yaesu3Command);
                }
                showAlert();
                notifyMeterData(alc, swr);
            }


        }

    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            clearBufferData();//clear buffer
            getConnector().sendData(Yaesu3RigConstant.setReadOperationFreq());
        }
    }

    @Override
    public String getName() {
        return "YAESU FT-2000 series";
    }

    public Yaesu38Rig() {
        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY, QUERY_FREQ_TIMEOUT);
    }
}
