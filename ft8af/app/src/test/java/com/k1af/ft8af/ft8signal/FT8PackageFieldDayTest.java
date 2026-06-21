package com.k1af.ft8af.ft8signal;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;

import org.junit.Test;

/**
 * Coverage for Field Day message support in {@link FT8Package}:
 *   - {@link FT8Package#sectionIndex(String)}
 *   - {@link FT8Package#generatePack77_fd(Ft8Message)}
 *
 * Plain JUnit: FT8Package's static initialiser swallows the
 * {@code System.loadLibrary("ft8af")} UnsatisfiedLinkError, so the class loads on
 * the bare JVM. We deliberately use standard callsigns that resolve via the
 * pure-Java pack_c28 path (no native hash dependency).
 */
public class FT8PackageFieldDayTest {

    // ---- sectionIndex -----------------------------------------------------------

    @Test
    public void sectionIndex_firstSection_returnZero() {
        assertThat(FT8Package.sectionIndex("CT")).isEqualTo(0);
    }

    @Test
    public void sectionIndex_lastSection_returns83() {
        assertThat(FT8Package.sectionIndex("TER")).isEqualTo(83);
    }

    @Test
    public void sectionIndex_middleSection_WI() {
        assertThat(FT8Package.sectionIndex("WI")).isEqualTo(63);
    }

    @Test
    public void sectionIndex_EMA() {
        assertThat(FT8Package.sectionIndex("EMA")).isEqualTo(1);
    }

    @Test
    public void sectionIndex_unknown_returnsNegativeOne() {
        assertThat(FT8Package.sectionIndex("ZZZ")).isEqualTo(-1);
    }

    @Test
    public void sectionIndex_empty_returnsNegativeOne() {
        assertThat(FT8Package.sectionIndex("")).isEqualTo(-1);
    }

    @Test
    public void sectionTable_has84Entries() {
        assertThat(FT8Package.ARRL_SECTIONS).hasLength(84);
    }

    @Test
    public void sectionIndex_allSections_haveUniqueIndices() {
        // Verify no duplicates by checking every section resolves to a unique index
        for (int i = 0; i < FT8Package.ARRL_SECTIONS.length; i++) {
            assertThat(FT8Package.sectionIndex(FT8Package.ARRL_SECTIONS[i])).isEqualTo(i);
        }
    }

    // ---- generatePack77_fd ------------------------------------------------------

    @Test
    public void generatePack77_fd_returnsNonNull() {
        Ft8Message msg = buildFdMessage("CQ", "W1AW", 0, 6, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        assertThat(packed).isNotNull();
        assertThat(packed).hasLength(10);
    }

    @Test
    public void generatePack77_fd_i3Bits_areZero() {
        // i3 occupies bits 74-76 which are in byte 9 bits 3-5
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        // i3 should be 0: bits 74-76 = byte 9 bits [5:3]
        int i3 = (packed[9] >> 3) & 0x07;
        assertThat(i3).isEqualTo(0);
    }

    @Test
    public void generatePack77_fd_n3Bits_three_forInitialExchange() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        // n3 = 3: bits 71-73. Bit 71 is byte 8 bit 0, bits 72-73 are byte 9 bits [7:6]
        int n3_bit0 = packed[8] & 0x01;
        int n3_bits12 = (packed[9] >> 6) & 0x03;
        int n3 = (n3_bit0 << 2) | n3_bits12;
        assertThat(n3).isEqualTo(3);
    }

    @Test
    public void generatePack77_fd_n3Bits_four_forRExchange() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 1, 1, "A", "CT", 4);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int n3_bit0 = packed[8] & 0x01;
        int n3_bits12 = (packed[9] >> 6) & 0x03;
        int n3 = (n3_bit0 << 2) | n3_bits12;
        assertThat(n3).isEqualTo(4);
    }

    @Test
    public void generatePack77_fd_rFlag_zeroWhenNoR() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        // R1 is bit 56 = byte 7 bit 7
        int r1 = (packed[7] >> 7) & 0x01;
        assertThat(r1).isEqualTo(0);
    }

    @Test
    public void generatePack77_fd_rFlag_oneWhenR() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 1, 1, "A", "CT", 4);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int r1 = (packed[7] >> 7) & 0x01;
        assertThat(r1).isEqualTo(1);
    }

    @Test
    public void generatePack77_fd_classEncoding() {
        // k3 occupies bits 61-63 = byte 7 bits [2:0]
        for (int c = 0; c < 6; c++) {
            String cls = String.valueOf((char) ('A' + c));
            Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, cls, "CT", 3);
            byte[] packed = FT8Package.generatePack77_fd(msg);
            int k3 = packed[7] & 0x07;
            assertThat(k3).isEqualTo(c);
        }
    }

    @Test
    public void generatePack77_fd_numTxEncoding() {
        // n4 occupies bits 57-60 = byte 7 bits [6:3]
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 6, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int n4 = (packed[7] >> 3) & 0x0F;
        // numTx=6, stored as 6-1=5
        assertThat(n4).isEqualTo(5);
    }

    @Test
    public void generatePack77_fd_numTx_one_encodesAsZero() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int n4 = (packed[7] >> 3) & 0x0F;
        assertThat(n4).isEqualTo(0);
    }

    @Test
    public void generatePack77_fd_numTx_sixteen_encodesAsFifteen() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 16, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int n4 = (packed[7] >> 3) & 0x0F;
        assertThat(n4).isEqualTo(15);
    }

    @Test
    public void generatePack77_fd_sectionEncoding_CT() {
        // S7 occupies bits 64-70 = byte 8 bits [7:1]
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int s7 = (packed[8] >> 1) & 0x7F;
        assertThat(s7).isEqualTo(0); // CT is index 0
    }

    @Test
    public void generatePack77_fd_sectionEncoding_TER() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "TER", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int s7 = (packed[8] >> 1) & 0x7F;
        assertThat(s7).isEqualTo(83); // TER is index 83
    }

    @Test
    public void generatePack77_fd_sectionEncoding_WI() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "WI", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int s7 = (packed[8] >> 1) & 0x7F;
        assertThat(s7).isEqualTo(63); // WI is index 63
    }

    @Test
    public void generatePack77_fd_padBits_areZero() {
        // Bits 77-79 (pad) = byte 9 bits [2:0]
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int pad = packed[9] & 0x07;
        assertThat(pad).isEqualTo(0);
    }

    // ---- Helper ---------------------------------------------------------------

    private static Ft8Message buildFdMessage(
            String toCall, String fromCall, int rFlag,
            int numTx, String fdClass, String section, int n3) {
        Ft8Message msg = new Ft8Message(0, n3, toCall, fromCall, "");
        msg.r_flag = rFlag;
        msg.eu_serial = numTx;
        msg.arrl_class = fdClass;
        msg.arrl_rac = section;
        return msg;
    }
}
