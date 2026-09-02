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
        boolean admit(int session, Runnable teardown) { return admitSessionEvent(session, teardown); }
        boolean current(int session) { return isCurrentSession(session); }
    }

    private static List<String> record(FakeRig rig) {
        final List<String> events = new ArrayList<>();
        rig.setOnLinkStateChanged(new WifiRig.OnLinkStateChanged() {
            @Override public void onSessionBegin(int session) { events.add("begin:" + session); }
            @Override public void onLoginResult(int session, boolean ok) { events.add("login:" + ok + "@" + session); }
            @Override public void onSendError(int session) { events.add("sendError@" + session); }
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
    public void currentSessionEvents_areDelivered_taggedWithTheirSession() {
        FakeRig rig = new FakeRig();
        List<String> events = record(rig);
        rig.start();
        int s = rig.lastSession;
        rig.login(s, true);
        rig.sendError(s);
        assertThat(events).containsExactly("begin:" + s, "login:true@" + s, "sendError@" + s)
                .inOrder();
    }

    @Test
    public void staleSessionEvents_areDropped() {
        FakeRig rig = new FakeRig();
        List<String> events = record(rig);
        rig.start();
        int old = rig.lastSession;
        rig.close();                 // link dropped / user disconnect
        rig.start();                 // reconnect: new ControlUdp, new session
        int fresh = rig.lastSession;
        rig.login(old, true);        // late 0x60 from the old sockets
        rig.sendError(old);          // late sender failure from the old streams
        rig.login(old, false);
        assertThat(events).containsExactly("begin:" + old, "closed", "begin:" + fresh).inOrder();
        // The new session's own events still get through.
        rig.login(fresh, true);
        assertThat(events).containsExactly("begin:" + old, "closed", "begin:" + fresh,
                "login:true@" + fresh).inOrder();
    }

    @Test
    public void sessionBegin_isAnnouncedAsTheCounterAdvances() {
        // Copilot review on #778: the connector resets its per-attempt state in
        // onSessionBegin(), so the reset and the session advance are one edge — no window
        // in which an old-session event still matches the current id.
        FakeRig rig = new FakeRig();
        List<String> events = record(rig);
        rig.start();
        assertThat(events).containsExactly("begin:" + rig.currentLinkSession());
        rig.start();
        assertThat(events).containsExactly("begin:1", "begin:2").inOrder();
    }

    @Test
    public void unsessionedNotifiers_applyToTheCurrentSession() {
        // The original overloads remain for callers that have no session to carry
        // (close() -> notifyClosed(), and any third-party rig subclass).
        FakeRig rig = new FakeRig();
        List<String> events = record(rig);
        rig.start();
        int s = rig.lastSession;
        rig.notifyLoginResult(true);
        rig.notifySendError();
        rig.notifyClosed();
        assertThat(events).containsExactly("begin:" + s, "login:true@" + s, "sendError@" + s,
                "closed").inOrder();
    }

    @Test
    public void isCurrentSession_instanceForm() {
        FakeRig rig = new FakeRig();
        rig.start();
        int old = rig.lastSession;
        assertThat(rig.current(old)).isTrue();
        rig.start();
        assertThat(rig.current(old)).isFalse();
        assertThat(rig.current(rig.lastSession)).isTrue();
    }

    @Test
    public void admitSessionEvent_currentSession_admitsWithoutTeardown() {
        FakeRig rig = new FakeRig();
        rig.start();
        final int[] torn = {0};
        assertThat(rig.admit(rig.lastSession, () -> torn[0]++)).isTrue();
        assertThat(torn[0]).isEqualTo(0);
    }

    @Test
    public void admitSessionEvent_staleSession_tearsDownOnlyThatSessionAndRejects() {
        // Copilot review on #778: a late send error / failed login from the old sockets
        // must close *those* sockets, not the reconnected session's controlUdp.
        FakeRig rig = new FakeRig();
        List<String> events = record(rig);
        rig.start();
        int old = rig.lastSession;
        rig.start();
        final int[] torn = {0};
        assertThat(rig.admit(old, () -> torn[0]++)).isFalse();
        assertThat(torn[0]).isEqualTo(1);
        // Nothing reached the listener for the stale session, and the rig wasn't closed.
        assertThat(events).doesNotContain("closed");
        assertThat(rig.opened).isTrue();
    }

    @Test
    public void admitSessionEvent_staleTeardownFailure_isSwallowed() {
        // closeAll() on sockets that are already gone can throw; the stale branch must
        // not let that escape onto the UDP receive worker.
        FakeRig rig = new FakeRig();
        rig.start();
        int old = rig.lastSession;
        rig.start();
        assertThat(rig.admit(old, () -> { throw new IllegalStateException("closed"); }))
                .isFalse();
        assertThat(rig.admit(old, null)).isFalse();
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
