package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.ft8transmit.MeterProtectionController;

import org.junit.Test;

/**
 * Pure-logic coverage for the Lab599 Discovery TX-500 SWR meter mapping (#599).
 *
 * The rig reports SWR as a raw 0000-0030 field; per Lab599's published chart the
 * relationship to actual SWR is linear ({@code cat = 3 * (swr - 1)}), and the value
 * handed to {@link MeterProtectionController} must sit on the same 0-255 scale the
 * SWR-halt threshold is stored on, or protection can never trip.
 */
public class DiscoveryTX500RigTest {

    @Test
    public void catToSwrRatio_matchesPublishedChart() {
        // cat = 3 * (swr - 1): a few anchor points straight from the chart.
        assertThat(DiscoveryTX500Rig.tx500CatToSwrRatio(0)).isEqualTo(1.0f);
        assertThat(DiscoveryTX500Rig.tx500CatToSwrRatio(6)).isEqualTo(3.0f);   // 3.0:1
        assertThat(DiscoveryTX500Rig.tx500CatToSwrRatio(9)).isEqualTo(4.0f);   // 4.0:1
        assertThat(DiscoveryTX500Rig.tx500CatToSwrRatio(18)).isEqualTo(7.0f);  // 7.0:1
        assertThat(DiscoveryTX500Rig.tx500CatToSwrRatio(27)).isEqualTo(10.0f); // 10:1
    }

    @Test
    public void catToSwrRatio_neverBelowUnity() {
        assertThat(DiscoveryTX500Rig.tx500CatToSwrRatio(-1)).isEqualTo(1.0f);
        assertThat(DiscoveryTX500Rig.tx500CatToSwrRatio(0)).isEqualTo(1.0f);
    }

    @Test
    public void catToSwrRatio_isMonotonic() {
        float prev = -1f;
        for (int cat = 0; cat <= 30; cat++) {
            float ratio = DiscoveryTX500Rig.tx500CatToSwrRatio(cat);
            assertThat(ratio).isAtLeast(prev);
            prev = ratio;
        }
    }

    @Test
    public void catToNormalizedSwr_alignsWithHaltThresholdScale() {
        // cat 6 == 3.0:1 == the default halt threshold (120) once normalized.
        assertThat(DiscoveryTX500Rig.tx500CatToNormalizedSwr(6))
                .isEqualTo(MeterProtectionController.swrRatioToNormalized(3.0f));
        assertThat(DiscoveryTX500Rig.tx500CatToNormalizedSwr(6)).isEqualTo(120);

        // A clean match (cat 0) and a high reading (cat 30 -> >10:1 -> clamps to 255).
        assertThat(DiscoveryTX500Rig.tx500CatToNormalizedSwr(0)).isEqualTo(0);
        assertThat(DiscoveryTX500Rig.tx500CatToNormalizedSwr(30)).isEqualTo(255);
    }

    @Test
    public void catToNormalizedSwr_tripsHaltAboveDefaultThreshold() {
        // Default swr-halt threshold is 120 (~3.0:1); a reading of 23 (~8.7:1) must halt.
        int normalized = DiscoveryTX500Rig.tx500CatToNormalizedSwr(23);
        assertThat(MeterProtectionController.shouldHaltForSwr(normalized, true, 120)).isTrue();

        // A low reading (cat 3 ~= 2.0:1) must NOT halt at the default threshold.
        int low = DiscoveryTX500Rig.tx500CatToNormalizedSwr(3);
        assertThat(MeterProtectionController.shouldHaltForSwr(low, true, 120)).isFalse();
    }
}
