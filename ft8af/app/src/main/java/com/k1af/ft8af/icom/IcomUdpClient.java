package com.k1af.ft8af.icom;
/**
 * Simple UDP protocol handler wrapper
 *
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

public class IcomUdpClient {
    private static final String TAG = "RadioUdpSocket";


    private final int MAX_BUFFER_SIZE = 1024 * 2;
    private DatagramSocket sendSocket;
    //private int remotePort;
    private int localPort = -1;
    private boolean activated = false;
    private OnUdpEvents onUdpEvents = null;
    private final ExecutorService doReceiveThreadPool = Executors.newCachedThreadPool();
    private final ExecutorService sendDataThreadPool = Executors.newCachedThreadPool();
    private SendDataRunnable sendDataRunnable = new SendDataRunnable(this);

    public IcomUdpClient() {//Random local port
        localPort = -1;
    }

    public IcomUdpClient(int localPort) {//If localPort==-1, random local port
        this.localPort = localPort;
    }

    public void sendData(byte[] data, String ip, int port) throws UnknownHostException {
        if (!activated) return;

        InetAddress address = InetAddress.getByName(ip);
        sendDataRunnable.address = address;
        sendDataRunnable.data = data;
        sendDataRunnable.port = port;
        sendDataThreadPool.execute(sendDataRunnable);
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
//                synchronized (this) {
//                    try {
//                        sendSocket.send(packet);
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                        Log.e(TAG, "IComUdpClient: " + e.getMessage());
//                        if (onUdpEvents!=null){
//                            onUdpEvents.OnUdpSendIOException(e);
//                        }
//                    }
//                }
//            }
//        }).start();
    }

    private static class SendDataRunnable implements Runnable {
        byte[] data;
        int port;
        InetAddress address;
        IcomUdpClient client;

        public SendDataRunnable(IcomUdpClient client) {
            this.client = client;
        }

        @Override
        public void run() {
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            synchronized (this) {
                try {
                    if (client.sendSocket != null) client.sendSocket.send(packet);

                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "IComUdpClient: " + e.getMessage());
                    if (client != null) {
                        if (client.onUdpEvents != null) {
                            client.onUdpEvents.OnUdpSendIOException(e);
                        }
                    }
                }
            }
        }
    }

    public boolean isActivated() {
        return activated;
    }

    public synchronized void setActivated(boolean activated) throws SocketException {
        this.activated = activated;
        if (activated) {//Use activated flag to determine if receive thread should end, and clear sendSocket pointer
            sendSocket = new DatagramSocket();
            //new DatagramSocket(null);//Bind to random port
            sendSocket.setReuseAddress(true);
            if (localPort != -1) {//Bind to specified local port
                sendSocket.bind(new InetSocketAddress(localPort));
            }

            //Update local port value
            localPort = sendSocket.getLocalPort();
            Log.e(TAG, "openUdpPort: " + sendSocket.getLocalPort());
            //Log.e(TAG, "openUdpIp: " + sendSocket.getLocalAddress());


            receiveData();
        } else {
            if (sendSocket != null) {
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
        // the wrong socket.
        doReceiveThreadPool.execute(new DoReceiveRunnable(this, sendSocket));
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                while (activated) {
//                    byte[] data = new byte[MAX_BUFFER_SIZE];
//                    DatagramPacket packet = new DatagramPacket(data, data.length);
//                    try {
//                        sendSocket.receive(packet);
//                        if (onUdpEvents != null) {
//                            byte[] temp = Arrays.copyOf(packet.getData(), packet.getLength());
//                            onUdpEvents.OnReceiveData(sendSocket, packet, temp);
//                        }
//                        //Log.d(TAG, "receiveData:host ip: " + packet.getAddress().getHostName());
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                        Log.e(TAG, "receiveData: error:" + e.getMessage());
//                    }
//
//                }
//                Log.e(TAG, "udpClient: is exit!");
//                sendSocket.close();
//                sendSocket = null;
//            }
//        }).start();

    }

    public void setOnUdpEvents(OnUdpEvents onUdpEvents) {
        this.onUdpEvents = onUdpEvents;
    }

    public interface OnUdpEvents {
        void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data);

        void OnUdpSendIOException(IOException e);
    }

    public int getLocalPort() {
        if (sendSocket != null) {
            return sendSocket.getLocalPort();
        } else {
            return 0;
        }
    }

    public String getLocalIp() {
        if (sendSocket != null) {
            return sendSocket.getLocalAddress().toString();
        } else {
            return "127.0.0.1";
        }
    }

    public DatagramSocket getSendSocket() {
        return sendSocket;
    }


    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }

    // Package-private (not private) so a unit test in this package can drive its
    // teardown deterministically.
    static class DoReceiveRunnable implements Runnable {
        final IcomUdpClient icomUdpClient;
        // The socket this worker owns. Captured at construction so the loop and the
        // teardown never touch the shared sendSocket field, which a reconnect may
        // have swapped for a different socket.
        final DatagramSocket socket;

        public DoReceiveRunnable(IcomUdpClient icomUdpClient, DatagramSocket socket) {
            this.icomUdpClient = icomUdpClient;
            this.socket = socket;
        }

        @Override
        public void run() {
            // Exit when deactivated OR when our own socket is closed. Keying on the
            // socket (not just the shared activated flag) means a reconnect that flips
            // activated false->true again still stops this old worker instead of
            // leaving it to double-receive on the new socket or tight-spin on a closed
            // one.
            while (icomUdpClient.activated && !socket.isClosed()) {
                byte[] data = new byte[icomUdpClient.MAX_BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                try {
                    socket.receive(packet);
                    if (icomUdpClient.onUdpEvents != null) {
                        byte[] temp = Arrays.copyOf(packet.getData(), packet.getLength());
                        icomUdpClient.onUdpEvents.OnReceiveData(socket, packet, temp);
                    }
                    //Log.d(TAG, "receiveData:host ip: " + packet.getAddress().getHostName());
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

    // Visible for tests: install a socket into the shared slot without opening the
    // receive loop, so DoReceiveRunnable's teardown can be asserted deterministically.
    void primeSendSocketForTest(DatagramSocket socket) {
        this.sendSocket = socket;
    }

}
