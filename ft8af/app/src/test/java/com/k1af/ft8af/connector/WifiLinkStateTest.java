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
}
