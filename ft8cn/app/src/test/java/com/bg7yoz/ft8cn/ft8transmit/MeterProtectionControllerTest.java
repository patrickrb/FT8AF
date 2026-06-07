package com.bg7yoz.ft8cn.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for the two pure static converters on {@link MeterProtectionController}
 * that map between a normalized 0-255 SWR meter value and a human-readable SWR
 * ratio string. They are pure arithmetic (piecewise-linear), so exact values are
 * asserted. The ratio strings use {@code String.format("%.1f:1", ...)} with the
 * default locale, but all expected values here have a trailing ".x" with no
 * grouping, so they are locale-stable for the en/zh locales used in CI.
 */
public class MeterProtectionControllerTest {

    // ---- normalizedSwrToRatio -----------------------------------------------

    @Test
    public void normalizedToRatio_atOrBelowZero_isOneToOne() {
        assertThat(MeterProtectionController.normalizedSwrToRatio(0)).isEqualTo("1.0:1");
        assertThat(MeterProtectionController.normalizedSwrToRatio(-5)).isEqualTo("1.0:1");
    }

    @Test
    public void normalizedToRatio_atOrAbove255_isCappedHigh() {
        assertThat(MeterProtectionController.normalizedSwrToRatio(255)).isEqualTo(">10:1");
        assertThat(MeterProtectionController.normalizedSwrToRatio(300)).isEqualTo(">10:1");
    }

    @Test
    public void normalizedToRatio_lowBand_endsAtOnePointFive() {
        // n=24 -> 1.0 + 24/60*0.5 = 1.2; n=60 -> 1.5 (band boundary).
        assertThat(MeterProtectionController.normalizedSwrToRatio(24)).isEqualTo("1.2:1");
        assertThat(MeterProtectionController.normalizedSwrToRatio(60)).isEqualTo("1.5:1");
    }

    @Test
    public void normalizedToRatio_midBand_reachesThreeToOne() {
        // n=120 -> 1.5 + (120-60)/60*1.5 = 3.0.
        assertThat(MeterProtectionController.normalizedSwrToRatio(120)).isEqualTo("3.0:1");
    }

    @Test
    public void normalizedToRatio_upperBand_reachesSevenToOne() {
        // n=160 -> 3.0 + (160-120)/80*4.0 = 5.0; n=200 -> 7.0.
        assertThat(MeterProtectionController.normalizedSwrToRatio(160)).isEqualTo("5.0:1");
        assertThat(MeterProtectionController.normalizedSwrToRatio(200)).isEqualTo("7.0:1");
    }

    @Test
    public void normalizedToRatio_topBand_approachesTen() {
        // n=250 -> 7.0 + (250-200)/55*3.0 = 9.727... -> "9.7:1".
        assertThat(MeterProtectionController.normalizedSwrToRatio(250)).isEqualTo("9.7:1");
    }

    // ---- swrRatioToNormalized -----------------------------------------------

    @Test
    public void ratioToNormalized_atOrBelowOne_isZero() {
        assertThat(MeterProtectionController.swrRatioToNormalized(1.0f)).isEqualTo(0);
        assertThat(MeterProtectionController.swrRatioToNormalized(0.5f)).isEqualTo(0);
    }

    @Test
    public void ratioToNormalized_bandBoundaries() {
        // Each piecewise boundary should land exactly on the cumulative offset.
        assertThat(MeterProtectionController.swrRatioToNormalized(1.5f)).isEqualTo(60);
        assertThat(MeterProtectionController.swrRatioToNormalized(3.0f)).isEqualTo(120);
        assertThat(MeterProtectionController.swrRatioToNormalized(7.0f)).isEqualTo(200);
        assertThat(MeterProtectionController.swrRatioToNormalized(10.0f)).isEqualTo(255);
    }

    @Test
    public void ratioToNormalized_midPoints() {
        // 5.0 -> 120 + round((5-3)/4*80) = 120 + 40 = 160.
        assertThat(MeterProtectionController.swrRatioToNormalized(5.0f)).isEqualTo(160);
        // 2.25 -> 60 + round((2.25-1.5)/1.5*60) = 60 + 30 = 90.
        assertThat(MeterProtectionController.swrRatioToNormalized(2.25f)).isEqualTo(90);
    }

    @Test
    public void ratioToNormalized_aboveTen_capsAt255() {
        assertThat(MeterProtectionController.swrRatioToNormalized(15.0f)).isEqualTo(255);
    }

    @Test
    public void roundTrip_boundariesAreStable() {
        // ratio -> normalized -> ratio reproduces the boundary strings.
        int n = MeterProtectionController.swrRatioToNormalized(3.0f); // 120
        assertThat(MeterProtectionController.normalizedSwrToRatio(n)).isEqualTo("3.0:1");
    }
}
