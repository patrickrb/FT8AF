package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link BaseRig#setFreq(long)} (rig-reported) is a CAT liveness signal;
 * {@link BaseRig#setCommandedFreq(long)} (app-commanded) is not. Robolectric because
 * BaseRig publishes the dial through a {@code MutableLiveData}.
 */
@RunWith(RobolectricTestRunner.class)
public class BaseRigFreqSignalTest {

    /** Minimal concrete rig: no transport, just the BaseRig bookkeeping under test. */
    private static final class StubRig extends BaseRig {
        @Override public boolean isConnected() { return true; }
        @Override public void setUsbModeToRig() {}
        @Override public void setFreqToRig() {}
        @Override public void onReceiveData(byte[] data) {}
        @Override public void readFreqFromRig() {}
        @Override public String getName() { return "stub"; }
    }

    private final List<String> events = new ArrayList<>();
    private StubRig rig;

    @Before
    public void setUp() {
        rig = new StubRig();
        rig.setOnRigStateChanged(new OnRigStateChanged() {
            @Override public void onDisconnected() {}
            @Override public void onConnected() {}
            @Override public void onPttChanged(boolean isOn) {}
            @Override public void onFreqChanged(long freq) { events.add("freq:" + freq); }
            @Override public void onRunError(String message) {}
            @Override public void onRigResponded() { events.add("responded"); }
        });
    }

    @Test
    public void rigReportedFreq_isLivenessSignal_evenWhenDialUnchanged() {
        rig.setFreq(14_074_000L);
        assertThat(events).containsExactly("responded", "freq:14074000").inOrder();
        events.clear();
        // Same dial again: no onFreqChanged, but the reply still proves the link is alive.
        rig.setFreq(14_074_000L);
        assertThat(events).containsExactly("responded");
    }

    @Test
    public void appCommandedFreq_isNotALivenessSignal() {
        // The #781 bug: setOperationBand pushed the app's own target through setFreq,
        // which armed the watchdog as if the rig had answered.
        rig.setCommandedFreq(7_074_000L);
        assertThat(events).containsExactly("freq:7074000");
        assertThat(rig.getFreq()).isEqualTo(7_074_000L);
        events.clear();
        rig.setCommandedFreq(7_074_000L);
        assertThat(events).isEmpty();
    }

    @Test
    public void invalidFreqs_areIgnoredByBothPaths() {
        rig.setFreq(0);
        rig.setFreq(-1);
        rig.setCommandedFreq(0);
        rig.setCommandedFreq(-1);
        assertThat(events).isEmpty();
        assertThat(rig.getFreq()).isEqualTo(0);
    }

    @Test
    public void commandedThenReported_sameDial_stillCountsAsResponse() {
        // Typical sequence: app pushes 21.074, rig echoes 21.074 back to the FA; probe.
        rig.setCommandedFreq(21_074_000L);
        events.clear();
        rig.setFreq(21_074_000L);
        assertThat(events).containsExactly("responded");
    }
}
