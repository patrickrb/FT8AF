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
 * FLEX 6000 series. Replies are {@code ';'}-terminated {@code ZZ}-prefixed
 * commands (e.g. {@code ZZFA00014074000;}); parsed via {@link Flex6000Command}.
 */
public class Flex6000Rig extends BaseRig {
    private static final String TAG = "KenwoodTS590Rig";
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
                    getConnector().setPttOn(Flex6000RigConstant.setPTTState(on));
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
            getConnector().sendData(Flex6000RigConstant.setOperationUSB_DIGI_Mode());
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(Flex6000RigConstant.setOperationFreq(getFreq()));
        }
    }

    @Override
    public void onReceiveData(byte[] data) {
        String s = new String(data);

        // FlexRadio 6000 frames every reply as <ID><data>';' (e.g. ZZFA00014074000;).
        // The old parser consumed only the FIRST ';'-terminated command in a read
        // and re-buffered the rest WITH its terminator, where the next poll's
        // clearBufferData() wiped it -- so when the network/serial transport
        // coalesced two replies into one read (common on the TCP SmartSDR link),
        // the second frequency reply was permanently lost and the retained
        // terminator poisoned the next parse. Drain every complete ';'-terminated
        // command in this read and carry only the trailing, unterminated fragment.
        CatLineSplitter.Result result = CatLineSplitter.split(buffer.toString(), s, ';');
        clearBufferData();
        buffer.append(result.remainder);
        // A runaway unterminated buffer must not grow without bound.
        if (buffer.length() > 1000) clearBufferData();

        for (String frame : result.frames) {
            long freq = frequencyFromFrame(frame);
            if (freq != -1) {
                setFreq(freq);
            }
        }
    }

    /**
     * Pure dispatch for a single drained command frame: the frequency this frame
     * asks the rig to tune to, or {@code -1} when the frame carries no valid
     * frequency update (unparseable frame, a non-ZZFA command, or an invalid
     * {@code 0} read-back). Extracted so the coalesced-drain and freq-selection
     * logic is unit-testable without rig/Android state.
     */
    static long frequencyFromFrame(String frame) {
        Flex6000Command flex6000Command = Flex6000Command.getCommand(frame);
        if (flex6000Command == null) {
            return -1;
        }
        if (flex6000Command.getCommandID().equalsIgnoreCase("ZZFA")) {
            long tempFreq = Flex6000Command.getFrequency(flex6000Command);
            if (tempFreq != 0) {//if tempFreq==0, frequency is invalid
                return tempFreq;
            }
        }
        return -1;
    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            clearBufferData();//clear buffer
            getConnector().sendData(Flex6000RigConstant.setReadOperationFreq());
        }
    }

    @Override
    public String getName() {
        return "FLEX 6000 series";
    }

    public Flex6000Rig() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (getConnector()!=null){//switch to VFO A
                    //getConnector().sendData(Flex6000RigConstant.setVFOMode());
                }
            }
        },START_QUERY_FREQ_DELAY-500);

        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY,QUERY_FREQ_TIMEOUT);
    }
}
