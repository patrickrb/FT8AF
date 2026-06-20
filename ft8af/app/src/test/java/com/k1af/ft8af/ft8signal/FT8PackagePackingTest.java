package com.k1af.ft8af.ft8signal;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for the pure-Java bit-packing helpers on {@link FT8Package} that do
 * NOT touch the native libft8cn hash routines:
 *   - {@link FT8Package#pack_R1_g15(String)} (grid / signal-report / RRR / RR73 / 73 slot)
 *   - {@link FT8Package#pack_r1_p1(String)}  (the /P /R suffix flag)
 *   - {@link FT8Package#pack_c28(String)}    (token + standard-callsign branches only)
 *
 * Plain JUnit: FT8Package's static initialiser swallows the
 * {@code System.loadLibrary("ft8af")} UnsatisfiedLinkError, so the class loads on
 * the bare JVM and JaCoCo records its coverage. We deliberately avoid pack_c28
 * inputs that fall through to the NTOKENS+getHash22 (native) branch.
 *
 * Constants from FT8Package: NTOKENS=2063592, MAX22=4194304, MAXGRID4=32400.
 */
public class FT8PackagePackingTest {

    // ---- pack_R1_g15 ---------------------------------------------------------

    @Test
    public void packR1g15_null_isMaxGrid4Plus1() {
        // No grid / report => MAXGRID4 + 1 sentinel.
        assertThat(FT8Package.pack_R1_g15(null)).isEqualTo(FT8Package.MAXGRID4 + 1);
    }

    @Test
    public void packR1g15_empty_isMaxGrid4Plus1() {
        assertThat(FT8Package.pack_R1_g15("")).isEqualTo(FT8Package.MAXGRID4 + 1);
    }

    @Test
    public void packR1g15_specialReports_useReservedSlots() {
        // RRR/RR73/73 map to MAXGRID4 + {2,3,4} respectively.
        assertThat(FT8Package.pack_R1_g15("RRR")).isEqualTo(FT8Package.MAXGRID4 + 2);
        assertThat(FT8Package.pack_R1_g15("RR73")).isEqualTo(FT8Package.MAXGRID4 + 3);
        assertThat(FT8Package.pack_R1_g15("73")).isEqualTo(FT8Package.MAXGRID4 + 4);
    }

    @Test
    public void packR1g15_fourCharGrid_computesBase18Base10Index() {
        // FN42: ((('F'-'A')*18 + ('N'-'A'))*10 + 4)*10 + 2
        //     = ((5*18 + 13)*10 + 4)*10 + 2 = (103*10+4)*10+2 = 1034*10+2 = 10342
        assertThat(FT8Package.pack_R1_g15("FN42")).isEqualTo(10342);
    }

    @Test
    public void packR1g15_gridAA00_isZero() {
        // The lowest 4-char grid maps to index 0.
        assertThat(FT8Package.pack_R1_g15("AA00")).isEqualTo(0);
    }

    @Test
    public void packR1g15_positiveReport_noR1Bit() {
        // "+05": irpt = 35 + 5 = 40, returns MAXGRID4 + 40 (R1 bit clear).
        assertThat(FT8Package.pack_R1_g15("+05")).isEqualTo(FT8Package.MAXGRID4 + 40);
    }

    @Test
    public void packR1g15_negativeReport_noR1Bit() {
        // "-10": irpt = 35 + (-10) = 25, returns MAXGRID4 + 25.
        assertThat(FT8Package.pack_R1_g15("-10")).isEqualTo(FT8Package.MAXGRID4 + 25);
    }

    @Test
    public void packR1g15_acknowledgedReport_setsR1Bit() {
        // "R-10": strip 'R', irpt = 35 + (-10) = 25, then OR 0x8000.
        int expected = (FT8Package.MAXGRID4 + 25) | 0x8000;
        assertThat(FT8Package.pack_R1_g15("R-10")).isEqualTo(expected);
        // R1 (0x8000) bit must be set.
        assertThat(FT8Package.pack_R1_g15("R-10") & 0x8000).isEqualTo(0x8000);
    }

    // ---- pack_r1_p1 ----------------------------------------------------------

    @Test
    public void packR1P1_nullOrShort_returnsZero() {
        assertThat(FT8Package.pack_r1_p1(null)).isEqualTo((byte) 0);
        assertThat(FT8Package.pack_r1_p1("K")).isEqualTo((byte) 0);
    }

    @Test
    public void packR1P1_portableOrRoverSuffix_returnsOne() {
        assertThat(FT8Package.pack_r1_p1("K1ABC/P")).isEqualTo((byte) 1);
        assertThat(FT8Package.pack_r1_p1("K1ABC/R")).isEqualTo((byte) 1);
    }

    @Test
    public void packR1P1_plainCallsign_returnsZero() {
        assertThat(FT8Package.pack_r1_p1("K1ABC")).isEqualTo((byte) 0);
    }

    // ---- pack_c28 (token + standard-callsign branches only) ------------------

    @Test
    public void packC28_directionalTokens_mapToZeroOneTwo() {
        assertThat(FT8Package.pack_c28("DE")).isEqualTo(0);
        assertThat(FT8Package.pack_c28("QRZ")).isEqualTo(1);
        assertThat(FT8Package.pack_c28("CQ")).isEqualTo(2);
    }

    @Test
    public void packC28_cqNumericModifier_offsetByThree() {
        // "CQ 123" -> 123 + 3 = 126.
        assertThat(FT8Package.pack_c28("CQ 123")).isEqualTo(126);
    }

    @Test
    public void packC28_cqSingleLetterModifier_offsetBy1004() {
        // "CQ A" -> ('A'-65) + 1004 = 1004.
        assertThat(FT8Package.pack_c28("CQ A")).isEqualTo(1004);
        // "CQ Z" -> 25 + 1004 = 1029.
        assertThat(FT8Package.pack_c28("CQ Z")).isEqualTo(1029);
    }

    @Test
    public void packC28_cqTwoLetterModifier_base27PlusOffset() {
        // "CQ DX" -> ('D'-65)*27 + ('X'-65) + 1031 = 3*27 + 23 + 1031 = 1135.
        assertThat(FT8Package.pack_c28("CQ DX")).isEqualTo(1135);
    }

    @Test
    public void packC28_standardCallsign_isInNTokensPlusMax22Range() {
        // A standard callsign falls into the NTOKENS + MAX22 + n28 block and must
        // NOT hit the native hash path. Exact value for "K1ABC" computed by hand:
        //   formatCallsign -> " K1ABC" (digit in pos2 => leading space pad)
        //   n28 = ((((((0*36)+20)*10+1)*27+1)*27+2)*27+3) = 3957069
        //   result = NTOKENS + MAX22 + 3957069
        long base = (long) FT8Package.NTOKENS + FT8Package.MAX22;
        assertThat(FT8Package.pack_c28("K1ABC")).isEqualTo((int) (base + 3957069));
    }

    @Test
    public void packC28_standardCallsignsDiffer() {
        // Two distinct standard callsigns produce distinct c28 codes, and both
        // sit above the NTOKENS+MAX22 boundary (i.e. the standard block).
        long boundary = (long) FT8Package.NTOKENS + FT8Package.MAX22;
        int k1abc = FT8Package.pack_c28("K1ABC");
        int w1aw = FT8Package.pack_c28("W1AW");
        assertThat((long) k1abc).isAtLeast(boundary);
        assertThat((long) w1aw).isAtLeast(boundary);
        assertThat(k1abc).isNotEqualTo(w1aw);
    }
}
