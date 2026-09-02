package com.k1af.ft8af.icom;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Test;

import java.net.DatagramSocket;

/**
 * Regression coverage for the {@link IcomUdpClient} receive-thread teardown race.
 *
 * <p>An Icom/Xiegu Wi-Fi rig reconnect re-opens the UDP stream in place
 * ({@code IcomUdpBase.openStream()} does {@code setActivated(false)} then
 * {@code setActivated(true)}). The previous receive worker used to close and null the
 * <em>shared</em> {@code sendSocket} field on its way out; when that worker was slow to
 * exit, it ran that teardown after the reconnect had already installed a brand-new
 * socket, closing/nulling the new socket and leaving the rig dead until an app restart.
 *
 * <p>The fix gives each worker the socket it owns and forbids it from touching the
 * shared field. These tests drive {@link IcomUdpClient.DoReceiveRunnable} directly (the
 * client starts deactivated, so {@code run()} skips the receive loop and executes only
 * the teardown) — no threads, no timing, fully deterministic.
 *
 * <p>Plain JUnit: {@code DatagramSocket} is pure JDK and the only Android type touched
 * is {@code android.util.Log}, which unit tests stub via {@code returnDefaultValues}.
 */
public class IcomUdpClientReceiveTeardownTest {

    private final DatagramSocket owned;
    private final DatagramSocket replacement;

    public IcomUdpClientReceiveTeardownTest() throws Exception {
        owned = new DatagramSocket();
        replacement = new DatagramSocket();
    }

    @After
    public void tearDown() {
        if (!owned.isClosed()) owned.close();
        if (!replacement.isClosed()) replacement.close();
    }

    /** The worker closes the socket it owns when its loop ends. */
    @Test
    public void teardown_closesOwnedSocket() {
        IcomUdpClient client = new IcomUdpClient(-1);

        new IcomUdpClient.DoReceiveRunnable(client, owned).run();

        assertThat(owned.isClosed()).isTrue();
    }

    /**
     * The worker must NOT clobber a replacement socket a reconnect installed into the
     * shared slot — the exact failure that left the rig dead after a reconnect.
     */
    @Test
    public void teardown_leavesReplacementSocketAndSharedSlotIntact() {
        IcomUdpClient client = new IcomUdpClient(-1);
        // Simulate a reconnect having installed a fresh socket while the old worker is
        // still winding down.
        client.primeSendSocketForTest(replacement);

        new IcomUdpClient.DoReceiveRunnable(client, owned).run();

        assertThat(replacement.isClosed()).isFalse();
        assertThat(client.getSendSocket()).isSameInstanceAs(replacement);
    }
}
