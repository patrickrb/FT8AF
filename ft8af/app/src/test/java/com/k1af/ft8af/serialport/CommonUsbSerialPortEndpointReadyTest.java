package com.k1af.ft8af.serialport;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link CommonUsbSerialPort#ioEndpointReady}, the
 * readiness decision behind {@code read()}/{@code write()} throwing a
 * recoverable {@link java.io.IOException} instead of crashing.
 *
 * <p>Root cause reproduced by {@link #connectionPublishedButEndpointNotYetAssigned_notReady}:
 * {@code open()} sets {@code mConnection} <em>before</em> {@code openInt()}
 * assigns the read/write endpoint, so a concurrent I/O call (a TX PTT firing
 * during CAT auto-reconnect on a freshly allocated port) can observe a non-null
 * connection with a still-null endpoint. That case previously passed the bare
 * {@code mConnection == null} check and then NPEd dereferencing the endpoint
 * ({@code mWriteEndpoint.getMaxPacketSize()}); it must now read as "not open".
 */
public class CommonUsbSerialPortEndpointReadyTest {

    @Test
    public void bothPresent_ready() {
        assertThat(CommonUsbSerialPort.ioEndpointReady(new Object(), new Object())).isTrue();
    }

    @Test
    public void connectionPublishedButEndpointNotYetAssigned_notReady() {
        // The crash window: open() published the connection, openInt() has not
        // yet assigned the endpoint.
        assertThat(CommonUsbSerialPort.ioEndpointReady(new Object(), null)).isFalse();
    }

    @Test
    public void nullConnection_notReady() {
        assertThat(CommonUsbSerialPort.ioEndpointReady(null, new Object())).isFalse();
    }

    @Test
    public void bothNull_notReady() {
        assertThat(CommonUsbSerialPort.ioEndpointReady(null, null)).isFalse();
    }
}
