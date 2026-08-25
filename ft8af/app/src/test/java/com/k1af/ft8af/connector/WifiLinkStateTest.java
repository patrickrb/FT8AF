package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link WifiLinkState} (issue #754): the edge-guard that turns the Icom UDP
 * stack's repeated login/status packets and its send-error/close events into single
 * connect/disconnect edges for the CAT status chip. Pure JUnit.
 */
public class WifiLinkStateTest {

    @Test
    public void loginOk_emitsConnectedOnce() {
        WifiLinkState s = new WifiLinkState();
        assertThat(s.onLoginResult(true)).isEqualTo(WifiLinkState.Emit.CONNECTED);
        assertThat(s.isConnected()).isTrue();
        // The rig re-sends 0x60/0x50; further successes must not re-announce.
        assertThat(s.onLoginResult(true)).isNull();
        assertThat(s.onLoginResult(true)).isNull();
        assertThat(s.isConnected()).isTrue();
    }

    @Test
    public void loginFail_isTerminalError() {
        WifiLinkState s = new WifiLinkState();
        assertThat(s.onLoginResult(false)).isEqualTo(WifiLinkState.Emit.ERROR);
        assertThat(s.isConnected()).isFalse();
        // Nothing after a terminal failure.
        assertThat(s.onLoginResult(true)).isNull();
        assertThat(s.onClosed()).isNull();
    }

    @Test
    public void sendErrorAfterConnect_isDisconnect() {
        WifiLinkState s = new WifiLinkState();
        s.onLoginResult(true);
        assertThat(s.onSendError()).isEqualTo(WifiLinkState.Emit.DISCONNECTED);
        assertThat(s.isConnected()).isFalse();
    }

    @Test
    public void sendErrorBeforeConnect_isError() {
        WifiLinkState s = new WifiLinkState();
        assertThat(s.onSendError()).isEqualTo(WifiLinkState.Emit.ERROR);
        assertThat(s.isConnected()).isFalse();
    }

    @Test
    public void sendErrorThenClose_emitsOnlyOnce() {
        // OnUdpSendIOException calls notifySendError() then close() -> notifyClosed():
        // the disconnect must be announced once, not twice.
        WifiLinkState s = new WifiLinkState();
        s.onLoginResult(true);
        assertThat(s.onSendError()).isEqualTo(WifiLinkState.Emit.DISCONNECTED);
        assertThat(s.onClosed()).isNull();
    }

    @Test
    public void userCloseAfterConnect_isDisconnect() {
        WifiLinkState s = new WifiLinkState();
        s.onLoginResult(true);
        assertThat(s.onClosed()).isEqualTo(WifiLinkState.Emit.DISCONNECTED);
        assertThat(s.isConnected()).isFalse();
    }

    @Test
    public void closeBeforeConnect_isSilent() {
        // Dismissing the login dialog before the handshake completes shouldn't flash a
        // "disconnected" the chip never showed connected for.
        WifiLinkState s = new WifiLinkState();
        assertThat(s.onClosed()).isNull();
        assertThat(s.isConnected()).isFalse();
    }

    @Test
    public void reset_clearsTerminalSoNextLoginConnects() {
        // Reconnect reuses the connector (and this state): after a drop the next login
        // must announce CONNECTED again instead of being swallowed by the stale terminal flag.
        WifiLinkState s = new WifiLinkState();
        s.onLoginResult(true);
        assertThat(s.onSendError()).isEqualTo(WifiLinkState.Emit.DISCONNECTED);
        assertThat(s.onLoginResult(true)).isNull(); // still terminal without reset
        s.reset();
        assertThat(s.isConnected()).isFalse();
        assertThat(s.onLoginResult(true)).isEqualTo(WifiLinkState.Emit.CONNECTED);
        assertThat(s.isConnected()).isTrue();
    }

    @Test
    public void reset_afterLoginFailure_allowsRetry() {
        WifiLinkState s = new WifiLinkState();
        assertThat(s.onLoginResult(false)).isEqualTo(WifiLinkState.Emit.ERROR);
        s.reset();
        assertThat(s.onLoginResult(true)).isEqualTo(WifiLinkState.Emit.CONNECTED);
    }

    @Test
    public void reset_afterUserClose_allowsReconnect() {
        WifiLinkState s = new WifiLinkState();
        s.onLoginResult(true);
        assertThat(s.onClosed()).isEqualTo(WifiLinkState.Emit.DISCONNECTED);
        s.reset();
        assertThat(s.onLoginResult(true)).isEqualTo(WifiLinkState.Emit.CONNECTED);
        assertThat(s.onClosed()).isEqualTo(WifiLinkState.Emit.DISCONNECTED);
    }

    @Test
    public void concurrentLogins_announceConnectedExactlyOnce() throws Exception {
        // The rig re-fires 0x60/0x50 and the receive worker isn't the only caller; with
        // unsynchronized transitions two threads could both see announcedConnected == false
        // and emit two CONNECTED edges. Hammer it and count.
        for (int round = 0; round < 50; round++) {
            final WifiLinkState s = new WifiLinkState();
            final int threads = 8;
            final java.util.concurrent.CountDownLatch go =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger connected =
                    new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger disconnected =
                    new java.util.concurrent.atomic.AtomicInteger();
            Thread[] ts = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                final boolean closer = (i == threads - 1);
                ts[i] = new Thread(() -> {
                    try { go.await(); } catch (InterruptedException ignored) { return; }
                    WifiLinkState.Emit e = closer ? s.onClosed() : s.onLoginResult(true);
                    if (e == WifiLinkState.Emit.CONNECTED) connected.incrementAndGet();
                    if (e == WifiLinkState.Emit.DISCONNECTED) disconnected.incrementAndGet();
                });
                ts[i].start();
            }
            go.countDown();
            for (Thread t : ts) t.join();
            assertThat(connected.get()).isAtMost(1);
            assertThat(disconnected.get()).isAtMost(1);
            // A close never leaves the chip lit, whichever order the threads won.
            assertThat(s.isConnected()).isFalse();
            // DISCONNECTED can only follow a CONNECTED.
            if (disconnected.get() == 1) assertThat(connected.get()).isEqualTo(1);
        }
    }
}
