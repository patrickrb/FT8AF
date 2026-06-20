package com.k1af.ft8af.rigs;

import static com.k1af.ft8af.GeneralVariables.QUERY_FREQ_TIMEOUT;
import static com.k1af.ft8af.GeneralVariables.START_QUERY_FREQ_DELAY;

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.ui.ToastMessage;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Gen-3 commands; different rigs vary. Frequency length for 981/991 is 9 digits, others are 8 digits.
 */
public class ElecraftRig extends BaseRig {
    private static final String TAG = "ElecraftRig";
    private final StringBuilder buffer = new StringBuilder();
    private int swr = 0;
    private boolean swrAlert = false;

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
                    switch (ReadTaskAction.decide(isConnected(), isPttOn())) {
                        case SKIP:        return;
                        case READ_METERS: readMeters(); break;
                        case READ_FREQ:   readFreqFromRig(); break;
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
            getConnector().sendData(ElecraftRigConstant.setReadMetersSWR());
        }
    }

    private void showAlert() {
        if (!GeneralVariables.swr_switch_on) return;//check if alert switch is off
        if (swr >= ElecraftRigConstant.swr_alert_max) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
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
                    getConnector().setPttOn(ElecraftRigConstant.setPTTState(on));
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
            getConnector().sendData(ElecraftRigConstant.setOperationUSBMode());
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(ElecraftRigConstant.setOperationFreq11Byte(getFreq()));
        }
    }

    @Override
    public void onReceiveData(byte[] data) {
        String s = new String(data);

        if (!s.contains(";")) {
            buffer.append(s);
            if (buffer.length() > 1000) clearBufferData();
            // return;//data reception not yet complete.
        } else {
            if (s.indexOf(";") > 0) {//received end-of-data, and ';' is not the first character
                buffer.append(s.substring(0, s.indexOf(";")));
            }

            //begin parsing data
            ElecraftCommand elecraftCommand = ElecraftCommand.getCommand(buffer.toString());
            clearBufferData();//clear buffer
            //put remaining data into buffer
            buffer.append(s.substring(s.indexOf(";") + 1));

            if (elecraftCommand == null) {
                return;
            }
            if (elecraftCommand.getCommandID().equalsIgnoreCase("FA")
                    || elecraftCommand.getCommandID().equalsIgnoreCase("FB")) {
                long tempFreq = ElecraftCommand.getFrequency(elecraftCommand);
                if (tempFreq != 0) {//if tempFreq==0, frequency is invalid
                    setFreq(ElecraftCommand.getFrequency(elecraftCommand));
                }
            } else if (elecraftCommand.getCommandID().equalsIgnoreCase("SW")) {//METER
                if (ElecraftCommand.isSWRMeter(elecraftCommand)) {
                    swr = ElecraftCommand.getSWRMeter(elecraftCommand);
                }

                showAlert();
                notifyMeterData(-1, Math.min(swr * 4, 255));
            }


        }

    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            clearBufferData();//clear buffer
            getConnector().sendData(ElecraftRigConstant.setReadOperationFreq());
        }
    }

    @Override
    public String getName() {
        return "Elecraft series";
    }

    public ElecraftRig() {
        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY, QUERY_FREQ_TIMEOUT);
    }
}
