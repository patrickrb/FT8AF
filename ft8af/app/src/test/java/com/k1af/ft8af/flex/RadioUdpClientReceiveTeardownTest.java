package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Regression coverage for the {@link RadioUdpClient} socket-lifecycle races — the Flex
 * twin of the Icom fix in {@code IcomUdpClientReceiveTeardownTest}.
 *
 * <p>The shared {@code sendSocket} field was mutated off-lock by the receive worker,
 * which produced two defects:
 *
 * <ul>
 *   <li>{@link RadioUdpClient.SendDataRunnable#run()} dereferenced {@code sendSocket}
 *       with no null-guard while catching only {@code IOException}, so a disconnect that
 *       nulled the socket under an in-flight send threw an NPE that escaped onto a cached
 *       pool worker and crashed the app.</li>
 *   <li>{@link RadioUdpClient.ReceiveRunnable#run()} closed and nulled the <em>shared</em>
 *       {@code sendSocket} on its way out, so a slow worker could tear down a socket a
 *       reconnect had already installed.</li>
 * </ul>
 *
 * <p>These tests drive the runnables directly (the client starts deactivated, so
 * {@code ReceiveRunnable.run()} skips the receive loop and executes only the teardown) —
 * no threads, no timing, fully deterministic.
 *
 * <p>Plain JUnit: {@code DatagramSocket} is pure JDK and the only Android type touched is
 * {@code android.util.Log}, which unit tests stub via {@code returnDefaultValues}.
 */
public class RadioUdpClientReceiveTeardownTest {

    private final DatagramSocket owned;
    private final DatagramSocket replacement;

    public RadioUdpClientReceiveTeardownTest() throws Exception {
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
        RadioUdpClient client = new RadioUdpClient(0);

        new RadioUdpClient.ReceiveRunnable(client, owned).run();

        assertThat(owned.isClosed()).isTrue();
    }

    /**
     * The worker must NOT clobber a replacement socket a reconnect installed into the
     * shared slot — the exact failure that left the rig dead after a reconnect.
     */
    @Test
    public void teardown_leavesReplacementSocketAndSharedSlotIntact() {
        RadioUdpClient client = new RadioUdpClient(0);
        // Simulate a reconnect having installed a fresh socket while the old worker is
        // still winding down.
        client.primeSendSocketForTest(replacement);

        new RadioUdpClient.ReceiveRunnable(client, owned).run();

        assertThat(replacement.isClosed()).isFalse();
        assertThat(client.getSendSocketForTest()).isSameInstanceAs(replacement);
    }

    /**
     * A send that runs after teardown cleared the shared socket must not throw — the NPE
     * that used to escape onto a cached pool worker and crash the app.
     */
    @Test
    public void send_afterSocketClearedByTeardown_doesNotThrow() throws Exception {
        RadioUdpClient client = new RadioUdpClient(0);
        // sendSocket is null (the state setActivated(false) / a teardown leaves behind).
        RadioUdpClient.SendDataRunnable send = new RadioUdpClient.SendDataRunnable(
                client, new byte[]{1, 2, 3}, InetAddress.getLoopbackAddress(), 5001);

        send.run(); // pre-fix: NullPointerException on client.sendSocket.send(...)

        assertThat(client.getSendSocketForTest()).isNull();
    }

    /**
     * Each send carries its own immutable snapshot. The old code reused a single shared
     * runnable whose data/address/port were overwritten before every {@code execute()},
     * so two back-to-back sends racing on the pool could send the wrong payload to the
     * wrong host (or duplicate the latest one). Two runnables built from different calls
     * must retain their own fields.
     */
    @Test
    public void eachSend_capturesIndependentSnapshot() throws Exception {
        RadioUdpClient client = new RadioUdpClient(0);
        byte[] first = {1, 2, 3};
        byte[] second = {9, 9};
        InetAddress addrA = InetAddress.getByName("192.0.2.10");
        InetAddress addrB = InetAddress.getByName("192.0.2.20");

        RadioUdpClient.SendDataRunnable a =
                new RadioUdpClient.SendDataRunnable(client, first, addrA, 5001);
        RadioUdpClient.SendDataRunnable b =
                new RadioUdpClient.SendDataRunnable(client, second, addrB, 5002);

        assertThat(a.data).isSameInstanceAs(first);
        assertThat(a.address).isSameInstanceAs(addrA);
        assertThat(a.port).isEqualTo(5001);
        // b must not have clobbered a's snapshot.
        assertThat(b.data).isSameInstanceAs(second);
        assertThat(b.address).isSameInstanceAs(addrB);
        assertThat(b.port).isEqualTo(5002);
    }
}
