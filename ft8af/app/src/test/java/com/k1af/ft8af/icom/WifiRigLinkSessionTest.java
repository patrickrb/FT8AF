package com.k1af.ft8af.icom;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the link-session guard in {@link WifiRig} (Copilot review on #754): every
 * {@code start()} creates a fresh {@code ControlUdp}, but the previous session's stream-event
 * handlers still point at the rig, so a late login response or send failure from the old
 * sockets must not be reported into the new attempt's link state. Pure JUnit.
 */
public class WifiRigLinkSessionTest {

    /** Minimal rig: start() begins a session the way IComWifiRig/XieGuWifiRig do. */
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

        // Expose the protected session-checked notifiers the concrete rigs' handlers use.
        void login(int session, boolean ok) { notifyLoginResult(session, ok); }
        void sendError(int session) { notifySendError(session); }
    }

    private static List<String> record(FakeRig rig) {
        final List<String> events = new ArrayList<>();
        rig.setOnLinkStateChanged(new WifiRig.OnLinkStateChanged() {
            @Override public void onLoginResult(boolean ok) { events.add("login:" + ok); }
            @Override public void onSendError() { events.add("sendError"); }
            @Override public void onClosed() { events.add("closed"); }
        });
        return events;
    }

    @Test
    public void isCurrentSession_exactMatchOnly() {
        assertThat(WifiRig.isCurrentSession(3, 3)).isTrue();
        assertThat(WifiRig.isCurrentSession(2, 3)).isFalse();
        assertThat(WifiRig.isCurrentSession(4, 3)).isFalse();
    }

    @Test
    public void beginLinkSession_incrementsPerStart() {
        FakeRig rig = new FakeRig();
        assertThat(rig.currentLinkSession()).isEqualTo(0);
        rig.start();
        int first = rig.lastSession;
        assertThat(first).isEqualTo(rig.currentLinkSession());
        rig.start();
        assertThat(rig.lastSession).isEqualTo(first + 1);
        assertThat(rig.currentLinkSession()).isEqualTo(first + 1);
    }

    @Test
    public void currentSessionEvents_areDelivered() {
        FakeRig rig = new FakeRig();
        List<String> events = record(rig);
        rig.start();
        rig.login(rig.lastSession, true);
        rig.sendError(rig.lastSession);
        assertThat(events).containsExactly("login:true", "sendError").inOrder();
    }

    @Test
    public void staleSessionEvents_areDropped() {
        FakeRig rig = new FakeRig();
        List<String> events = record(rig);
        rig.start();
        int old = rig.lastSession;
        rig.close();                 // link dropped / user disconnect
        rig.start();                 // reconnect: new ControlUdp, new session
        rig.login(old, true);        // late 0x60 from the old sockets
        rig.sendError(old);          // late sender failure from the old streams
        rig.login(old, false);
        assertThat(events).containsExactly("closed");
        // The new session's own events still get through.
        rig.login(rig.lastSession, true);
        assertThat(events).containsExactly("closed", "login:true").inOrder();
    }

    @Test
    public void unsessionedNotifiers_stillDeliver() {
        // The original overloads remain for callers that have no session to carry
        // (close() -> notifyClosed(), and any third-party rig subclass).
        FakeRig rig = new FakeRig();
        List<String> events = record(rig);
        rig.notifyLoginResult(true);
        rig.notifySendError();
        rig.notifyClosed();
        assertThat(events).containsExactly("login:true", "sendError", "closed").inOrder();
    }

    @Test
    public void nullListener_isSafe() {
        FakeRig rig = new FakeRig();
        rig.start();
        rig.login(rig.lastSession, true);
        rig.sendError(rig.lastSession);
        rig.close();
        // No NPE is the assertion.
        assertThat(rig.opened).isFalse();
    }
}
