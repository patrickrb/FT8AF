package com.k1af.ft8af.rigs;

import static com.k1af.ft8af.GeneralVariables.QUERY_FREQ_TIMEOUT;
import static com.k1af.ft8af.GeneralVariables.START_QUERY_FREQ_DELAY;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Rig control over a Hamlib {@code rigctld} daemon reached across the network.
 *
 * <p>CAT (frequency, PTT, mode) travels over a TCP socket to rigctld using the
 * text protocol built by {@link HamlibRigctl}; rigctld in turn drives whatever
 * radio it was launched against, so this one backend covers every Hamlib rig.
 * Only CAT crosses the link — audio still flows through the local sound path
 * (USB sound card / AudioTrack), exactly like a wired CAT rig — which is why
 * {@link #usesNetworkAudio()} stays {@code false}.</p>
 */
public class HamlibNetRig extends BaseRig {
    private static final String TAG = "HamlibNetRig";

    private final StringBuilder buffer = new StringBuilder();
    private Timer readFreqTimer = new Timer();

    public HamlibNetRig() {
        Log.d(TAG, "HamlibNetRig: create.");
        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY, QUERY_FREQ_TIMEOUT);
    }

    private TimerTask readTask() {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    // Hamlib has no meter read here; only poll the dial when
                    // idle. READ_METERS (PTT on) falls through to no-op so we
                    // never poll while transmitting.
                    if (ReadTaskAction.decide(isConnected(), isPttOn())
                            == ReadTaskAction.Action.READ_FREQ) {
                        readFreqFromRig();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "readFreq error:" + e.getMessage());
                }
            }
        };
    }

    @Override
    public void onDisconnecting() {
        if (readFreqTimer != null) {
            readFreqTimer.cancel();
            readFreqTimer.purge();
            readFreqTimer = null;
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
    public void setPTT(boolean on) {
        super.setPTT(on);
        if (getConnector() != null) {
            getConnector().sendData(HamlibRigctl.setPtt(on));
        }
    }

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            getConnector().sendData(HamlibRigctl.setDataUsbMode());
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(HamlibRigctl.setFreq(getFreq()));
        }
    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            getConnector().sendData(HamlibRigctl.readFreq());
        }
    }

    @Override
    public void onReceiveData(byte[] data) {
        buffer.append(new String(data, StandardCharsets.US_ASCII));
        // Guard against an endless partial line if the peer never sends a newline.
        if (buffer.length() > 1000) {
            buffer.setLength(0);
            return;
        }
        HamlibRigctl.Scan scan = HamlibRigctl.scanLines(buffer.toString());
        buffer.setLength(0);
        buffer.append(scan.remainder);
        for (String line : scan.lines) {
            handleLine(line);
        }
    }

    /**
     * Dispatch one complete reply line. A bare frequency updates the dial (and,
     * via {@link BaseRig#setFreq(long)}, feeds the CAT-liveness watchdog); an
     * {@code RPRT} report just proves the link is alive.
     */
    private void handleLine(String line) {
        long freq = HamlibRigctl.parseFreqReply(line);
        if (freq > 0) {
            setFreq(freq);
            return;
        }
        if (HamlibRigctl.parseReport(line) != HamlibRigctl.NO_REPORT
                && getOnRigStateChanged() != null) {
            getOnRigStateChanged().onRigResponded();
        }
    }

    /**
     * Hamlib NET rigctl carries only CAT over TCP; TX/RX audio uses the local
     * sound path just like a wired CAT rig, so audio must never be diverted to
     * the network transmit path.
     */
    @Override
    public boolean usesNetworkAudio() {
        return false;
    }

    @Override
    public String getName() {
        return "Hamlib NET rigctl";
    }
}
