package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for BaseRigOperation — frequency/band/wavelength
 * formatting and lookups. Every method here is a static, Android-free function,
 * so no Robolectric runner is needed. Frequencies are in Hz throughout (matching
 * the production code), e.g. 14_074_000 == 14.074 MHz.
 */
public class BaseRigOperationTest {

    @Test
    public void getFrequencyStr_formatsMHzWithThreeDecimals() {
        assertThat(BaseRigOperation.getFrequencyStr(14_074_000L)).isEqualTo("14.074MHz");
        assertThat(BaseRigOperation.getFrequencyStr(7_074_000L)).isEqualTo("7.074MHz");
        assertThat(BaseRigOperation.getFrequencyStr(1_840_000L)).isEqualTo("1.840MHz");
    }

    @Test
    public void getFrequencyFloat_formatsSixDecimalPlaces() {
        assertThat(BaseRigOperation.getFrequencyFloat(14_074_000L)).isEqualTo("14.074000");
        assertThat(BaseRigOperation.getFrequencyFloat(7_074_123L)).isEqualTo("7.074123");
    }

    @Test
    public void getFrequencyAllInfo_combinesFreqAndBand() {
        assertThat(BaseRigOperation.getFrequencyAllInfo(14_074_000L))
                .isEqualTo("14.074MHz (20m)");
    }

    @Test
    public void checkIsWSPR2_trueInsideKnownWindows() {
        assertThat(BaseRigOperation.checkIsWSPR2(137_500L)).isTrue();     // 2190m
        assertThat(BaseRigOperation.checkIsWSPR2(7_040_100L)).isTrue();   // 40m
        assertThat(BaseRigOperation.checkIsWSPR2(14_097_100L)).isTrue();  // 20m
        assertThat(BaseRigOperation.checkIsWSPR2(1_296_501_500L)).isTrue(); // 23cm
    }

    @Test
    public void checkIsWSPR2_falseOutsideWindows() {
        assertThat(BaseRigOperation.checkIsWSPR2(14_074_000L)).isFalse(); // FT8, not WSPR
        assertThat(BaseRigOperation.checkIsWSPR2(0L)).isFalse();
        assertThat(BaseRigOperation.checkIsWSPR2(14_097_300L)).isFalse(); // just above 20m window
    }

    @Test
    public void getMeterFromFreq_coversEveryNamedBand() {
        assertThat(BaseRigOperation.getMeterFromFreq(136_000L)).isEqualTo("2200m");
        assertThat(BaseRigOperation.getMeterFromFreq(475_000L)).isEqualTo("630m");
        assertThat(BaseRigOperation.getMeterFromFreq(1_840_000L)).isEqualTo("160m");
        assertThat(BaseRigOperation.getMeterFromFreq(3_573_000L)).isEqualTo("80m");
        assertThat(BaseRigOperation.getMeterFromFreq(5_357_000L)).isEqualTo("60m");
        assertThat(BaseRigOperation.getMeterFromFreq(7_074_000L)).isEqualTo("40m");
        assertThat(BaseRigOperation.getMeterFromFreq(10_136_000L)).isEqualTo("30m");
        assertThat(BaseRigOperation.getMeterFromFreq(14_074_000L)).isEqualTo("20m");
        assertThat(BaseRigOperation.getMeterFromFreq(18_100_000L)).isEqualTo("17m");
        assertThat(BaseRigOperation.getMeterFromFreq(21_074_000L)).isEqualTo("15m");
        assertThat(BaseRigOperation.getMeterFromFreq(24_915_000L)).isEqualTo("12m");
        assertThat(BaseRigOperation.getMeterFromFreq(28_074_000L)).isEqualTo("10m");
        assertThat(BaseRigOperation.getMeterFromFreq(50_313_000L)).isEqualTo("6m");
        assertThat(BaseRigOperation.getMeterFromFreq(144_174_000L)).isEqualTo("2m");
        assertThat(BaseRigOperation.getMeterFromFreq(222_000_000L)).isEqualTo("1.25m");
        assertThat(BaseRigOperation.getMeterFromFreq(432_174_000L)).isEqualTo("70cm");
        assertThat(BaseRigOperation.getMeterFromFreq(915_000_000L)).isEqualTo("33cm");
        assertThat(BaseRigOperation.getMeterFromFreq(1_296_000_000L)).isEqualTo("23cm");
    }

    @Test
    public void getMeterFromFreq_zero_returnsEmpty() {
        // freq == 0 hits the calculation fallback's guard clause.
        assertThat(BaseRigOperation.getMeterFromFreq(0L)).isEmpty();
    }

    @Test
    public void getMeterFromFreq_outOfBand_fallsBackToCalculation() {
        // 12 MHz is between 30m and 20m -> no named band -> wavelength computed
        // as 300e6/freq = 25 m, rounded to nearest 10 m.
        assertThat(BaseRigOperation.getMeterFromFreq(12_000_000L)).isEqualTo("30m");
        // 100 MHz -> 3 m, in the "< 20 m, report in metres" branch.
        assertThat(BaseRigOperation.getMeterFromFreq(100_000_000L)).isEqualTo("3m");
        // 600 MHz -> 0.5 m -> centimetre branch, rounded to nearest 10 cm.
        assertThat(BaseRigOperation.getMeterFromFreq(600_000_000L)).isEqualTo("50cm");
    }

    @Test
    public void byteToStr_hexEncodesEachByteWithTrailingSpace() {
        byte[] in = {0x00, (byte) 0xFF, 0x10};
        assertThat(BaseRigOperation.byteToStr(in)).isEqualTo("0x00 0xff 0x10 ");
    }

    @Test
    public void byteToStr_emptyArray_returnsEmpty() {
        assertThat(BaseRigOperation.byteToStr(new byte[0])).isEmpty();
    }
}
