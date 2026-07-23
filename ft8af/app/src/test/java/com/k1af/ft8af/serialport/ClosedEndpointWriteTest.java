package com.k1af.ft8af.serialport;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.hardware.usb.UsbDeviceConnection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadow.api.Shadow;

import java.io.IOException;
import java.util.EnumSet;

/**
 * Coverage for the yanked-device guards in {@link CommonUsbSerialPort}.
 *
 * <p>Field crash (POTA, 2026-07-23): an OTG brown-out re-enumerated the USB bus
 * while the app was keying the rig. {@code mWriteEndpoint} was null, so
 * {@code mWriteEndpoint.getMaxPacketSize()} threw NPE — not IOException — which
 * escaped {@code CableSerialPort.sendData}'s catch and killed the process 3ms
 * after {@code TX1;}. The rig was left transmitting for 89 seconds.
 *
 * <p>A dead port must fail as {@link IOException}, which every caller already
 * handles.
 *
 * <p>Robolectric: {@code UsbDeviceConnection} is an Android type; the test only
 * needs a non-null instance so the connection check passes and execution reaches
 * the endpoint check.
 */
@RunWith(RobolectricTestRunner.class)
public class ClosedEndpointWriteTest {

    /** Minimal concrete port: the abstract hooks are never reached in these tests. */
    private static class TestPort extends CommonUsbSerialPort {
        TestPort() {
            super(null, 0);
        }

        @Override
        public UsbSerialDriver getDriver() {
            return null;
        }

        @Override
        protected void openInt(UsbDeviceConnection connection) {
        }

        @Override
        protected void closeInt() {
        }

        @Override
        public EnumSet<ControlLine> getSupportedControlLines() {
            return EnumSet.noneOf(ControlLine.class);
        }

        @Override
        public EnumSet<ControlLine> getControlLines() {
            return EnumSet.noneOf(ControlLine.class);
        }

        @Override
        public void setParameters(int baudRate, int dataBits, int stopBits, int parity) {
        }
    }

    private static TestPort portWithConnectionButNoEndpoints() {
        TestPort port = new TestPort();
        // Non-null connection, null endpoints: exactly the post-re-enumeration
        // state that produced the field NPE. Shadow.newInstanceOf builds the
        // framework object without invoking its hidden constructor — the project
        // has no mocking library, and nothing here calls into the connection.
        port.mConnection = Shadow.newInstanceOf(UsbDeviceConnection.class);
        port.mReadEndpoint = null;
        port.mWriteEndpoint = null;
        return port;
    }

    @Test
    public void writeWithNoConnectionThrowsIoException() {
        TestPort port = new TestPort();
        IOException e = assertThrows(IOException.class,
                () -> port.write(new byte[]{'T', 'X', '0', ';'}, 100));
        assertThat(e).hasMessageThat().contains("Connection closed");
    }

    @Test
    public void writeWithClosedEndpointThrowsIoExceptionNotNpe() {
        // The regression: this used to be a fatal NullPointerException.
        TestPort port = portWithConnectionButNoEndpoints();
        IOException e = assertThrows(IOException.class,
                () -> port.write(new byte[]{'T', 'X', '0', ';'}, 100));
        assertThat(e).hasMessageThat().contains("Write endpoint closed");
    }

    @Test
    public void readWithClosedEndpointThrowsIoExceptionNotNpe() {
        TestPort port = portWithConnectionButNoEndpoints();
        IOException e = assertThrows(IOException.class,
                () -> port.read(new byte[64], 100));
        assertThat(e).hasMessageThat().contains("Read endpoint closed");
    }

    @Test
    public void emptyWriteOnAClosedEndpointStillFailsCleanly() {
        // A zero-length write must not slip past the guard into the loop.
        TestPort port = portWithConnectionButNoEndpoints();
        assertThrows(IOException.class, () -> port.write(new byte[0], 100));
    }
}
