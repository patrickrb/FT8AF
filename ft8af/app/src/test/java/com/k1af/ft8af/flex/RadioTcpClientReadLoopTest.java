package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression coverage for the {@link RadioTcpClient} SocketThread's handling of a dead or
 * failed TCP connection.
 *
 * <p>The Flex command socket runs {@code SocketThread.run()} → {@link RadioTcpClient#readLoop()},
 * whose loop guard is {@code isConnect()} == {@link Socket#isConnected()}. That flag is
 * <em>sticky</em>: once a socket has connected it stays {@code true} even after the peer resets
 * the link. Before the fix:
 *
 * <ul>
 *   <li>a peer reset made {@code read()} throw {@link java.net.SocketException}; the catch block
 *       only logged and looped, so the very next {@code read()} threw again — a tight 100% CPU
 *       busy-loop that never signalled {@code onConnectionClosed()};</li>
 *   <li>a graceful peer close made {@code read()} return {@code -1} forever; the loop counted
 *       to ten, fired {@code onConnectionClosed()} and then kept spinning (no {@code return});</li>
 *   <li>a connect-time {@link java.net.SocketException} (e.g. {@link java.net.ConnectException}
 *       when the rig is off / wrong IP) was swallowed without calling {@code connectFail()}, so
 *       the UI stayed stuck on "connecting" with no failure callback.</li>
 * </ul>
 *
 * <p>These tests drive the real {@link RadioTcpClient#readLoop()} / {@link
 * RadioTcpClient#openConnection()} against real loopback sockets. The {@code timeout} on the
 * read-loop cases is the assertion that the loop actually terminates — the pre-fix busy-loops
 * hang until the timeout fires.
 *
 * <p>Plain JUnit: sockets are pure JDK and the only Android type touched is
 * {@code android.util.Log}, which unit tests stub via {@code returnDefaultValues}.
 */
public class RadioTcpClientReadLoopTest {

    private final List<Socket> toClose = new ArrayList<>();
    private ServerSocket server;

    private static final class RecordingListener implements RadioTcpClient.OnDataReceiveListener {
        int connectSuccess;
        int connectFail;
        int connectionClosed;
        final List<byte[]> received = new ArrayList<>();

        @Override public void onConnectSuccess() { connectSuccess++; }
        @Override public void onConnectFail() { connectFail++; }
        @Override public void onDataReceive(byte[] buffer) { received.add(buffer); }
        @Override public void onConnectionClosed() { connectionClosed++; }
    }

    /** Connect a real loopback client/server pair and return the accepted server side. */
    private Socket connectedPair(Socket client) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        server = new ServerSocket(0, 1, loopback);
        client.connect(new InetSocketAddress(loopback, server.getLocalPort()), 2000);
        Socket serverSide = server.accept();
        toClose.add(client);
        toClose.add(serverSide);
        return serverSide;
    }

    @After
    public void tearDown() throws Exception {
        for (Socket s : toClose) {
            if (!s.isClosed()) s.close();
        }
        if (server != null && !server.isClosed()) server.close();
    }

    /**
     * A peer reset (RST) must break the read loop and signal the disconnect exactly once —
     * not spin forever. Pre-fix this hangs (busy-loop) and the timeout fails the test.
     */
    @Test(timeout = 10_000)
    public void readLoop_onPeerReset_signalsClosedAndStops() throws Exception {
        RadioTcpClient client = new RadioTcpClient();
        RecordingListener listener = new RecordingListener();
        client.setOnDataReceiveListener(listener);

        Socket clientSocket = new Socket();
        Socket serverSide = connectedPair(clientSocket);
        client.primeConnectionForTest(clientSocket, clientSocket.getInputStream());

        // Force a TCP RST from the peer: SO_LINGER 0 makes close() send a reset instead of FIN.
        serverSide.setSoLinger(true, 0);
        serverSide.close();

        client.readLoop();

        assertThat(listener.connectionClosed).isEqualTo(1);
    }

    /**
     * A graceful peer close (FIN → read() returns -1) must also break the loop and signal the
     * disconnect. Pre-fix the loop fired onConnectionClosed repeatedly and never returned.
     */
    @Test(timeout = 10_000)
    public void readLoop_onPeerEof_signalsClosedAndStops() throws Exception {
        RadioTcpClient client = new RadioTcpClient();
        RecordingListener listener = new RecordingListener();
        client.setOnDataReceiveListener(listener);

        Socket clientSocket = new Socket();
        Socket serverSide = connectedPair(clientSocket);
        client.primeConnectionForTest(clientSocket, clientSocket.getInputStream());

        serverSide.close(); // graceful FIN

        client.readLoop();

        assertThat(listener.connectionClosed).isEqualTo(1);
    }

    /** The happy path still delivers received bytes before the connection is torn down. */
    @Test(timeout = 10_000)
    public void readLoop_deliversDataThenStopsOnClose() throws Exception {
        RadioTcpClient client = new RadioTcpClient();
        RecordingListener listener = new RecordingListener();
        client.setOnDataReceiveListener(listener);

        Socket clientSocket = new Socket();
        Socket serverSide = connectedPair(clientSocket);
        client.primeConnectionForTest(clientSocket, clientSocket.getInputStream());

        byte[] payload = {1, 2, 3, 4, 5};
        serverSide.getOutputStream().write(payload);
        serverSide.getOutputStream().flush();
        serverSide.close();

        client.readLoop();

        assertThat(listener.received).isNotEmpty();
        // All delivered bytes concatenated must equal the payload we sent.
        int total = 0;
        for (byte[] chunk : listener.received) total += chunk.length;
        byte[] joined = new byte[total];
        int pos = 0;
        for (byte[] chunk : listener.received) {
            System.arraycopy(chunk, 0, joined, pos, chunk.length);
            pos += chunk.length;
        }
        assertThat(joined).isEqualTo(payload);
        assertThat(listener.connectionClosed).isEqualTo(1);
    }

    /**
     * A refused connection (nothing listening on the port) is a {@link java.net.ConnectException},
     * a {@link java.net.SocketException}. openConnection() must report the failure and return
     * false. Pre-fix the SocketException branch swallowed it: no connectFail(), no early return.
     */
    @Test(timeout = 10_000)
    public void openConnection_onRefusedConnection_signalsFailure() throws Exception {
        // Grab then release a port so nothing is listening on it -> connect is refused.
        ServerSocket probe = new ServerSocket(0);
        int deadPort = probe.getLocalPort();
        probe.close();

        RadioTcpClient client = new RadioTcpClient();
        RecordingListener listener = new RecordingListener();
        client.setOnDataReceiveListener(listener);
        client.setEndpointForTest("127.0.0.1", deadPort);

        boolean connected = client.openConnection();

        assertThat(connected).isFalse();
        assertThat(listener.connectFail).isEqualTo(1);
        assertThat(listener.connectSuccess).isEqualTo(0);
    }
}
