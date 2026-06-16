package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for Yaesu gen-2 (FT-817/857/897) command constants and the
 * BCD frequency encoder. The 5-byte CAT block is 4 BCD frequency bytes (in
 * 10-Hz-ish nibble weights) + a 1-byte opcode/mode trailer.
 */
public class Yaesu2RigConstantTest {

    @Test
    public void getModeStr_mapsKnownModes() {
        assertThat(Yaesu2RigConstant.getModeStr(Yaesu2RigConstant.LSB)).isEqualTo("LSB");
        assertThat(Yaesu2RigConstant.getModeStr(Yaesu2RigConstant.USB)).isEqualTo("USB");
        assertThat(Yaesu2RigConstant.getModeStr(Yaesu2RigConstant.DIG)).isEqualTo("DIG");
        assertThat(Yaesu2RigConstant.getModeStr(Yaesu2RigConstant.PKT)).isEqualTo("PKT");
    }

    @Test
    public void getModeStr_unknownFallsBack() {
        assertThat(Yaesu2RigConstant.getModeStr(0x55)).isEqualTo("UNKNOWN");
    }

    @Test
    public void setOperationFreq_encodesBcdWithTrailer() {
        // 14.074 MHz -> 01 40 74 00 + opcode 01.
        byte[] f = Yaesu2RigConstant.setOperationFreq(14_074_000L);
        byte[] expected = {(byte) 0x01, (byte) 0x40, (byte) 0x74, (byte) 0x00, (byte) 0x01};
        assertThat(f).isEqualTo(expected);
    }

    @Test
    public void setPTTState_toggleByteDiffers() {
        byte[] on = Yaesu2RigConstant.setPTTState(true);
        byte[] off = Yaesu2RigConstant.setPTTState(false);
        assertThat(on).hasLength(5);
        assertThat(on[4] & 0xFF).isEqualTo(0x08);
        assertThat(off[4] & 0xFF).isEqualTo(0x88);
    }

    @Test
    public void modeCommands_carryExpectedOpcodes() {
        // DIG mode used for the generic USB-data setter; opcode 07.
        byte[] dig = Yaesu2RigConstant.setOperationUSBMode();
        assertThat(dig[0] & 0xFF).isEqualTo(0x0A);
        assertThat(dig[4] & 0xFF).isEqualTo(0x07);

        // The 847 USB setter uses USB mode byte 0x01.
        byte[] usb = Yaesu2RigConstant.setOperationUSB847Mode();
        assertThat(usb[0] & 0xFF).isEqualTo(0x01);
        assertThat(usb[4] & 0xFF).isEqualTo(0x07);
    }

    @Test
    public void readMeterAndFreqCommands_haveExpectedOpcodes() {
        assertThat(Yaesu2RigConstant.readMeter()[4] & 0xFF).isEqualTo(0xBD);
        assertThat(Yaesu2RigConstant.setReadOperationFreq()[4] & 0xFF).isEqualTo(0x03);
    }

    @Test
    public void connectAndDisconnectCommands_haveExpectedOpcodes() {
        assertThat(Yaesu2RigConstant.sendConnectData()[4] & 0xFF).isEqualTo(0x00);
        assertThat(Yaesu2RigConstant.sendDisconnectData()[4] & 0xFF).isEqualTo(0x80);
    }
}
