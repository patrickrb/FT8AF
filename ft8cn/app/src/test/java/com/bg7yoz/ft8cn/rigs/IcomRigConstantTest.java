package com.bg7yoz.ft8cn.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for ICOM CI-V command builders and BCD helpers.
 *
 * Built frames put the rig address at index 2 and controller address at index 3
 * (the reverse of received frames). Frequency is 5-byte little-endian BCD. The
 * documented reference for 14.074 MHz is
 * {@code FE FE A4 E0 05 00 40 07 14 00 FD}. {@code android.util.Log} returns
 * defaults under plain JUnit.
 */
public class IcomRigConstantTest {

    private static final int CTRL = 0xE0;
    private static final int RIG = 0xA4;

    @Test
    public void getModeStr_mapsKnownModes() {
        assertThat(IcomRigConstant.getModeStr(IcomRigConstant.LSB)).isEqualTo("LSB");
        assertThat(IcomRigConstant.getModeStr(IcomRigConstant.USB)).isEqualTo("USB");
        assertThat(IcomRigConstant.getModeStr(IcomRigConstant.CW)).isEqualTo("CW");
        assertThat(IcomRigConstant.getModeStr(IcomRigConstant.DV)).isEqualTo("DV");
    }

    @Test
    public void getModeStr_unknownFallsBack() {
        assertThat(IcomRigConstant.getModeStr(999)).isEqualTo("UNKNOWN");
    }

    @Test
    public void setOperationFrequency_encodesLittleEndianBcd() {
        byte[] f = IcomRigConstant.setOperationFrequency(CTRL, RIG, 14_074_000L);
        // FE FE A4 E0 05 00 40 07 14 00 FD
        byte[] expected = {
                (byte) 0xFE, (byte) 0xFE, (byte) 0xA4, (byte) 0xE0, (byte) 0x05,
                (byte) 0x00, (byte) 0x40, (byte) 0x07, (byte) 0x14, (byte) 0x00,
                (byte) 0xFD};
        assertThat(f).isEqualTo(expected);
    }

    @Test
    public void setPTTState_buildsTransmitFrame() {
        // 1C 00 <state>; PTT ON -> state 0x01.
        byte[] on = IcomRigConstant.setPTTState(CTRL, RIG, IcomRigConstant.PTT_ON);
        assertThat(on).hasLength(8);
        assertThat(on[0] & 0xFF).isEqualTo(0xFE);
        assertThat(on[1] & 0xFF).isEqualTo(0xFE);
        assertThat(on[2] & 0xFF).isEqualTo(RIG);
        assertThat(on[3] & 0xFF).isEqualTo(CTRL);
        assertThat(on[4] & 0xFF).isEqualTo(0x1C);
        assertThat(on[6] & 0xFF).isEqualTo(0x01);
        assertThat(on[7] & 0xFF).isEqualTo(0xFD);

        byte[] off = IcomRigConstant.setPTTState(CTRL, RIG, IcomRigConstant.PTT_OFF);
        assertThat(off[6] & 0xFF).isEqualTo(0x00);
    }

    @Test
    public void getSWRState_buildsReadMeterSwrFrame() {
        byte[] f = IcomRigConstant.getSWRState(CTRL, RIG);
        assertThat(f).hasLength(7);
        assertThat(f[4] & 0xFF).isEqualTo(0x15); // CMD_READ_METER
        assertThat(f[5] & 0xFF).isEqualTo(0x12); // SWR
        assertThat(f[6] & 0xFF).isEqualTo(0xFD);
    }

    @Test
    public void getALCState_buildsReadMeterAlcFrame() {
        byte[] f = IcomRigConstant.getALCState(CTRL, RIG);
        assertThat(f[4] & 0xFF).isEqualTo(0x15);
        assertThat(f[5] & 0xFF).isEqualTo(0x13); // ALC
    }

    @Test
    public void setOperationMode_buildsModeFrame() {
        byte[] f = IcomRigConstant.setOperationMode(CTRL, RIG, IcomRigConstant.USB);
        assertThat(f).hasLength(8);
        assertThat(f[4] & 0xFF).isEqualTo(0x06);
        assertThat(f[5] & 0xFF).isEqualTo(0x01); // USB
        assertThat(f[6] & 0xFF).isEqualTo(0x01); // FIL1
    }

    @Test
    public void setReadFreq_buildsShortReadFrame() {
        byte[] f = IcomRigConstant.setReadFreq(CTRL, RIG);
        assertThat(f).hasLength(6);
        assertThat(f[4] & 0xFF).isEqualTo(0x03);
        assertThat(f[5] & 0xFF).isEqualTo(0xFD);
    }

    @Test
    public void twoByteBcdToInt_littleEndianOrder() {
        // data[1] is the low pair, data[0] is the high pair.
        assertThat(IcomRigConstant.twoByteBcdToInt(new byte[]{0x12, 0x34})).isEqualTo(1234);
    }

    @Test
    public void twoByteBcdToIntBigEnd_reversedOrder() {
        assertThat(IcomRigConstant.twoByteBcdToIntBigEnd(new byte[]{0x12, 0x34})).isEqualTo(3412);
    }

    @Test
    public void twoByteBcd_shortInputReturnsZero() {
        assertThat(IcomRigConstant.twoByteBcdToInt(new byte[]{0x12})).isEqualTo(0);
        assertThat(IcomRigConstant.twoByteBcdToIntBigEnd(new byte[0])).isEqualTo(0);
    }
}
