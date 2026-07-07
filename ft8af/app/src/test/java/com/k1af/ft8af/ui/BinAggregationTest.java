package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests for {@link BinAggregation} (issue #428): the spectrum-strip pair
 * combine extracted from ColumnarView. MODE_MAX must reproduce the legacy
 * inline expression bit-for-bit (including the last-index tail case) so the
 * default renders identically to pre-#428 builds. Pure math — no Robolectric.
 */
public class BinAggregationTest {

    /** The exact pre-#428 ColumnarView expression, kept as the oracle. */
    private static int legacyMax(int[] data, int i) {
        return (i + 1 < data.length) ? Math.max(data[i], data[i + 1]) : data[i];
    }

    @Test
    public void maxModeMatchesLegacyExpressionAtEveryIndex() {
        int[] data = {0, 255, 17, 17, 200, 3, 128};
        for (int i = 0; i < data.length; i++) {
            assertThat(BinAggregation.combine(data, i, BinAggregation.MODE_MAX))
                    .isEqualTo(legacyMax(data, i));
        }
    }

    @Test
    public void lastBinStandsAloneInEveryMode() {
        int[] data = {10, 20, 42};
        assertThat(BinAggregation.combine(data, 2, BinAggregation.MODE_MAX)).isEqualTo(42);
        assertThat(BinAggregation.combine(data, 2, BinAggregation.MODE_AVG)).isEqualTo(42);
        assertThat(BinAggregation.combine(data, 2, BinAggregation.MODE_RMS)).isEqualTo(42);
    }

    @Test
    public void avgModeRoundsHalfUpAndKeepsEndpointsStable() {
        assertThat(BinAggregation.combine(new int[] {10, 20}, 0, BinAggregation.MODE_AVG))
                .isEqualTo(15);
        // Half up: (10 + 15 + 1) / 2 == 13.
        assertThat(BinAggregation.combine(new int[] {10, 15}, 0, BinAggregation.MODE_AVG))
                .isEqualTo(13);
        // Saturated input stays saturated (no off-by-one dimming at full scale).
        assertThat(BinAggregation.combine(new int[] {255, 255}, 0, BinAggregation.MODE_AVG))
                .isEqualTo(255);
        assertThat(BinAggregation.combine(new int[] {0, 0}, 0, BinAggregation.MODE_AVG))
                .isEqualTo(0);
    }

    @Test
    public void rmsModeSitsBetweenAvgAndMax() {
        // RMS(0, 255) = 255/sqrt(2) ~= 180.
        int rms = BinAggregation.combine(new int[] {0, 255}, 0, BinAggregation.MODE_RMS);
        assertThat(rms).isEqualTo(180);
        int avg = BinAggregation.combine(new int[] {0, 255}, 0, BinAggregation.MODE_AVG);
        int max = BinAggregation.combine(new int[] {0, 255}, 0, BinAggregation.MODE_MAX);
        assertThat(rms).isAtLeast(avg);
        assertThat(rms).isAtMost(max);
        // Equal inputs are a fixed point in every mode.
        assertThat(BinAggregation.combine(new int[] {77, 77}, 0, BinAggregation.MODE_RMS))
                .isEqualTo(77);
    }

    @Test
    public void unknownModeFallsBackToMax() {
        int[] data = {5, 9};
        assertThat(BinAggregation.combine(data, 0, 99)).isEqualTo(legacyMax(data, 0));
        assertThat(BinAggregation.combine(data, 0, -1)).isEqualTo(legacyMax(data, 0));
    }

    @Test
    public void resultIsClampedTo0To255() {
        // Display ints should already be 0..255, but a bad frame must not
        // produce an out-of-range bar height.
        assertThat(BinAggregation.combine(new int[] {300, 400}, 0, BinAggregation.MODE_MAX))
                .isEqualTo(255);
        assertThat(BinAggregation.combine(new int[] {-20, -5}, 0, BinAggregation.MODE_MAX))
                .isEqualTo(0);
        assertThat(BinAggregation.combine(new int[] {-20, -5}, 0, BinAggregation.MODE_AVG))
                .isEqualTo(0);
    }
}
