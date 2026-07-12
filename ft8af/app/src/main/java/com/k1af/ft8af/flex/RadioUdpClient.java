package com.k1af.ft8af.flex;
/**
 * Simple UDP wrapper for data stream operations
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RadioUdpClient {
    private static final String TAG = "RadioUdpSocket";
    private final int MAX_BUFFER_SIZE = 1024*4;
    private DatagramSocket sendSocket;
    private int port;
    private boolean activated = false;
    private OnUdpEvents onUdpEvents = null;
    private final ExecutorService sendDataThreadPool = Executors.newCachedThreadPool();
    private final ExecutorService receiveThreadPool = Executors.newCachedThreadPool();

    public RadioUdpClient(int port) {
        this.port = port;
    }

    public synchronized void sendData(byte[] data, String ip,int port) throws UnknownHostException {
        if (!activated) return;
        //Log.e(TAG, "sendData: "+byteToStr(data) );
        //Log.e(TAG, String.format("sendData: ip: %s,port:%d ",ip,port) );
        InetAddress address = InetAddress.getByName(ip);
        // Submit a fresh runnable carrying an immutable snapshot of this call's
        // payload/target. A single shared runnable whose fields we overwrite before
        // each execute() would let back-to-back sends clobber each other on the pool
        // (a worker reads data/address/port asynchronously), sending the wrong payload
        // to the wrong host or duplicating the latest one.
        sendDataThreadPool.execute(new SendDataRunnable(this, data, address, port));
    }

    // Package-private (not private) so a unit test in this package can drive it.
    static class SendDataRunnable implements Runnable{
        final byte[] data;
        final InetAddress address;
        final int port;
        final RadioUdpClient client;

        public SendDataRunnable(RadioUdpClient client, byte[] data, InetAddress address, int port) {
            this.client = client;
            this.data = data;
            this.address = address;
            this.port = port;
        }

        @Override
        public void run() {
            DatagramPacket packet = new DatagramPacket(data, data.length, address,port);
            try {
                // Null-guard the shared socket: a disconnect that overlaps an in-flight
                // send (setActivated(false) closes and nulls sendSocket) would otherwise
                // NPE here, and only IOException is caught, so it escapes onto a pool
                // worker and crashes the app. Mirrors IcomUdpClient.SendDataRunnable.
                DatagramSocket socket = client.sendSocket;
                if (socket != null) socket.send(packet);
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, "run: " + e.getMessage());
            }
        }
    }

    public boolean isActivated() {
        return activated;
    }

    public synchronized void  setActivated(boolean activated) throws SocketException {
        this.activated = activated;
        if (activated) {//Use activated flag to determine whether to end receive thread and clear sendSocket pointer
            sendSocket = new DatagramSocket(null);//Bind to a random port
            sendSocket.bind(new InetSocketAddress(port));
            receiveData();
        }else {
            if (sendSocket!=null){
                sendSocket.close();
                // Owner-nulls the shared slot after closing it. The receive worker
                // used to do this on its way out, but a slow worker could run that
                // teardown *after* a reconnect had already installed a fresh socket
                // here, closing/nulling the new socket and leaving the rig dead.
                sendSocket = null;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void receiveData() {
        // Hand the worker the exact socket it should own, captured under this
        // synchronized method. It never reads the shared sendSocket field again, so a
        // later reconnect that swaps sendSocket can't make it receive on (or tear down)
        // the wrong socket. Mirrors IcomUdpClient.receiveData().
        receiveThreadPool.execute(new ReceiveRunnable(this, sendSocket));
    }

    // Package-private (not private) so a unit test in this package can drive its
    // teardown deterministically.
    static class ReceiveRunnable implements Runnable{
        final RadioUdpClient client;
        // The socket this worker owns. Captured at construction so the loop and the
        // teardown never touch the shared sendSocket field, which a reconnect may
        // have swapped for a different socket.
        final DatagramSocket socket;

        public ReceiveRunnable(RadioUdpClient client, DatagramSocket socket) {
            this.client = client;
            this.socket = socket;
        }

        @Override
        public void run() {
            // Exit when deactivated OR when our own socket is closed. Keying on the
            // socket (not just the shared activated flag) means a reconnect that flips
            // activated false->true again still stops this old worker instead of
            // leaving it to double-receive on the new socket or tight-spin on a closed
            // one.
            while (client.activated && !socket.isClosed()) {

                byte[] data = new byte[client.MAX_BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                try {
                    socket.receive(packet);
                    if (client.onUdpEvents != null) {
                        byte[] temp = Arrays.copyOf(packet.getData(), packet.getLength());

                        client.onUdpEvents.OnReceiveData(socket, packet, temp);


                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "receiveData: error:" + e.getMessage());
                }

            }
            Log.e(TAG, "udpClient: is exit!");
            // Close only the socket we own; never touch the shared sendSocket field
            // (setActivated owns its lifecycle). Closing our own socket is idempotent
            // if setActivated already closed it during teardown.
            socket.close();
        }
    }
    public void setOnUdpEvents(OnUdpEvents onUdpEvents) {
        this.onUdpEvents = onUdpEvents;
    }

    public interface OnUdpEvents {
        void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data);
    }

    public int getPort() {
        if (sendSocket!=null){
            return sendSocket.getLocalPort();
        }else {
            return 0;
        }
    }

    // Visible for tests: install a socket into the shared slot without opening the
    // receive loop, so ReceiveRunnable's teardown can be asserted deterministically.
    void primeSendSocketForTest(DatagramSocket socket) {
        this.sendSocket = socket;
    }

    // Visible for tests: read the shared slot to assert a worker's teardown left it
    // (and any reconnect-installed socket) untouched.
    DatagramSocket getSendSocketForTest() {
        return sendSocket;
    }

    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }
}
