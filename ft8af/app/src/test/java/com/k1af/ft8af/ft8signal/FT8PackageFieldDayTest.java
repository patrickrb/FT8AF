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
        assertThat(FT8Package.sectionIndex("AB")).isEqualTo(0);
    }

    @Test
    public void sectionIndex_lastSection_returns85() {
        assertThat(FT8Package.sectionIndex("NB")).isEqualTo(85);
    }

    @Test
    public void sectionIndex_middleSection_WI() {
        assertThat(FT8Package.sectionIndex("WI")).isEqualTo(75);
    }

    @Test
    public void sectionIndex_EMA() {
        assertThat(FT8Package.sectionIndex("EMA")).isEqualTo(10);
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
    public void sectionTable_has86Entries() {
        assertThat(FT8Package.ARRL_SECTIONS).hasLength(86);
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
        assertThat(s7).isEqualTo(7); // CT is index 7
    }

    @Test
    public void generatePack77_fd_sectionEncoding_TER() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "TER", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int s7 = (packed[8] >> 1) & 0x7F;
        assertThat(s7).isEqualTo(43); // TER is index 43
    }

    @Test
    public void generatePack77_fd_sectionEncoding_WI() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "WI", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int s7 = (packed[8] >> 1) & 0x7F;
        assertThat(s7).isEqualTo(75); // WI is index 75
    }

    @Test
    public void generatePack77_fd_padBits_areZero() {
        // Bits 77-79 (pad) = byte 9 bits [2:0]
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] packed = FT8Package.generatePack77_fd(msg);
        int pad = packed[9] & 0x07;
        assertThat(pad).isEqualTo(0);
    }

    // ---- Round-trip encode → decode bit extraction ----------------------------

    /**
     * Simulate the C-side decode by extracting FD fields from packed bytes.
     * Verifies the encode/decode bit math matches across Java and C layers.
     */
    @Test
    public void roundTrip_typicalMessage_n3equals3() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 2, "A", "EMA", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);

        assertThat(d.i3).isEqualTo(0);
        assertThat(d.n3).isEqualTo(3);
        assertThat(d.r1).isEqualTo(0);
        assertThat(d.numTx).isEqualTo(2);
        assertThat(d.fdClass).isEqualTo("A");
        assertThat(d.section).isEqualTo("EMA");
    }

    @Test
    public void roundTrip_withRoger_n3equals4() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 1, 2, "A", "EMA", 4);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);

        assertThat(d.i3).isEqualTo(0);
        assertThat(d.n3).isEqualTo(4);
        assertThat(d.r1).isEqualTo(1);
        assertThat(d.numTx).isEqualTo(2);
        assertThat(d.fdClass).isEqualTo("A");
        assertThat(d.section).isEqualTo("EMA");
    }

    @Test
    public void roundTrip_allSixClasses() {
        for (int c = 0; c < 6; c++) {
            String cls = String.valueOf((char) ('A' + c));
            Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, cls, "CT", 3);
            byte[] p = FT8Package.generatePack77_fd(msg);
            FdDecoded d = extractFdFields(p);
            assertThat(d.fdClass).isEqualTo(cls);
        }
    }

    @Test
    public void roundTrip_numTx_one() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);
        assertThat(d.numTx).isEqualTo(1);
    }

    @Test
    public void roundTrip_numTx_sixteen() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 16, "A", "CT", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);
        assertThat(d.numTx).isEqualTo(16);
    }

    @Test
    public void roundTrip_rFlag_zero() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 3, "B", "CT", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);
        assertThat(d.r1).isEqualTo(0);
    }

    @Test
    public void roundTrip_rFlag_one() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 1, 3, "B", "CT", 4);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);
        assertThat(d.r1).isEqualTo(1);
    }

    @Test
    public void roundTrip_firstSection_AB() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "AB", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);
        assertThat(d.section).isEqualTo("AB");
    }

    @Test
    public void roundTrip_lastSection_NB() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "NB", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);
        assertThat(d.section).isEqualTo("NB");
    }

    @Test
    public void roundTrip_middleSection_STX() {
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "STX", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);
        assertThat(d.section).isEqualTo("STX");
    }

    @Test
    public void roundTrip_CQ_asCallTo() {
        Ft8Message msg = buildFdMessage("CQ", "W1AW", 0, 4, "C", "SFL", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);

        // CQ encodes as n28a=2 (special token)
        assertThat(d.n28a).isEqualTo(2);
        assertThat(d.numTx).isEqualTo(4);
        assertThat(d.fdClass).isEqualTo("C");
        assertThat(d.section).isEqualTo("SFL");
    }

    @Test
    public void roundTrip_callsignBits_preserved() {
        // Verify the same callsign produces the same n28 whether encoded
        // via generatePack77_fd or via pack_c28 directly.
        Ft8Message msg = buildFdMessage("W1AW", "K1ABC", 0, 1, "A", "CT", 3);
        byte[] p = FT8Package.generatePack77_fd(msg);
        FdDecoded d = extractFdFields(p);

        long expectedTo = FT8Package.pack_c28("W1AW") & 0x0fffffffL;
        long expectedDe = FT8Package.pack_c28("K1ABC") & 0x0fffffffL;
        assertThat(d.n28a).isEqualTo(expectedTo);
        assertThat(d.n28b).isEqualTo(expectedDe);
    }

    // ---- Helper ---------------------------------------------------------------

    /** Decoded FD fields extracted from packed bytes. */
    private static class FdDecoded {
        long n28a, n28b;
        int r1, numTx;
        String fdClass, section;
        int n3, i3;
    }

    /** Extract FD fields from packed 10-byte payload — mirrors the C decoder bit math. */
    private static FdDecoded extractFdFields(byte[] p) {
        FdDecoded d = new FdDecoded();

        // n28a (bits 0-27)
        d.n28a = ((long)(p[0] & 0xFF) << 20)
               | ((long)(p[1] & 0xFF) << 12)
               | ((long)(p[2] & 0xFF) << 4)
               | ((long)(p[3] & 0xFF) >> 4);

        // n28b (bits 28-55)
        d.n28b = ((long)(p[3] & 0x0F) << 24)
               | ((long)(p[4] & 0xFF) << 16)
               | ((long)(p[5] & 0xFF) << 8)
               | ((long)(p[6] & 0xFF));

        // R1 (bit 56) = byte 7, bit 7
        d.r1 = (p[7] >> 7) & 0x01;

        // n4 (bits 57-60) = byte 7, bits [6:3]
        int n4 = (p[7] >> 3) & 0x0F;
        d.numTx = n4 + 1;

        // k3 (bits 61-63) = byte 7, bits [2:0]
        int k3 = p[7] & 0x07;
        d.fdClass = String.valueOf((char) ('A' + k3));

        // S7 (bits 64-70) = byte 8, bits [7:1]
        int s7 = (p[8] >> 1) & 0x7F;
        d.section = (s7 < FT8Package.ARRL_SECTIONS.length)
                ? FT8Package.ARRL_SECTIONS[s7] : "???";

        // n3 (bits 71-73)
        int n3_bit0 = p[8] & 0x01;
        int n3_bits12 = (p[9] >> 6) & 0x03;
        d.n3 = (n3_bit0 << 2) | n3_bits12;

        // i3 (bits 74-76) = byte 9 bits [5:3]
        d.i3 = (p[9] >> 3) & 0x07;

        return d;
    }

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
