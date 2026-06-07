package com.bg7yoz.ft8cn.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Pure-logic coverage for Yaesu gen-3 / Kenwood-style ASCII command builders.
 * Commands are plain ASCII strings terminated with ';'. Frequency setters
 * zero-pad to 8/9/11 digits depending on the rig family.
 */
public class Yaesu3RigConstantTest {

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }

    @Test
    public void getModeStr_mapsKnownModes() {
        assertThat(Yaesu3RigConstant.getModeStr(Yaesu3RigConstant.USB)).isEqualTo("USB");
        assertThat(Yaesu3RigConstant.getModeStr(Yaesu3RigConstant.DATA)).isEqualTo("DATA");
        assertThat(Yaesu3RigConstant.getModeStr(Yaesu3RigConstant.DATA_R)).isEqualTo("DATA_R");
        assertThat(Yaesu3RigConstant.getModeStr(Yaesu3RigConstant.AM_N)).isEqualTo("AM_N");
    }

    @Test
    public void getModeStr_unknownFallsBack() {
        assertThat(Yaesu3RigConstant.getModeStr(0x55)).isEqualTo("UNKNOWN");
    }

    @Test
    public void setOperationFreq_padsToCorrectWidth() {
        assertThat(s(Yaesu3RigConstant.setOperationFreq11Byte(14_074_000L)))
                .isEqualTo("FA00014074000;");
        assertThat(s(Yaesu3RigConstant.setOperationFreq9Byte(14_074_000L)))
                .isEqualTo("FA014074000;");
        assertThat(s(Yaesu3RigConstant.setOperationFreq8Byte(14_074_000L)))
                .isEqualTo("FA14074000;");
    }

    @Test
    public void pttCommands_useTxToggle() {
        assertThat(s(Yaesu3RigConstant.setPTTState(true))).isEqualTo("TX1;");
        assertThat(s(Yaesu3RigConstant.setPTTState(false))).isEqualTo("TX0;");
        assertThat(s(Yaesu3RigConstant.setPTT_TX_On(true))).isEqualTo("TX1;");
        assertThat(s(Yaesu3RigConstant.setPTT_TX_On(false))).isEqualTo("TX0;");
    }

    @Test
    public void modeCommands_areExpectedAsciiStrings() {
        assertThat(s(Yaesu3RigConstant.setOperationUSBMode())).isEqualTo("MD02;");
        assertThat(s(Yaesu3RigConstant.setOperationDATA_U_Mode())).isEqualTo("MD0C;");
        assertThat(s(Yaesu3RigConstant.setOperationUSB_Data_Mode())).isEqualTo("MD0C;");
        assertThat(s(Yaesu3RigConstant.setMaxSsbWidth())).isEqualTo("NA00;SH0120;");
        assertThat(s(Yaesu3RigConstant.setMaxDataWidth())).isEqualTo("NA00;SH0117;");
    }

    @Test
    public void readCommands_areExpectedAsciiStrings() {
        assertThat(s(Yaesu3RigConstant.setReadOperationFreq())).isEqualTo("FA;");
        assertThat(s(Yaesu3RigConstant.setRead39Meters_ALC())).isEqualTo("RM4;");
        assertThat(s(Yaesu3RigConstant.setRead39Meters_SWR())).isEqualTo("RM6;");
    }
}
