package com.k1af.ft8af.flex;
/**
 * Simple TCP wrapper class for Flex command operations
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RadioTcpClient {
    private static final String TAG = "RadioTcpClient";
    private static RadioTcpClient radioTcpClient = null;
    private String ip;
    private int port;
    public static final int MAX_BUFFER_SIZE=1024 * 32;

    private final ExecutorService sendByteThreadPool = Executors.newCachedThreadPool();
    private final SendByteRunnable sendByteRunnable=new SendByteRunnable(this);

    public static RadioTcpClient getInstance() {
        if (radioTcpClient == null) {
            synchronized (RadioTcpClient.class) {
                radioTcpClient = new RadioTcpClient();
            }
        }
        return radioTcpClient;
    }

    private Socket mSocket;
    private OutputStream mOutputStream;
    private InputStream mInputStream;

    private SocketThread mSocketThread;
    private boolean isStop = false;//thread flag

    private OnDataReceiveListener onDataReceiveListener = null;

    public boolean isConnect() {
        boolean flag = false;
        if (mSocket != null) {
            flag = mSocket.isConnected();
        }
        return flag;
    }

    public void connect(String ip, int port) {
        this.ip = ip;
        this.port = port;
        //mSocketThread = new SocketThread(ip, port);
        mSocketThread = new SocketThread();
        mSocketThread.start();
    }

    public void disconnect() {
        isStop = true;
        try {
            if (mOutputStream != null) {
                mOutputStream.close();
            }

            if (mInputStream != null) {
                mInputStream.close();
            }

            if (mSocket != null) {
                mSocket.close();
                mSocket = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (mSocketThread != null) {
            mSocketThread.interrupt();//not intime destory thread,so need a flag
        }
    }


    private class SocketThread extends Thread {
        @Override
        public void run() {
            Log.d(TAG, "TcpSocketThread start...");
            super.run();
            if (openConnection()) {
                readLoop();
            }
        }
    }

    /**
     * Open the TCP socket to the configured endpoint and wire up the streams.
     *
     * <p>Package-private (not private) so the connect handling can be exercised by a unit
     * test without spinning up the real receive thread.
     *
     * @return {@code true} when the socket is connected and streams are ready; {@code false}
     *         on any failure (the caller must not enter {@link #readLoop()} in that case).
     */
    boolean openConnection() {
        try {
            if (mSocket != null) {
                mSocket.close();
                mSocket = null;
            }
            InetAddress ipAddress = InetAddress.getByName(ip);
            mSocket = new Socket(ipAddress, port);
            //Set no-delay sending
            //mSocket.setTcpNoDelay(true);
            //Set input/output buffer stream size
            //mSocket.setSendBufferSize(8*1024);
            //mSocket.setReceiveBufferSize(8*1024);
            if (isConnect()) {
                mOutputStream = mSocket.getOutputStream();
                mInputStream = mSocket.getInputStream();


                isStop = false;
                connectSuccess();
                return true;
            }
            /* This approach has limited value here; true socket disconnection should rely on heartbeat sending and waiting for server response; no response within a period means connection failed */
            else {
                connectFail();
                return false;
            }

        } catch (SocketException e) {
            // ConnectException (rig off / wrong IP / peer refused) is a SocketException. Signal
            // the failure and stop, exactly like the IOException sibling below — otherwise the
            // connect attempt fell through silently and the UI stayed stuck on "connecting".
            Log.e(TAG, "TCP Connection exception:" + e.getMessage());
            connectFail();
            return false;
        } catch (IOException e) {
            connectFail();
            Log.e(TAG, "SocketThread connect io exception = " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Read from the connected socket until the peer closes it, an error occurs, or the client
     * is stopped/interrupted. Package-private so a unit test can drive it against a real
     * loopback socket.
     *
     * <p>An <em>unexpected</em> close — peer reset ({@link SocketException}), EOF, or a read
     * {@link IOException} — notifies {@link OnDataReceiveListener#onConnectionClosed()} and
     * returns. In particular a {@link SocketException} (peer reset) MUST break the loop: a
     * reset socket still reports {@link Socket#isConnected()} == {@code true} (that flag is
     * sticky once connected), so continuing would re-throw on the next {@code read()} in a
     * tight 100% CPU busy-loop that never signals the disconnect.
     *
     * <p>A <em>local</em> {@link #disconnect()} also unblocks the read (it closes the socket
     * and sets {@code isStop}/interrupts the thread), but that surfaces as the same
     * {@code SocketException}/{@code IOException}. Those paths deliberately do NOT notify —
     * a user-initiated disconnect must not look like an unexpected remote drop and fire the
     * higher layers' "connection closed" UI/toast. The plain loop-condition exit
     * ({@code isStop}/interrupt/already-disconnected) and the {@code mInputStream == null}
     * guard likewise return quietly.
     */
    // True when the read was unblocked by a local disconnect() (isStop set, or the read
    // thread interrupted) rather than by a genuine remote drop. Package-private so a unit
    // test can assert the suppression.
    boolean isStopping() {
        return isStop || Thread.currentThread().isInterrupted();
    }

    void readLoop() {
        int errorCount = 0;
        //read ...
        while (isConnect() && !isStop && !Thread.currentThread().isInterrupted()) {
            int size;
            try {
                byte[] buffer = new byte[MAX_BUFFER_SIZE];
                InputStream in = mInputStream;
                if (in == null) return;
                size = in.read(buffer);//peer closed -> -1
                if (size > 0) {
                    if (onDataReceiveListener != null) {
                        byte[] temp = Arrays.copyOf(buffer, size);
                        onDataReceiveListener.onDataReceive(temp);
                    }
                    errorCount = 0;
                } else {
                    // read() == -1 means the peer closed the stream (EOF); it returns -1
                    // immediately on every subsequent call, so declare the connection closed
                    // and stop instead of spinning.
                    errorCount++;
                    if (errorCount > 10) {
                        if (onDataReceiveListener != null) {
                            onDataReceiveListener.onConnectionClosed();
                        }
                        return;
                    }
                }

            } catch (SocketException e) {
                Log.e(TAG, "Tcp Connection exception:" + e.getMessage());
                // A local disconnect() closes the socket and surfaces here as a
                // SocketException too; only notify on an actual remote drop, not a
                // user-initiated stop.
                if (!isStopping() && onDataReceiveListener != null) {
                    onDataReceiveListener.onConnectionClosed();
                }
                return;
            } catch (IOException e) {
                //uiHandler.sendEmptyMessage(-1);
                Log.e(TAG, "SocketThread read io exception = " + e.getMessage());
                e.printStackTrace();
                // Same as above: a local disconnect closing the stream can raise an
                // IOException from the blocked read; don't report that as a remote drop.
                if (!isStopping() && onDataReceiveListener != null) {
                    onDataReceiveListener.onConnectionClosed();
                }
                return;
            }
        }
    }

    private void connectFail() {
        if (onDataReceiveListener != null) {
            onDataReceiveListener.onConnectFail();
        }
    }

    private void connectSuccess() {
        if (onDataReceiveListener != null) {
            onDataReceiveListener.onConnectSuccess();
        }
    }

    /**
     * send byte[] cmd
     * Exception : android.os.NetworkOnMainThreadException
     */
    public synchronized void sendByte(final byte[] mBuffer) {
        sendByteRunnable.mBuffer=mBuffer;
        sendByteThreadPool.execute(sendByteRunnable);
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    if (mOutputStream != null) {
//                        mOutputStream.write(mBuffer);
//                        mOutputStream.flush();
//                    }
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            }
//        }).start();
    }

    private static class SendByteRunnable implements Runnable{
        RadioTcpClient client;
        byte[] mBuffer;
        public SendByteRunnable(RadioTcpClient client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                if (mBuffer==null) return;
                if (client.mOutputStream != null) {
                    client.mOutputStream.write(mBuffer);
                    client.mOutputStream.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public interface OnDataReceiveListener {
        void onConnectSuccess();

        void onConnectFail();

        void onDataReceive(byte[] buffer);
        void onConnectionClosed();
    }

    public void setOnDataReceiveListener(
            OnDataReceiveListener dataReceiveListener) {
        onDataReceiveListener = dataReceiveListener;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    // ---- Test seams -------------------------------------------------------------------
    // Package-private hooks that let the flex unit tests drive openConnection()/readLoop()
    // against real loopback sockets without starting the SocketThread.

    void setEndpointForTest(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    void primeConnectionForTest(Socket socket, InputStream in) {
        this.mSocket = socket;
        this.mInputStream = in;
        this.isStop = false;
    }
}
