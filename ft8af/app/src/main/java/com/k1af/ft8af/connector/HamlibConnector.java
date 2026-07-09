package com.k1af.ft8af.connector;

import android.util.Log;

import com.k1af.ft8af.rigs.OnConnectReceiveData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP transport to a Hamlib {@code rigctld} daemon. Opens the socket on a
 * background thread, streams reply bytes back to the rig via
 * {@link OnConnectReceiveData}, and writes CAT command lines built by
 * {@code HamlibRigctl}. Kept intentionally thin (no protocol knowledge lives
 * here) to mirror the other network connectors; all wire-format logic and its
 * unit tests live in {@code HamlibRigctl}.
 */
public class HamlibConnector extends BaseRigConnector {
    private static final String TAG = "HamlibConnector";
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;

    private volatile Socket socket;
    private volatile OutputStream outputStream;
    private volatile boolean running;
    private Thread readerThread;

    public HamlibConnector(String host, int port, int controlMode) {
        super(controlMode);
        this.host = host;
        this.port = port;
    }

    @Override
    public void connect() {
        if (getOnConnectorStateChanged() != null) {
            getOnConnectorStateChanged().onConnecting();
        }
        new Thread(this::openSocket, "HamlibConnector-connect").start();
    }

    private void openSocket() {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            socket = s;
            outputStream = s.getOutputStream();
            running = true;
            if (getOnConnectorStateChanged() != null) {
                getOnConnectorStateChanged().onConnected();
            }
            startReader(s);
        } catch (IOException e) {
            Log.e(TAG, "connect failed: " + e.getMessage());
            running = false;
            if (getOnConnectorStateChanged() != null) {
                getOnConnectorStateChanged().onRunError(e.getMessage());
            }
        }
    }

    private void startReader(Socket s) {
        readerThread = new Thread(() -> {
            byte[] chunk = new byte[512];
            try {
                InputStream in = s.getInputStream();
                int read;
                while (running && (read = in.read(chunk)) != -1) {
                    OnConnectReceiveData receiver = getOnConnectReceiveData();
                    if (receiver != null) {
                        byte[] slice = new byte[read];
                        System.arraycopy(chunk, 0, slice, 0, read);
                        receiver.onData(slice);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "read failed: " + e.getMessage());
                }
            } finally {
                boolean wasRunning = running;
                running = false;
                if (wasRunning && getOnConnectorStateChanged() != null) {
                    getOnConnectorStateChanged().onDisconnected();
                }
            }
        }, "HamlibConnector-reader");
        readerThread.start();
    }

    @Override
    public synchronized void sendData(byte[] data) {
        OutputStream out = outputStream;
        if (out == null) {
            return;
        }
        try {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "write failed: " + e.getMessage());
            running = false;
            if (getOnConnectorStateChanged() != null) {
                getOnConnectorStateChanged().onRunError(e.getMessage());
            }
        }
    }

    @Override
    public void setPttOn(byte[] command) {
        sendData(command);
    }

    @Override
    public boolean isConnected() {
        Socket s = socket;
        return running && s != null && s.isConnected() && !s.isClosed();
    }

    @Override
    public void disconnect() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                Log.e(TAG, "close failed: " + e.getMessage());
            }
        }
        socket = null;
        outputStream = null;
    }
}
