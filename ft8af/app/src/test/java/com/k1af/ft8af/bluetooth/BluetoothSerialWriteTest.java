package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-JVM coverage for {@link BluetoothSerialSocket#writeIfConnected}, the
 * disconnect-race guard behind CAT-over-Bluetooth writes.
 *
 * <p>The bug this covers: a background {@code INTENT_ACTION_DISCONNECT} (rig
 * powered off / RFCOMM link dropped mid-QSO) runs {@code disconnect()} on
 * another thread, which nulls the socket. A CAT/TX worker already past the
 * {@code connected} check then dereferenced the now-null socket
 * ({@code socket.getOutputStream()} / {@code socket.write()}) — a
 * {@link NullPointerException}. That NPE escaped
 * {@code BluetoothRigConnector.sendCommand}'s {@code IOException}-only catch
 * and crashed the app on a background thread. Both {@code write()} layers now
 * snapshot the socket once and route it through {@code writeIfConnected},
 * which reports the torn-down link as the {@code "not connected"}
 * {@link IOException} the connector already handles instead of NPEing —
 * mirroring the USB-serial {@code CableSerialPort.writeIfOpen} fix.
 */
public class BluetoothSerialWriteTest {

    @Test
    public void nullSink_afterConcurrentDisconnect_throwsNotConnectedNotNpe() throws IOException {
        // The crash case: the caller passed the connected==true check, but a
        // concurrent disconnect() nulled the socket, so the snapshot -> sink is
        // null. Must be a clean IOException, never an NPE.
        IOException e = assertThrows(IOException.class,
                () -> BluetoothSerialSocket.writeIfConnected(true, null, new byte[]{1, 2, 3}));
        assertThat(e).isNotInstanceOf(NullPointerException.class);
        assertThat(e).hasMessageThat().contains("not connected");
    }

    @Test
    public void notConnected_throwsNotConnectedAndDoesNotWrite() {
        // Link already down: report "not connected" without touching the sink.
        IOException e = assertThrows(IOException.class,
                () -> BluetoothSerialSocket.writeIfConnected(false,
                        d -> { throw new AssertionError("must not write while disconnected"); },
                        new byte[]{0}));
        assertThat(e).hasMessageThat().contains("not connected");
    }

    @Test
    public void connectedOpenSink_writesExactBytes() throws IOException {
        AtomicReference<byte[]> got = new AtomicReference<>();
        byte[] src = "FA021074000;".getBytes();

        BluetoothSerialSocket.writeIfConnected(true, got::set, src);

        assertThat(got.get()).isSameInstanceAs(src);
    }

    @Test
    public void connectedSink_propagatesIoException() {
        // A real write failure (socket died mid-write) must surface as
        // IOException for sendCommand's catch to report — not be swallowed or
        // masked.
        assertThrows(IOException.class,
                () -> BluetoothSerialSocket.writeIfConnected(true,
                        d -> { throw new IOException("socket died"); }, new byte[]{0}));
    }
}
