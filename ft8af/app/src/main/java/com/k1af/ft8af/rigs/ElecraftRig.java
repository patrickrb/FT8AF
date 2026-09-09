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

        // Drain every complete ';'-terminated command in this read. The transport
        // delivers bytes in arbitrary chunks, so a single read routinely coalesces
        // two back-to-back replies (e.g. successive SWR meter polls during TX, or a
        // freq reply followed by a meter reply). The old parser handled only the
        // first command and re-buffered the rest WITH its retained terminator, so
        // the second reply was dropped (then wiped by the next poll's
        // clearBufferData()) and the retained ';' poisoned the next parse (the
        // stale ID was read as the command and the real command landed in the data
        // field). Only the trailing, unterminated fragment is carried over.
        CatLineSplitter.Result result = CatLineSplitter.split(buffer.toString(), s, ';');
        clearBufferData();
        buffer.append(result.remainder);
        // A runaway unterminated buffer must not grow without bound.
        if (buffer.length() > 1000) clearBufferData();

        for (String command : result.frames) {
            processCommand(command);
        }
    }

    /**
     * Parse and act on one complete CAT command (terminator already stripped).
     * Extracted so {@link #onReceiveData} stays a thin drain loop.
     */
    private void processCommand(String command) {
        ElecraftCommand elecraftCommand = ElecraftCommand.getCommand(command);
        if (elecraftCommand == null) {
            return;
        }
        if (elecraftCommand.getCommandID().equalsIgnoreCase("FA")
                || elecraftCommand.getCommandID().equalsIgnoreCase("FB")) {
            long tempFreq = ElecraftCommand.getFrequency(elecraftCommand);
            if (tempFreq != 0) {//if tempFreq==0, frequency is invalid
                setFreq(tempFreq);
            }
        } else if (elecraftCommand.getCommandID().equalsIgnoreCase("SW")) {//METER
            if (ElecraftCommand.isSWRMeter(elecraftCommand)) {
                swr = ElecraftCommand.getSWRMeter(elecraftCommand);
            }

            showAlert();
            notifyMeterData(-1, Math.min(swr * 4, 255));
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
