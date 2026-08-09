package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link CableSerialPort#isValidPortIndex}, the bounds
 * guard that decides whether {@code prepare()} may call
 * {@code driver.getPorts().get(portNum)}.
 *
 * <p>The old guard tested {@code getPorts().size() < portNum} and then indexed
 * with {@code get(portNum)}. Port lists are 0-based, so the only safe indices
 * are {@code 0 .. size-1}: the guard was off by one and let {@code portNum ==
 * size} (and an empty port list with {@code portNum == 0}) through, so
 * {@code get(portNum)} threw {@link IndexOutOfBoundsException} out of
 * {@code prepare()} → {@code connect()}. On the CAT auto-reconnect worker that
 * exception is uncaught and crashes the app. These cases pin the boundary.
 */
public class CableSerialPortPortIndexTest {

    @Test
    public void firstPortOfSinglePortDriver_isValid() {
        assertThat(CableSerialPort.isValidPortIndex(1, 0)).isTrue();
    }

    @Test
    public void lastPortOfMultiPortDriver_isValid() {
        assertThat(CableSerialPort.isValidPortIndex(4, 3)).isTrue();
    }

    @Test
    public void portNumEqualToCount_isRejected() {
        // The off-by-one case: a persisted multi-port selection replayed against
        // a device that now enumerates fewer ports. Old guard (size < portNum)
        // returned "valid" here, then get(portNum) threw.
        assertThat(CableSerialPort.isValidPortIndex(2, 2)).isFalse();
        assertThat(CableSerialPort.isValidPortIndex(1, 1)).isFalse();
    }

    @Test
    public void emptyPortList_isRejected() {
        // A driver that reports zero ports with the default portNum=0: old guard
        // (0 < 0 == false) let it through and get(0) threw on an empty list.
        assertThat(CableSerialPort.isValidPortIndex(0, 0)).isFalse();
    }

    @Test
    public void portNumBeyondCount_isRejected() {
        assertThat(CableSerialPort.isValidPortIndex(2, 5)).isFalse();
    }

    @Test
    public void negativePortNum_isRejected() {
        assertThat(CableSerialPort.isValidPortIndex(2, -1)).isFalse();
    }
}
