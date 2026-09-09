package com.k1af.ft8af.rigs;

import static com.k1af.ft8af.GeneralVariables.QUERY_FREQ_TIMEOUT;
import static com.k1af.ft8af.GeneralVariables.START_QUERY_FREQ_DELAY;

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.ui.ToastMessage;

import java.util.ArrayList;
import java.util.List;
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
        buffer.append(new String(data));

        // Drain EVERY complete ';'-terminated command, keeping only the
        // unterminated tail. The meter poll sends two reads back-to-back
        // (RM4; ALC then RM6; SWR), so the transport frequently coalesces both
        // replies into one chunk ("RM4nnn;RM6nnn;"). The old parser handled only
        // the first command and re-buffered the rest with its terminator, where
        // the next poll's clearBufferData() wiped it — permanently losing the
        // SWR reading. See splitCommands / processCommand.
        Frames frames = splitCommands(buffer.toString());
        clearBufferData();
        buffer.append(frames.remainder);
        // Guard against unbounded growth if a terminator never arrives.
        if (buffer.length() > 1000) clearBufferData();

        for (String command : frames.commands) {
            processCommand(command);
        }
    }

    /**
     * Parse and act on a single ';'-terminated command (terminator stripped).
     * Extracted so {@link #onReceiveData} stays a thin drain loop.
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
            notifyMeterData(alc, swr);
        }
    }

    /** Complete commands (terminator stripped) plus the trailing unterminated tail. */
    static final class Frames {
        /** Every ';'-terminated command in arrival order, terminator removed. */
        final List<String> commands;
        /** Text after the last ';' — an incomplete command to carry over. */
        final String remainder;

        Frames(List<String> commands, String remainder) {
            this.commands = commands;
            this.remainder = remainder;
        }
    }

    /**
     * Split accumulated CAT text into complete {@code ';'}-terminated commands
     * and the trailing unterminated remainder.
     *
     * <p>Pure logic — no rig or Android state — so it is unit-testable without
     * the Timer-scheduling constructor.
     *
     * @param accumulated buffered text so far (never null; may be empty)
     * @return the complete commands and the bytes to carry into the next read
     */
    static Frames splitCommands(String accumulated) {
        List<String> commands = new ArrayList<>();
        int start = 0;
        int semi;
        while ((semi = accumulated.indexOf(';', start)) >= 0) {
            commands.add(accumulated.substring(start, semi));
            start = semi + 1;
        }
        return new Frames(commands, accumulated.substring(start));
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
