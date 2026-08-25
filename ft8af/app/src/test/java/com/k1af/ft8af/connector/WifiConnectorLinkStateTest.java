package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.icom.WifiRig;
import com.k1af.ft8af.rigs.OnRigStateChanged;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies {@link WifiConnector} forwards the rig's link lifecycle to the shared
 * {@link OnConnectorStateChanged}/{@link OnRigStateChanged} pipeline that drives the CAT
 * status chip (issue #754), and that {@link WifiConnector#isConnected()} reflects the real
 * post-login state rather than {@code wifiRig.opened}. No Android types are touched.
 */
public class WifiConnectorLinkStateTest {

    /** A WifiRig that performs no network I/O; the test drives its link callbacks directly. */
    private static class FakeWifiRig extends WifiRig {
        FakeWifiRig() {
            super("192.168.0.1", 50001, "user", "pw");
        }

        @Override public void start() { opened = true; }
        @Override public void setPttOn(boolean on) { }
        @Override public void sendCivData(byte[] data) { }
        @Override public void sendWaveData(float[] data) { }
        @Override public void close() { opened = false; notifyClosed(); }
    }

    private FakeWifiRig rig;
    private WifiConnector connector;
    private final List<String> events = new ArrayList<>();

    @Before
    public void setUp() {
        rig = new FakeWifiRig();
        connector = new WifiConnector(ControlMode.CAT, rig);
        connector.setOnRigStateChanged(new OnRigStateChanged() {
            @Override public void onConnected() { events.add("connected"); }
            @Override public void onDisconnected() { events.add("disconnected"); }
            @Override public void onConnecting() { events.add("connecting"); }
            @Override public void onRunError(String message) { events.add("error:" + message); }
            @Override public void onPttChanged(boolean isOn) { }
            @Override public void onFreqChanged(long freq) { }
        });
    }

    @Test
    public void connect_isNotConnectedUntilLogin() {
        connector.connect();
        assertThat(events).containsExactly("connecting");
        // wifiRig.opened is already true here — the old isConnected() would have lied.
        assertThat(rig.opened).isTrue();
        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    public void loginSuccess_emitsConnectedAndReportsConnected() {
        connector.connect();
        rig.onLinkStateChanged.onLoginResult(true);
        assertThat(events).containsExactly("connecting", "connected").inOrder();
        assertThat(connector.isConnected()).isTrue();
        // Duplicate login packets don't re-announce.
        rig.onLinkStateChanged.onLoginResult(true);
        assertThat(events).containsExactly("connecting", "connected").inOrder();
    }

    @Test
    public void loginFailure_emitsError() {
        connector.connect();
        rig.onLinkStateChanged.onLoginResult(false);
        assertThat(events).containsExactly("connecting", "error:login failed").inOrder();
        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    public void linkDropAfterConnect_emitsDisconnectedOnce() {
        connector.connect();
        rig.onLinkStateChanged.onLoginResult(true);
        rig.onLinkStateChanged.onSendError(); // network went away
        rig.close();                          // teardown that follows
        assertThat(events).containsExactly("connecting", "connected", "disconnected").inOrder();
        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    public void userDisconnect_emitsDisconnected() {
        connector.connect();
        rig.onLinkStateChanged.onLoginResult(true);
        connector.disconnect(); // -> wifiRig.close() -> notifyClosed()
        assertThat(events).containsExactly("connecting", "connected", "disconnected").inOrder();
    }
}
