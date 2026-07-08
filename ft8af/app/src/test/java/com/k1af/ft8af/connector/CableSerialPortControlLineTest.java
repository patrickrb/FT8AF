package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.serialport.UsbSerialPort;

import org.junit.Test;

import java.util.EnumSet;

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
}
