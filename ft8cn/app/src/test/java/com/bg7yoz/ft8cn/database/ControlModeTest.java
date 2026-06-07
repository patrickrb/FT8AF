package com.bg7yoz.ft8cn.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link ControlMode#getControlModeStr(int)}, a static
 * switch with no Android dependency. Plain JUnit is sufficient.
 */
public class ControlModeTest {

    @Test
    public void knownModes_mapToLabels() {
        assertThat(ControlMode.getControlModeStr(ControlMode.VOX)).isEqualTo("VOX");
        assertThat(ControlMode.getControlModeStr(ControlMode.CAT)).isEqualTo("CAT");
        assertThat(ControlMode.getControlModeStr(ControlMode.BLUETOOTH)).isEqualTo("Bluetooth");
        assertThat(ControlMode.getControlModeStr(ControlMode.RTS)).isEqualTo("RTS");
        assertThat(ControlMode.getControlModeStr(ControlMode.DTR)).isEqualTo("DTR");
    }

    @Test
    public void modeConstants_haveExpectedValues() {
        assertThat(ControlMode.VOX).isEqualTo(0);
        assertThat(ControlMode.CAT).isEqualTo(1);
        assertThat(ControlMode.BLUETOOTH).isEqualTo(3);
        assertThat(ControlMode.RTS).isEqualTo(4);
        assertThat(ControlMode.DTR).isEqualTo(5);
    }

    @Test
    public void unknownMode_returnsEmptyString() {
        // Value 2 (the commented-out NETWORK slot) and other unmapped ints fall
        // through to the default "".
        assertThat(ControlMode.getControlModeStr(2)).isEmpty();
        assertThat(ControlMode.getControlModeStr(99)).isEmpty();
        assertThat(ControlMode.getControlModeStr(-1)).isEmpty();
    }
}
