package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

/**
 * Unit tests for {@link BluetoothSerialSocket#frameFromRead(byte[], int)}, the read-loop
 * seam that decides what to do with an {@link java.io.InputStream#read(byte[])} result.
 *
 * The bug this covers: a clean remote EOF (radio powered off / RFCOMM link dropped) makes
 * {@code read()} return -1. The old loop did {@code Arrays.copyOf(buffer, -1)}, throwing a
 * NegativeArraySizeException whose "-1" message was then surfaced to the operator as a bogus
 * I/O error on every normal Bluetooth disconnect.
 */
public class BluetoothSerialSocketFrameTest {

    @Test
    public void frameFromRead_positiveLength_returnsPrefixBytes() throws IOException {
        byte[] buffer = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] frame = BluetoothSerialSocket.frameFromRead(buffer, 3);
        assertThat(frame).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    public void frameFromRead_positiveLength_copiesFullBufferWhenLenEqualsLength() throws IOException {
        byte[] buffer = new byte[]{10, 20, 30};
        byte[] frame = BluetoothSerialSocket.frameFromRead(buffer, 3);
        assertThat(frame).isEqualTo(new byte[]{10, 20, 30});
        // must be a copy, not the same array the read loop reuses
        assertThat(frame).isNotSameInstanceAs(buffer);
    }

    @Test
    public void frameFromRead_zeroLength_returnsEmptyFrame() throws IOException {
        byte[] buffer = new byte[]{1, 2, 3};
        byte[] frame = BluetoothSerialSocket.frameFromRead(buffer, 0);
        assertThat(frame).hasLength(0);
    }

    @Test
    public void frameFromRead_endOfStream_throwsIoErrorNotNegativeArraySize() {
        byte[] buffer = new byte[1024];
        // Pre-fix this line threw NegativeArraySizeException("-1"); it must now be a clean IOException.
        IOException e = assertThrows(IOException.class,
                () -> BluetoothSerialSocket.frameFromRead(buffer, -1));
        assertThat(e).isNotInstanceOf(NegativeArraySizeException.class);
        assertThat(e).hasMessageThat().contains("closed");
    }
}
