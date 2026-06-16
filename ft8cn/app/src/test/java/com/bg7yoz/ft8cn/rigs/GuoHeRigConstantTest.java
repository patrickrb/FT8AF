package com.bg7yoz.ft8cn.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the GuoHe Q900 binary command builders. Frames begin
 * with the 0xA5 x4 sync header and end with a CRC-16 trailer. The frequency
 * setter packs the value big-endian into both VFO-A (bytes 6-9) and VFO-B
 * (bytes 10-13) slots, then appends a CRC over the payload.
 */
public class GuoHeRigConstantTest {

    @Test
    public void getModeStr_mapsKnownModes() {
        assertThat(GuoHeRigConstant.getModeStr(GuoHeRigConstant.USB)).isEqualTo("USB");
        assertThat(GuoHeRigConstant.getModeStr(GuoHeRigConstant.DIGI)).isEqualTo("DIGI");
        assertThat(GuoHeRigConstant.getModeStr(GuoHeRigConstant.PKT)).isEqualTo("PKT");
        assertThat(GuoHeRigConstant.getModeStr(0x55)).isEqualTo("UNKNOWN");
    }

    @Test
    public void pttFrames_haveSyncHeaderAndDifferByState() {
        byte[] on = GuoHeRigConstant.setPTTState(true);
        byte[] off = GuoHeRigConstant.setPTTState(false);
        // Both carry the 0xA5 x4 sync header.
        for (int i = 0; i < 4; i++) {
            assertThat(on[i] & 0xFF).isEqualTo(0xA5);
            assertThat(off[i] & 0xFF).isEqualTo(0xA5);
        }
        // The state byte (index 6) distinguishes TX (0x00) from RX (0x01).
        assertThat(on[6] & 0xFF).isEqualTo(0x00);
        assertThat(off[6] & 0xFF).isEqualTo(0x01);
    }

    @Test
    public void setOperationFreq_packsBigEndianIntoBothVfoSlots() {
        // 14_074_000 == 0x00D6C090.
        byte[] f = GuoHeRigConstant.setOperationFreq(14_074_000L);
        assertThat(f).hasLength(16);
        // VFO-A bytes 6..9 big-endian.
        assertThat(f[6] & 0xFF).isEqualTo(0x00);
        assertThat(f[7] & 0xFF).isEqualTo(0xD6);
        assertThat(f[8] & 0xFF).isEqualTo(0xC0);
        assertThat(f[9] & 0xFF).isEqualTo(0x90);
        // VFO-B bytes 10..13 mirror VFO-A.
        assertThat(f[10] & 0xFF).isEqualTo(0x00);
        assertThat(f[11] & 0xFF).isEqualTo(0xD6);
        assertThat(f[12] & 0xFF).isEqualTo(0xC0);
        assertThat(f[13] & 0xFF).isEqualTo(0x90);
    }

    @Test
    public void setOperationFreq_appendsConsistentCrc16Trailer() {
        long freq = 14_074_000L;
        byte[] f = GuoHeRigConstant.setOperationFreq(freq);
        // Reproduce the CRC the production code computes over the 10-byte payload
        // (opcode 0x0b 0x09 + 8 freq bytes) and confirm the trailer matches.
        byte[] crcData = {
                (byte) 0x0b, (byte) 0x09,
                (byte) ((freq & 0xff000000L) >> 24),
                (byte) ((freq & 0x00ff0000L) >> 16),
                (byte) ((freq & 0x0000ff00L) >> 8),
                (byte) (freq & 0x000000ffL),
                (byte) ((freq & 0xff000000L) >> 24),
                (byte) ((freq & 0x00ff0000L) >> 16),
                (byte) ((freq & 0x0000ff00L) >> 8),
                (byte) (freq & 0x000000ffL)};
        int crc = CRC16.crc16(crcData);
        assertThat(f[14] & 0xFF).isEqualTo((crc & 0x00ff00) >> 8);
        assertThat(f[15] & 0xFF).isEqualTo(crc & 0x0000ff);
    }

    @Test
    public void modeAndReadFrames_haveSyncHeader() {
        assertThat(GuoHeRigConstant.setOperationUSBMode()[0] & 0xFF).isEqualTo(0xA5);
        assertThat(GuoHeRigConstant.setOperationFT8Mode()[0] & 0xFF).isEqualTo(0xA5);
        assertThat(GuoHeRigConstant.setReadOperationFreq()[0] & 0xFF).isEqualTo(0xA5);
    }
}
