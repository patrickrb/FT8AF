package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link HamlibRig}. The frequency/mode/PTT methods
 * delegate to native hamlib over a socket bridge and are verified end-to-end on
 * a real radio (see the PR's bench-test notes), not here — {@code libft8af.so}
 * is unavailable in a JVM unit test, so {@link com.k1af.ft8af.wave.HamlibNative}
 * reports unavailable and those calls are inert. This test pins the transport-
 * independent behavior: naming and the "connected == transport connected" rule.
 *
 * Plain JUnit: the tested paths touch no Android types.
 */
public class HamlibRigTest {

    @Test
    public void getName_isHamlib() {
        assertThat(new HamlibRig(1036).getName()).isEqualTo("Hamlib");
    }

    @Test
    public void isConnected_falseWithoutConnector() {
        // No connector attached → not connected, regardless of native state.
        assertThat(new HamlibRig(1036).isConnected()).isFalse();
    }

    @Test
    public void onReceiveData_withoutOpenHandle_isNoOpAndDoesNotThrow() {
        // Before the rig is opened (handle == 0) inbound bytes are simply
        // dropped rather than dereferencing a null bridge.
        HamlibRig rig = new HamlibRig(1036);
        rig.onReceiveData(new byte[]{(byte) 0xFE, (byte) 0xFE, 0x00, (byte) 0xFD});
        // reaching here without an exception is the assertion
        assertThat(rig.getName()).isEqualTo("Hamlib");
    }
}
