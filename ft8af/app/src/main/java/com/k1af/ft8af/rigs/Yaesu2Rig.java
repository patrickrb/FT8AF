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
 * Some YAESU rigs send data non-continuously, so a buffer is needed to receive 5-byte blocks. Resets when full or when sending a command.
 */
public class Yaesu2Rig extends BaseRig {
    private static final String TAG = "Yaesu2Rig";
    private Timer readFreqTimer = new Timer();

    private int swr = 0;
    private int alc = 0;
    private boolean alcMaxAlert = false;
    private boolean swrAlert = false;


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


    @Override
    public void setPTT(boolean on) {
        super.setPTT(on);

        if (getConnector() != null) {
            switch (getControlMode()) {
                case ControlMode.CAT://via CAT command
                    getConnector().setPttOn(Yaesu2RigConstant.setPTTState(on));
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
            getConnector().sendData(Yaesu2RigConstant.setOperationUSBMode());
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(Yaesu2RigConstant.setOperationFreq(getFreq()));
        }
    }

    @Override
    public void onReceiveData(byte[] data) {
        //YAESU 817 commands: frequency response is 5 bytes, METER is 2 bytes.
        //Meter is 2 bytes: first byte high nibble=power 0-A, low nibble=ALC 0-9; second byte high nibble=SWR 0-C (0=high SWR), low nibble=audio input 0-8
        if (data.length == 5) {//frequency
            long freq = Yaesu2Command.getFrequency(data);
            if (freq > -1) {
                setFreq(freq);
            }
        } else if (data.length == 2) {//METERS
            alc = (data[0] & 0x0f);
            swr = (data[1] & 0x0f0) >> 4;
            showAlert();
            notifyMeterData(alc * 17, swr * 17);
        }

    }

    /**
     * Read Meter RM;
     */
    private void readMeters() {
        if (getConnector() != null) {
            getConnector().sendData(Yaesu2RigConstant.readMeter());
        }
    }

    private void showAlert() {
        if ((swr > Yaesu2RigConstant.swr_817_alert_min)
                && GeneralVariables.swr_switch_on) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }
        if ((alc >= Yaesu2RigConstant.alc_817_alert_max)
                && GeneralVariables.alc_switch_on) {//ALC alert
            if (!alcMaxAlert) {
                alcMaxAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_high_alert));
            }
        } else {
            alcMaxAlert = false;
        }

    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            //clearBuffer();//clear buffer
            getConnector().sendData(Yaesu2RigConstant.setReadOperationFreq());
        }
    }

    @Override
    public String getName() {
        return "YAESU 817 series";
    }

    public Yaesu2Rig() {
        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY, QUERY_FREQ_TIMEOUT);
    }

}
