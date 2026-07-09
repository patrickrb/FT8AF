package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.database.ControlMode;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Behavioural coverage for {@link HamlibNetRig}: it must emit the exact rigctld
 * command lines and route bare-frequency replies into the dial while treating
 * {@code RPRT} reports only as liveness. Robolectric because {@link BaseRig}
 * touches {@code MutableLiveData} on {@link BaseRig#setFreq(long)}.
 */
@RunWith(RobolectricTestRunner.class)
public class HamlibNetRigTest {

    /** Connector that records every byte the rig hands it and reports "connected". */
    private static final class CapturingConnector extends BaseRigConnector {
        final List<String> sent = new ArrayList<>();

        CapturingConnector() {
            super(ControlMode.CAT);
        }

        @Override
        public synchronized void sendData(byte[] data) {
            sent.add(new String(data, StandardCharsets.US_ASCII));
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        String last() {
            return sent.get(sent.size() - 1);
        }
    }

    /** OnRigStateChanged that records the callbacks the rig fires. */
    private static final class RecordingState implements OnRigStateChanged {
        Long lastFreq = null;
        Boolean lastPtt = null;
        int respondedCount = 0;

        @Override public void onDisconnected() {}
        @Override public void onConnected() {}
        @Override public void onPttChanged(boolean isOn) { lastPtt = isOn; }
        @Override public void onFreqChanged(long freq) { lastFreq = freq; }
        @Override public void onRunError(String message) {}
        @Override public void onRigResponded() { respondedCount++; }
    }

    private HamlibNetRig newRig(CapturingConnector connector, RecordingState state) {
        HamlibNetRig rig = new HamlibNetRig();
        rig.onDisconnecting();// stop the polling timer so tests see only their own sends
        rig.setOnRigStateChanged(state);
        rig.setConnector(connector);
        return rig;
    }

    @Test
    public void setFreqToRig_sendsFCommand() {
        CapturingConnector connector = new CapturingConnector();
        HamlibNetRig rig = newRig(connector, new RecordingState());
        rig.setFreq(14_074_000L);
        rig.setFreqToRig();
        assertThat(connector.last()).isEqualTo("F 14074000\n");
    }

    @Test
    public void readFreqFromRig_sendsPollCommand() {
        CapturingConnector connector = new CapturingConnector();
        HamlibNetRig rig = newRig(connector, new RecordingState());
        rig.readFreqFromRig();
        assertThat(connector.last()).isEqualTo("f\n");
    }

    @Test
    public void setUsbModeToRig_sendsPktUsbMode() {
        CapturingConnector connector = new CapturingConnector();
        HamlibNetRig rig = newRig(connector, new RecordingState());
        rig.setUsbModeToRig();
        assertThat(connector.last()).isEqualTo("M PKTUSB 0\n");
    }

    @Test
    public void setPtt_sendsCommandAndFiresCallback() {
        CapturingConnector connector = new CapturingConnector();
        RecordingState state = new RecordingState();
        HamlibNetRig rig = newRig(connector, state);
        rig.setPTT(true);
        assertThat(connector.last()).isEqualTo("T 1\n");
        assertThat(state.lastPtt).isTrue();
        assertThat(rig.isPttOn()).isTrue();
        rig.setPTT(false);
        assertThat(connector.last()).isEqualTo("T 0\n");
        assertThat(state.lastPtt).isFalse();
    }

    @Test
    public void onReceiveData_frequencyReplyUpdatesDial() {
        CapturingConnector connector = new CapturingConnector();
        RecordingState state = new RecordingState();
        HamlibNetRig rig = newRig(connector, state);
        rig.onReceiveData("14074000\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(state.lastFreq).isEqualTo(14_074_000L);
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);
    }

    @Test
    public void onReceiveData_reassemblesSplitReply() {
        CapturingConnector connector = new CapturingConnector();
        RecordingState state = new RecordingState();
        HamlibNetRig rig = newRig(connector, state);
        // TCP can split a reply across reads; the rig must buffer until the newline.
        rig.onReceiveData("1407".getBytes(StandardCharsets.US_ASCII));
        assertThat(state.lastFreq).isNull();
        rig.onReceiveData("4000\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(state.lastFreq).isEqualTo(14_074_000L);
    }

    @Test
    public void onReceiveData_reportIsLivenessOnly() {
        CapturingConnector connector = new CapturingConnector();
        RecordingState state = new RecordingState();
        HamlibNetRig rig = newRig(connector, state);
        rig.onReceiveData("RPRT 0\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(state.lastFreq).isNull();// no dial change from a report
        assertThat(state.respondedCount).isEqualTo(1);// but it proves the link is alive
    }

    @Test
    public void usesNetworkAudio_isFalseSoAudioStaysLocal() {
        assertThat(new HamlibNetRig().usesNetworkAudio()).isFalse();
    }
}
