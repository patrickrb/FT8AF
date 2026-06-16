package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Pure-logic coverage for Elecraft (K3-style ASCII CAT) command builders.
 * Commands are ASCII strings terminated with ';'. The frequency setter
 * zero-pads to 11 digits (TS-590-compatible width).
 */
public class ElecraftRigConstantTest {

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }

    @Test
    public void getModeStr_mapsKnownModes() {
        assertThat(ElecraftRigConstant.getModeStr(ElecraftRigConstant.USB)).isEqualTo("USB");
        assertThat(ElecraftRigConstant.getModeStr(ElecraftRigConstant.DATA)).isEqualTo("DATA");
        assertThat(ElecraftRigConstant.getModeStr(ElecraftRigConstant.DATA_R)).isEqualTo("DATA_R");
        assertThat(ElecraftRigConstant.getModeStr(ElecraftRigConstant.CW_R)).isEqualTo("CW_R");
    }

    @Test
    public void getModeStr_unknownFallsBack() {
        assertThat(ElecraftRigConstant.getModeStr(0x55)).isEqualTo("UNKNOWN");
    }

    @Test
    public void setOperationFreq11Byte_padsToElevenDigits() {
        assertThat(s(ElecraftRigConstant.setOperationFreq11Byte(14_074_000L)))
                .isEqualTo("FA00014074000;");
        assertThat(s(ElecraftRigConstant.setOperationFreq11Byte(7_074_000L)))
                .isEqualTo("FA00007074000;");
    }

    @Test
    public void pttCommands_useTxRxToggle() {
        assertThat(s(ElecraftRigConstant.setPTTState(true))).isEqualTo("TX;");
        assertThat(s(ElecraftRigConstant.setPTTState(false))).isEqualTo("RX;");
    }

    @Test
    public void modeAndReadCommands_areExpectedAsciiStrings() {
        assertThat(s(ElecraftRigConstant.setOperationUSBMode())).isEqualTo("MD2;");
        assertThat(s(ElecraftRigConstant.setReadOperationFreq())).isEqualTo("FA;");
        assertThat(s(ElecraftRigConstant.setReadMetersSWR())).isEqualTo("SWR;");
    }
}
