package com.k1af.ft8af.rigs;

import static com.k1af.ft8af.GeneralVariables.QUERY_FREQ_TIMEOUT;
import static com.k1af.ft8af.GeneralVariables.START_QUERY_FREQ_DELAY;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.ui.ToastMessage;

import java.util.Timer;
import java.util.TimerTask;

/**
 * KENWOOD TS590, similar to YAESU gen-3 commands. Uses Yaesu3Command structure, commands in KenwoodTK90RigConstant.
 */
public class KenwoodTS570Rig extends BaseRig {
    private static final String TAG = "KenwoodTS570Rig";
    private final StringBuilder buffer = new StringBuilder();

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

    /**
     * Read Meter RM;
     */
    private void readMeters() {
        if (getConnector() != null) {
            clearBufferData();//clear buffer
            getConnector().sendData(KenwoodTK90RigConstant.setRead590Meters());
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
                    getConnector().setPttOn(KenwoodTK90RigConstant.setTS570PTTState(on));
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
            getConnector().sendData(KenwoodTK90RigConstant.setTS590OperationUSBMode());
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(KenwoodTK90RigConstant.setTS590OperationFreq(getFreq()));
        }
    }

    @Override
    public void onReceiveData(byte[] data) {
        String s = new String(data);

        // Kenwood frames every command and reply with ';' (e.g. the freq reply
        // FA...; and the meter reply RM...;). The old code split on '\r', which
        // Kenwood never sends, so no reply ever parsed -- frequency read-back and
        // SWR/ALC protection were dead. Drain every complete ';'/CR-terminated
        // command in this read (the RM poll answers SWR then ALC back-to-back, so
        // both replies routinely coalesce into one read) and carry only the
        // trailing, unterminated fragment into the next call.
        CatLineSplitter.Result result = CatLineSplitter.split(buffer.toString(), s, ";\r");
        clearBufferData();
        buffer.append(result.remainder);
        // A runaway unterminated buffer must not grow without bound.
        if (buffer.length() > 1000) clearBufferData();

        for (String frame : result.frames) {
            processCommand(frame);
        }
    }

    private void processCommand(String frame) {
        Yaesu3Command yaesu3Command = Yaesu3Command.getCommand(frame);
        if (yaesu3Command == null) {
            return;
        }
        String cmd = yaesu3Command.getCommandID();
        if (cmd.equalsIgnoreCase("FA")) {//frequency
            long tempFreq = Yaesu3Command.getFrequency(yaesu3Command);
            if (tempFreq != 0) {//if tempFreq==0, frequency is invalid
                setFreq(tempFreq);
            }
        } else if (cmd.equalsIgnoreCase("RM")) {//meter
            if (Yaesu3Command.is590MeterSWR(yaesu3Command)) {
                swr = Yaesu3Command.get590ALCOrSWR(yaesu3Command);
            }
            if (Yaesu3Command.is590MeterALC(yaesu3Command)) {
                alc = Yaesu3Command.get590ALCOrSWR(yaesu3Command);
            }
            showAlert();
            notifyMeterData(Math.min(alc * 8, 255), Math.min(swr * 8, 255));
        }
    }

    private void showAlert() {
        if ((swr >= KenwoodTK90RigConstant.ts_590_swr_alert_max)
                && GeneralVariables.swr_switch_on) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }
        if ((alc > KenwoodTK90RigConstant.ts_590_alc_alert_max)
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
            clearBufferData();//clear buffer
            getConnector().sendData(KenwoodTK90RigConstant.setTS590ReadOperationFreq());
        }
    }

    @Override
    public String getName() {
        return "KENWOOD TS-570";
    }

    public KenwoodTS570Rig() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (getConnector() != null) {
                    getConnector().sendData(KenwoodTK90RigConstant.setTS590VFOMode());
                }
            }
        }, START_QUERY_FREQ_DELAY - 500);
        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY, QUERY_FREQ_TIMEOUT);
    }
}
