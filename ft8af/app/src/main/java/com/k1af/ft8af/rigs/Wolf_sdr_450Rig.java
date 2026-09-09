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
 * Wolf SDR CAT command set is compatible with YAESU 450D, but some hams found in testing that 450D defaults to DIG-U mode, which cannot transmit at full power on Wolf SDR.
 * Using USB mode allows full power transmission, so a USB mode option has been added.
 * When creating the rig, use a boolean parameter to specify USB mode.
 */
public class Wolf_sdr_450Rig extends BaseRig {
    private static final String TAG = "Wolf_sdr_450Rig";
    private final StringBuilder buffer = new StringBuilder();
    private int swr = 0;
    private int alc = 0;
    private boolean alcMaxAlert = false;
    private boolean swrAlert = false;

    private Timer readFreqTimer = new Timer();
    private boolean isUsbMode = true;


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
                    getConnector().setPttOn(Yaesu3RigConstant.setPTT_TX_On(on));//for YAESU 450 command
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
            //getConnector().sendData(Yaesu3RigConstant.setOperationDATA_U_Mode());
            //getConnector().sendData(Yaesu3RigConstant.setOperationUSB_Data_Mode());
            if (isUsbMode) {//USB mode
                getConnector().sendData(Yaesu3RigConstant.setOperationUSBMode());
            } else {//DIG-U mode
                getConnector().sendData(Yaesu3RigConstant.setOperationDATA_U_Mode());
            }
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
        // Drain every complete ';'-terminated command in this read. The meter
        // poll sends the ALC and SWR reads back-to-back, so the transport
        // routinely coalesces both replies ("RM4nnn;RM6nnn;") into one chunk.
        // The old parser consumed only the first command and re-buffered the
        // rest with its terminator retained, where the next poll's
        // clearBufferData() wiped it -- permanently losing the SWR reading (so
        // the high-SWR safety alert never fires during TX). Only the trailing,
        // unterminated fragment is carried into the next call. Shared with the
        // other Yaesu gen-3 rigs via CatLineSplitter.
        CatLineSplitter.Result result = CatLineSplitter.split(buffer.toString(), new String(data), ';');
        clearBufferData();
        buffer.append(result.remainder);
        // A runaway unterminated buffer must not grow without bound.
        if (buffer.length() > 1000) clearBufferData();

        for (String command : result.frames) {
            processCommand(command);
        }
    }

    /**
     * Parse and act on one complete ';'-terminated command (terminator already
     * stripped). Extracted so {@link #onReceiveData} stays a thin drain loop.
     */
    private void processCommand(String command) {
        Yaesu3Command yaesu3Command = Yaesu3Command.getCommand(command);
        if (yaesu3Command == null) {
            return;
        }
        if (yaesu3Command.getCommandID().equalsIgnoreCase("FA")
                || yaesu3Command.getCommandID().equalsIgnoreCase("FB")) {
            long tempFreq = Yaesu3Command.getFrequency(yaesu3Command);
            if (tempFreq != 0) {//if tempFreq==0, frequency is invalid
                setFreq(tempFreq);
            }
        } else if (yaesu3Command.getCommandID().equalsIgnoreCase("RM")) {//METER
            if (Yaesu3Command.isSWRMeter38(yaesu3Command)) {
                swr = Yaesu3Command.getALCOrSWR38(yaesu3Command);
            }
            if (Yaesu3Command.isALCMeter38(yaesu3Command)) {
                alc = Yaesu3Command.getALCOrSWR38(yaesu3Command);
            }
            showAlert();
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
        return "WOLF SDR";
    }

    public Wolf_sdr_450Rig(boolean usbMode) {
        isUsbMode = usbMode;
        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY, QUERY_FREQ_TIMEOUT);
    }
}
