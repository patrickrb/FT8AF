package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.k1af.ft8af.serialport.UsbSerialPort;

import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-JVM coverage for {@link CableSerialPort#controlLineSupported}, the
 * decision behind {@code setRTS_On}/{@code setDTR_On} reporting failure (rather
 * than a silent success) when the port can't drive the requested control line —
 * so a misconfigured RTS/DTR PTT surfaces instead of reading as a keyed rig.
 */
public class CableSerialPortControlLineTest {

    @Test
    public void supportedLine_isDrivable() {
        EnumSet<UsbSerialPort.ControlLine> both =
                EnumSet.of(UsbSerialPort.ControlLine.RTS, UsbSerialPort.ControlLine.DTR);
        assertThat(CableSerialPort.controlLineSupported(both, UsbSerialPort.ControlLine.RTS)).isTrue();
        assertThat(CableSerialPort.controlLineSupported(both, UsbSerialPort.ControlLine.DTR)).isTrue();
    }

    @Test
    public void unsupportedLine_reportsFailure() {
        EnumSet<UsbSerialPort.ControlLine> rtsOnly =
                EnumSet.of(UsbSerialPort.ControlLine.RTS);
        assertThat(CableSerialPort.controlLineSupported(rtsOnly, UsbSerialPort.ControlLine.DTR)).isFalse();

        EnumSet<UsbSerialPort.ControlLine> none =
                EnumSet.noneOf(UsbSerialPort.ControlLine.class);
        assertThat(CableSerialPort.controlLineSupported(none, UsbSerialPort.ControlLine.RTS)).isFalse();
    }

    @Test
    public void nullSet_isNotSupported() {
        assertThat(CableSerialPort.controlLineSupported(null, UsbSerialPort.ControlLine.RTS)).isFalse();
    }

    // ---- writeIfOpen: the disconnect-race guard -----------------------------
    // A concurrent disconnect() nulls the serial port between a caller's
    // null-check and its write(); reading the field twice NPE-d a delayed CAT
    // freq write on the FT-891. sendData now snapshots the port and writes
    // through writeIfOpen, which treats a null (disconnected) port as "not
    // open" instead of dereferencing it.

    @Test
    public void writeIfOpen_nullPort_returnsFalseAndDoesNotWrite() throws IOException {
        // The crash case: the snapshot came back null (port disconnected). Must
        // report "not open", never NPE.
        assertThat(CableSerialPort.writeIfOpen(null, new byte[]{1, 2, 3}, 500)).isFalse();
    }

    @Test
    public void writeIfOpen_openPort_writesExactBytesAndTimeout() throws IOException {
        AtomicReference<byte[]> got = new AtomicReference<>();
        AtomicInteger gotTimeout = new AtomicInteger(-1);
        byte[] src = "FA021074000;".getBytes();

        boolean wrote = CableSerialPort.writeIfOpen((s, t) -> {
            got.set(s);
            gotTimeout.set(t);
        }, src, 800);

        assertThat(wrote).isTrue();
        assertThat(got.get()).isSameInstanceAs(src);
        assertThat(gotTimeout.get()).isEqualTo(800);
    }

    @Test
    public void writeIfOpen_propagatesIoException() {
        // A real write failure (port died mid-write) must surface as IOException
        // for sendData's catch to log + return false — not be swallowed.
        assertThrows(IOException.class, () ->
                CableSerialPort.writeIfOpen((s, t) -> { throw new IOException("port died"); },
                        new byte[]{0}, 500));
    }
}
