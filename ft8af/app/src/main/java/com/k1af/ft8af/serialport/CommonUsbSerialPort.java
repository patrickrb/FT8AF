/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.k1af.ft8af.serialport;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import com.k1af.ft8af.serialport.util.MonotonicClock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumSet;

/**
 * A base class shared by several driver implementations.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public abstract class CommonUsbSerialPort implements UsbSerialPort {

    public static boolean DEBUG = false;

    private static final String TAG = CommonUsbSerialPort.class.getSimpleName();
    private static final int MAX_READ_SIZE = 16 * 1024; // = old bulkTransfer limit

    protected final UsbDevice mDevice;
    protected final int mPortNumber;

    // non-null when open()
    protected UsbDeviceConnection mConnection = null;
    protected UsbEndpoint mReadEndpoint;
    protected UsbEndpoint mWriteEndpoint;
    protected UsbRequest mUsbRequest;

    /**
     * Internal write buffer.
     *  Guarded by {@link #mWriteBufferLock}.
     *  Default length = mReadEndpoint.getMaxPacketSize()
     **/
    protected byte[] mWriteBuffer;
    protected final Object mWriteBufferLock = new Object();


    public CommonUsbSerialPort(UsbDevice device, int portNumber) {
        mDevice = device;
        mPortNumber = portNumber;
    }

    @Override
    public String toString() {
        return String.format("<%s device_name=%s device_id=%s port_number=%s>",
                getClass().getSimpleName(), mDevice.getDeviceName(),
                mDevice.getDeviceId(), mPortNumber);
    }

    @Override
    public UsbDevice getDevice() {
        return mDevice;
    }

    @Override
    public int getPortNumber() {
        return mPortNumber;
    }

    @Override
    public UsbEndpoint getWriteEndpoint() { return mWriteEndpoint; }

    @Override
    public UsbEndpoint getReadEndpoint() { return mReadEndpoint; }

    /**
     * Returns the device serial number
     *  @return serial number
     */
    @Override
    public String getSerial() {
        return mConnection.getSerial();
    }

    /**
     * Sets the size of the internal buffer used to exchange data with the USB
     * stack for write operations.  Most users should not need to change this.
     *
     * @param bufferSize the size in bytes, <= 0 resets original size
     */
    public final void setWriteBufferSize(int bufferSize) {
        synchronized (mWriteBufferLock) {
            if (bufferSize <= 0) {
                if (mWriteEndpoint != null) {
                    bufferSize = mWriteEndpoint.getMaxPacketSize();
                } else {
                    mWriteBuffer = null;
                    return;
                }
            }
            if (mWriteBuffer != null && bufferSize == mWriteBuffer.length) {
                return;
            }
            mWriteBuffer = new byte[bufferSize];
        }
    }

    @Override
    public void open(UsbDeviceConnection connection) throws IOException {
        if (mConnection != null) {
            throw new IOException("Already open");
        }
        if(connection == null) {
            throw new IllegalArgumentException("Connection is null");
        }
        mConnection = connection;
        try {
            openInt(connection);
            if (mReadEndpoint == null || mWriteEndpoint == null) {
                throw new IOException("Could not get read & write endpoints");
            }
            mUsbRequest = new UsbRequest();
            mUsbRequest.initialize(mConnection, mReadEndpoint);
        } catch(Exception e) {
            try {
                close();
            } catch(Exception ignored) {}
            throw e;
        }
    }

    protected abstract void openInt(UsbDeviceConnection connection) throws IOException;

    /**
     * Whether the port is ready for I/O on the given endpoint.
     *
     * <p>A port is usable only once {@link #open} has BOTH published the
     * connection and {@code openInt()} has assigned the endpoint. {@code open()}
     * deliberately sets {@link #mConnection} (line "mConnection = connection")
     * <em>before</em> calling {@code openInt()} — {@code openInt}/{@code close}
     * need the connection during setup — so there is a window where
     * {@code mConnection != null} but the endpoint is still {@code null}. A
     * {@code read()}/{@code write()} from another thread during that window
     * (e.g. a TX PTT firing while CAT auto-reconnect is re-opening a freshly
     * allocated port) would pass a bare {@code mConnection == null} check and
     * then NPE dereferencing the endpoint. Treating a missing connection OR
     * endpoint as "not open" turns that crash into a recoverable
     * {@link IOException} the CAT/TX layer already handles.
     *
     * <p>Extracted as a package-private static so the readiness decision is
     * unit-testable without a live {@link UsbDeviceConnection}/{@link UsbEndpoint}.
     */
    static boolean ioEndpointReady(Object connection, Object endpoint) {
        return connection != null && endpoint != null;
    }

    @Override
    public void close() throws IOException {
        if (mConnection == null) {
            throw new IOException("Already closed");
        }
        try {
            mUsbRequest.cancel();
        } catch(Exception ignored) {}
        mUsbRequest = null;
        try {
            closeInt();
        } catch(Exception ignored) {}
        try {
            mConnection.close();
        } catch(Exception ignored) {}
        mConnection = null;
    }

    protected abstract void closeInt();

    /**
     * use simple USB request supported by all devices to test if connection is still valid
     */
    protected void testConnection() throws IOException {
        byte[] buf = new byte[2];
        int len = mConnection.controlTransfer(0x80 /*DEVICE*/, 0 /*GET_STATUS*/, 0, 0, buf, buf.length, 200);
        if(len < 0)
            throw new IOException("USB get_status request failed");
    }

    @Override
    public int read(final byte[] dest, final int timeout) throws IOException {
        return read(dest, timeout, true);
    }

    protected int read(final byte[] dest, final int timeout, boolean testConnection) throws IOException {
        // Snapshot the connection and endpoint into locals so a concurrent close()
        // (cable yanked on another thread) nulling the fields mid-read can't NPE
        // here — it stays a recoverable IOException. mReadEndpoint (like
        // mWriteEndpoint) is also null until openInt() runs, which open() calls
        // only after publishing mConnection, so the guard covers the (re)open race
        // too. See ioEndpointReady.
        final UsbDeviceConnection connection = mConnection;
        final UsbEndpoint readEndpoint = mReadEndpoint;
        if (!ioEndpointReady(connection, readEndpoint)) {
            throw new IOException("Connection closed");
        }
        if(dest.length <= 0) {
            throw new IllegalArgumentException("Read buffer to small");
        }
        final int nread;
        if (timeout != 0) {
            // bulkTransfer will cause data loss with short timeout + high baud rates + continuous transfer
            //   https://stackoverflow.com/questions/9108548/android-usb-host-bulktransfer-is-losing-data
            // but mConnection.requestWait(timeout) available since Android 8.0 es even worse,
            // as it crashes with short timeout, e.g.
            //   A/libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x276a in tid 29846 (pool-2-thread-1), pid 29618 (.usbserial.test)
            //     /system/lib64/libusbhost.so (usb_request_wait+192)
            //     /system/lib64/libandroid_runtime.so (android_hardware_UsbDeviceConnection_request_wait(_JNIEnv*, _jobject*, long)+84)
            // data loss / crashes were observed with timeout up to 200 msec
            long endTime = testConnection ? MonotonicClock.millis() + timeout : 0;
            int readMax = Math.min(dest.length, MAX_READ_SIZE);
            nread = connection.bulkTransfer(readEndpoint, dest, readMax, timeout);
            // Android error propagation is improvable:
            //  nread == -1 can be: timeout, connection lost, buffer to small, ???
            if(nread == -1 && testConnection && MonotonicClock.millis() < endTime)
                testConnection();

        } else {
            // mUsbRequest (like mReadEndpoint above) can be null after a concurrent
            // close()/re-enumeration; the timeout==0 path dereferences it, so guard
            // it here too rather than NPE on the IO thread.
            final UsbRequest request = mUsbRequest;
            if (request == null) {
                throw new IOException("Connection closed");
            }
            final ByteBuffer buf = ByteBuffer.wrap(dest);
            if (!request.queue(buf, dest.length)) {
                throw new IOException("Queueing USB request failed");
            }
            final UsbRequest response = connection.requestWait();
            if (response == null) {
                throw new IOException("Waiting for USB request failed");
            }
            nread = buf.position();
            // Android error propagation is improvable:
            //   response != null & nread == 0 can be: connection lost, buffer to small, ???
            if(nread == 0) {
                testConnection();
            }
        }
        return Math.max(nread, 0);
    }

    @Override
    public void write(final byte[] src, final int timeout) throws IOException {
        int offset = 0;
        final long endTime = (timeout == 0) ? 0 : (MonotonicClock.millis() + timeout);

        // Snapshot the connection and endpoint into locals: close() (called from
        // another thread when the cable is yanked) nulls the mConnection /
        // mWriteEndpoint fields, so reading them once here — rather than
        // dereferencing the live fields through the loop below — means a
        // concurrent close can't turn an in-flight write into an NPE. It stays a
        // recoverable IOException instead. The guard also covers the (re)open
        // window where open() publishes mConnection before openInt() assigns
        // mWriteEndpoint. See ioEndpointReady.
        final UsbDeviceConnection connection = mConnection;
        final UsbEndpoint writeEndpoint = mWriteEndpoint;
        if (!ioEndpointReady(connection, writeEndpoint)) {
            throw new IOException("Connection closed");
        }
        while (offset < src.length) {
            int requestTimeout;
            final int requestLength;
            final int actualLength;

            synchronized (mWriteBufferLock) {
                final byte[] writeBuffer;

                if (mWriteBuffer == null) {
                    mWriteBuffer = new byte[writeEndpoint.getMaxPacketSize()];
                }
                requestLength = Math.min(src.length - offset, mWriteBuffer.length);
                if (offset == 0) {
                    writeBuffer = src;
                } else {
                    // bulkTransfer does not support offsets, make a copy.
                    System.arraycopy(src, offset, mWriteBuffer, 0, requestLength);
                    writeBuffer = mWriteBuffer;
                }
                if (timeout == 0 || offset == 0) {
                    requestTimeout = timeout;
                } else {
                    requestTimeout = (int)(endTime - MonotonicClock.millis());
                    if(requestTimeout == 0)
                        requestTimeout = -1;
                }
                if (requestTimeout < 0) {
                    actualLength = -2;
                } else {
                    actualLength = connection.bulkTransfer(writeEndpoint, writeBuffer, requestLength, requestTimeout);
                }
            }
            if (DEBUG) {
                Log.d(TAG, "Wrote " + actualLength + "/" + requestLength + " offset " + offset + "/" + src.length + " timeout " + requestTimeout);
            }
            if (actualLength <= 0) {
                if (timeout != 0 && MonotonicClock.millis() >= endTime) {
                    SerialTimeoutException ex = new SerialTimeoutException("Error writing " + requestLength + " bytes at offset " + offset + " of total " + src.length + ", rc=" + actualLength);
                    ex.bytesTransferred = offset;
                    throw ex;
                } else {
                    throw new IOException("Error writing " + requestLength + " bytes at offset " + offset + " of total " + src.length);
                }
            }
            offset += actualLength;
        }
    }

    @Override
    public boolean isOpen() {
        return mConnection != null;
    }

    @Override
    public abstract void setParameters(int baudRate, int dataBits, int stopBits, @Parity int parity) throws IOException;

    @Override
    public boolean getCD() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getCTS() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getDSR() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getDTR() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public void setDTR(boolean value) throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getRI() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public boolean getRTS() throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public void setRTS(boolean value) throws IOException { throw new UnsupportedOperationException(); }

    @Override
    public abstract EnumSet<ControlLine> getControlLines() throws IOException;

    @Override
    public abstract EnumSet<ControlLine> getSupportedControlLines() throws IOException;

    @Override
    public void purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBreak(boolean value) throws IOException { throw new UnsupportedOperationException(); }

}
