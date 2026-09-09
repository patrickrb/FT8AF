package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.icom.WifiRig;
import com.k1af.ft8af.rigs.OnRigStateChanged;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WifiConnector} + {@link WifiRig} session wiring (Copilot review on #778): the
 * connector's per-attempt link state is reset on the rig's {@code onSessionBegin} edge — the
 * same instant the session counter advances — and every login/send-error event is admitted
 * by session id, so a late event from the previous UDP session can neither falsely connect
 * nor terminally fail a fresh attempt. Robolectric only because {@code WifiConnector} touches
 * {@code android.util.Log}; no sockets are opened.
 */
@RunWith(RobolectricTestRunner.class)
public class WifiConnectorSessionTest {

    /** A rig whose start() begins a session but opens nothing; events are pushed by hand. */
    private static class FakeRig extends WifiRig {
        int lastSession;

        FakeRig() {
            super("192.168.0.1", 50001, "user", "pw");
        }

        @Override public void start() { lastSession = beginLinkSession(); opened = true; }
        @Override public void setPttOn(boolean on) { }
        @Override public void sendCivData(byte[] data) { }
        @Override public void sendWaveData(float[] data) { }
        @Override public void close() { opened = false; notifyClosed(); }

        void login(int session, boolean ok) { notifyLoginResult(session, ok); }
        void sendError(int session) { notifySendError(session); }
    }

    private static List<String> record(WifiConnector c) {
        final List<String> events = new ArrayList<>();
        c.setOnRigStateChanged(new OnRigStateChanged() {
            @Override public void onDisconnected() { events.add("disconnected"); }
            @Override public void onConnected() { events.add("connected"); }
            @Override public void onPttChanged(boolean isOn) { }
            @Override public void onFreqChanged(long freq) { }
            @Override public void onRunError(String message) { events.add("error:" + message); }
            @Override public void onConnecting() { events.add("connecting"); }
        });
        return events;
    }

    @Test
    public void connect_thenLogin_connects() {
        FakeRig rig = new FakeRig();
        WifiConnector c = new WifiConnector(ControlMode.CAT, rig);
        List<String> events = record(c);
        c.connect();
        rig.login(rig.lastSession, true);
        assertThat(events).containsExactly("connecting", "connected").inOrder();
        assertThat(c.isConnected()).isTrue();
    }

    @Test
    public void reconnect_staleLoginFromOldSession_cannotConnectTheNewAttempt() {
        FakeRig rig = new FakeRig();
        WifiConnector c = new WifiConnector(ControlMode.CAT, rig);
        List<String> events = record(c);
        c.connect();
        int old = rig.lastSession;
        c.disconnect();
        c.connect();
        // A late 0x60 from the old sockets, delivered straight to the link state as if it
        // had slipped past the rig's own check: must not light the chip.
        rig.login(old, true);
        assertThat(c.isConnected()).isFalse();
        assertThat(events).containsExactly("connecting", "connecting").inOrder();
        // The real login for the new session still connects.
        rig.login(rig.lastSession, true);
        assertThat(c.isConnected()).isTrue();
        assertThat(events).containsExactly("connecting", "connecting", "connected").inOrder();
    }

    @Test
    public void reconnect_staleFailureFromOldSession_doesNotSwallowTheNewLogin() {
        FakeRig rig = new FakeRig();
        WifiConnector c = new WifiConnector(ControlMode.CAT, rig);
        List<String> events = record(c);
        c.connect();
        int old = rig.lastSession;
        c.disconnect();
        c.connect();
        rig.login(old, false);       // late failed login from the old sockets
        rig.sendError(old);          // late sender failure from the old streams
        assertThat(events).doesNotContain("error:login failed");
        assertThat(events).doesNotContain("error:network send error");
        rig.login(rig.lastSession, true);
        assertThat(c.isConnected()).isTrue();
    }

    @Test
    public void reconnect_afterDropWithoutDisconnect_startsFreshState() {
        // Tap-to-reconnect after a link drop reuses the connector without disconnect():
        // the terminal flag from the drop must not swallow the new login (#754), and the
        // reset now rides on the session edge rather than preceding start().
        FakeRig rig = new FakeRig();
        WifiConnector c = new WifiConnector(ControlMode.CAT, rig);
        List<String> events = record(c);
        c.connect();
        rig.login(rig.lastSession, true);
        rig.sendError(rig.lastSession);
        assertThat(events).containsExactly("connecting", "connected", "disconnected").inOrder();
        c.connect();
        rig.login(rig.lastSession, true);
        assertThat(c.isConnected()).isTrue();
        assertThat(events).containsExactly("connecting", "connected", "disconnected",
                "connecting", "connected").inOrder();
    }
}
