package com.k1af.ft8af.icom;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Regression coverage for the {@link IcomUdpClient} shared-send-{@code Runnable} race.
 *
 * <p>{@code sendData} used to mutate a single long-lived {@code SendDataRunnable}
 * (its {@code data}/{@code address}/{@code port} fields) and resubmit that <em>same</em>
 * object to a cached thread pool on every call. An Icom/Xiegu Wi-Fi session drives that
 * method from several threads at once — the ping (500 ms), idle and are-you-there timers
 * plus CAT command sends — so two overlapping sends clobbered each other. Because control
 * / ping / command packets differ in length, a pool worker could read the previous
 * buffer but the next length in {@code new DatagramPacket(data, data.length, ...)} and
 * throw {@code IllegalArgumentException} (uncaught on the pool thread → app crash), or at
 * best transmit the wrong bytes and desync the tracked sequence.
 *
 * <p>The fix builds a fresh, immutable task per call. These tests capture the submitted
 * tasks with a stub executor and run them deterministically — no threads, no timing.
 *
 * <p>Plain JUnit: {@code DatagramSocket} is pure JDK and the only Android type touched is
 * {@code android.util.Log}, which unit tests stub via {@code returnDefaultValues}.
 */
public class IcomUdpClientSendDataTest {

    /** Executor that records submitted tasks instead of running them. */
    private static final class CapturingExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private IcomUdpClient client;
    private DatagramSocket receiver;

    @After
    public void tearDown() throws Exception {
        if (client != null) client.setActivated(false);
        if (receiver != null && !receiver.isClosed()) receiver.close();
    }

    /** Every send must produce its own task; the pre-fix code reused one shared object. */
    @Test
    public void sendData_buildsAFreshTaskPerCall() throws Exception {
        client = new IcomUdpClient(-1);
        CapturingExecutor executor = new CapturingExecutor();
        client.setSendExecutorForTest(executor);
        client.setActivated(true);

        client.sendData(new byte[]{1, 2, 3, 4}, "127.0.0.1", 50000);
        client.sendData(new byte[]{9, 9}, "127.0.0.1", 50000);

        assertThat(executor.tasks).hasSize(2);
        assertThat(executor.tasks.get(0)).isNotSameInstanceAs(executor.tasks.get(1));
    }

    /**
     * The first-queued task must still transmit the FIRST payload after a second, shorter
     * send was submitted. With the shared runnable it already held the second buffer, so
     * running the first task sent the wrong bytes (and a length change could crash it).
     */
    @Test
    public void queuedSend_keepsItsOwnPayload_notTheLatest() throws Exception {
        receiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        receiver.setSoTimeout(2000);
        int port = receiver.getLocalPort();

        client = new IcomUdpClient(-1);
        CapturingExecutor executor = new CapturingExecutor();
        client.setSendExecutorForTest(executor);
        client.setActivated(true); // opens the real send socket used by the tasks

        byte[] first = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] second = {9, 9};

        client.sendData(first, "127.0.0.1", port);
        client.sendData(second, "127.0.0.1", port);

        executor.tasks.get(0).run();
        assertThat(receiveBytes()).isEqualTo(first);

        executor.tasks.get(1).run();
        assertThat(receiveBytes()).isEqualTo(second);
    }

    private byte[] receiveBytes() throws Exception {
        byte[] buf = new byte[64];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        receiver.receive(packet);
        return Arrays.copyOf(packet.getData(), packet.getLength());
    }
}
