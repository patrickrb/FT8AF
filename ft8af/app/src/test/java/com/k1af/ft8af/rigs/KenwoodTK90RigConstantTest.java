package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Pure-logic coverage for the Kenwood TK-90 family ASCII command builders, which
 * also host the TS-590/TS-570/TS-2000/FLEX-6000/(tr)uSDX command variants. Native
 * TK-90 commands terminate with CR ("\r"); the Kenwood-clone variants terminate
 * with ';'.
 */
public class KenwoodTK90RigConstantTest {

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }

    @Test
    public void getModeStr_mapsKnownModes() {
        assertThat(KenwoodTK90RigConstant.getModeStr(KenwoodTK90RigConstant.USB)).isEqualTo("USB");
        assertThat(KenwoodTK90RigConstant.getModeStr(KenwoodTK90RigConstant.FSK)).isEqualTo("FSK");
        assertThat(KenwoodTK90RigConstant.getModeStr(KenwoodTK90RigConstant.DATA)).isEqualTo("DATA");
        assertThat(KenwoodTK90RigConstant.getModeStr(0x55)).isEqualTo("UNKNOWN");
    }

    @Test
    public void nativeTk90Commands_useCrTermination() {
        assertThat(s(KenwoodTK90RigConstant.setPTTState(true))).isEqualTo("TX\r");
        assertThat(s(KenwoodTK90RigConstant.setPTTState(false))).isEqualTo("RX\r");
        assertThat(s(KenwoodTK90RigConstant.setOperationUSBMode())).isEqualTo("MD2\r");
        assertThat(s(KenwoodTK90RigConstant.setReadOperationFreq())).isEqualTo("FA\r");
        assertThat(s(KenwoodTK90RigConstant.setVFOMode())).isEqualTo("FR0\r");
    }

    @Test
    public void ts590Commands_useSemicolonTermination() {
        assertThat(s(KenwoodTK90RigConstant.setTS590PTTState(true))).isEqualTo("TX1;");
        assertThat(s(KenwoodTK90RigConstant.setTS590PTTState(false))).isEqualTo("RX;");
        assertThat(s(KenwoodTK90RigConstant.setTS590OperationUSBMode())).isEqualTo("MD2;");
        assertThat(s(KenwoodTK90RigConstant.setTS590VFOMode())).isEqualTo("FR0;");
        assertThat(s(KenwoodTK90RigConstant.setTS590ReadOperationFreq())).isEqualTo("FA;");
        assertThat(s(KenwoodTK90RigConstant.setRead590Meters())).isEqualTo("RM;");
    }

    @Test
    public void ts570AndTs2000AndFlexPtt() {
        assertThat(s(KenwoodTK90RigConstant.setTS570PTTState(true))).isEqualTo("TX;");
        assertThat(s(KenwoodTK90RigConstant.setTS570PTTState(false))).isEqualTo("RX;");
        // TS-2000 keys with TX0; and shares the TS-590 RX; for receive.
        assertThat(s(KenwoodTK90RigConstant.setTS2000PTTState(true))).isEqualTo("TX0;");
        assertThat(s(KenwoodTK90RigConstant.setTS2000PTTState(false))).isEqualTo("RX;");
        assertThat(s(KenwoodTK90RigConstant.setFLEX6000PTTState(true))).isEqualTo("TX01;");
        assertThat(s(KenwoodTK90RigConstant.setFLEX6000PTTState(false))).isEqualTo("RX;");
        assertThat(s(KenwoodTK90RigConstant.setFLEX6000OperationUSBMode())).isEqualTo("MD9;");
        assertThat(s(KenwoodTK90RigConstant.setTS2000OperationDataMode())).isEqualTo("MD6;");
    }

    @Test
    public void frequencySetters_zeroPadToElevenDigits() {
        assertThat(s(KenwoodTK90RigConstant.setOperationFreq(14_074_000L)))
                .isEqualTo("FA00014074000\r");
        assertThat(s(KenwoodTK90RigConstant.setTS590OperationFreq(14_074_000L)))
                .isEqualTo("FA00014074000;");
    }

    @Test
    public void trUsdxCommands() {
        assertThat(s(KenwoodTK90RigConstant.setTrUSDXStreaming(true))).isEqualTo("UA2;");
        assertThat(s(KenwoodTK90RigConstant.setTrUSDXStreaming(false))).isEqualTo("UA0;");
        assertThat(s(KenwoodTK90RigConstant.setTrUSDXPTTState(true))).isEqualTo(";TX0;");
        assertThat(s(KenwoodTK90RigConstant.setTrUSDXPTTState(false))).isEqualTo(";RX;");
    }
}
