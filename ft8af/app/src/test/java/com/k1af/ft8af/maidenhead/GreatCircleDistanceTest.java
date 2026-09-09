package com.k1af.ft8af.maidenhead;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link MaidenheadGrid#greatCircleDistanceKm}, the
 * geometry extracted from {@code getDist(LatLng, LatLng)} so the {@code acos}
 * domain clamp can be tested without the Play-Services {@code LatLng} type
 * (i.e. no Robolectric runner needed).
 *
 * <p>The clamp fixes a real defect: for two stations in the same Maidenhead
 * grid their grid centres coincide, and the unclamped {@code Math.acos(cos)}
 * returned {@link Double#NaN} for ~2% of grids, which rendered as "NaN km" in
 * the distance column and dropped the contact from the distance statistics.
 */
public class GreatCircleDistanceTest {

    private static final double KM_TOL = 20.0;

    @Test
    public void identicalPoint_isZeroNotNaN() {
        // (-5.5, -179.0) is the centre of grid AI04 — one of the coordinates
        // whose self-distance rounded cos just above 1.0 and produced NaN
        // before the clamp.
        double d = MaidenheadGrid.greatCircleDistanceKm(-5.5, -179.0, -5.5, -179.0);
        assertThat(Double.isNaN(d)).isFalse();
        assertThat(d).isWithin(1e-6).of(0.0);
    }

    @Test
    public void identicalPoints_neverNaNAcrossTheGlobe() {
        // Scan a dense grid of coincident points; none may yield NaN.
        for (int latTenths = -900; latTenths <= 900; latTenths += 5) {
            double lat = latTenths / 10.0;
            for (int lngTenths = -1800; lngTenths <= 1800; lngTenths += 5) {
                double lng = lngTenths / 10.0;
                double d = MaidenheadGrid.greatCircleDistanceKm(lat, lng, lat, lng);
                assertThat(Double.isNaN(d)).isFalse();
                assertThat(d).isWithin(1e-3).of(0.0);
            }
        }
    }

    @Test
    public void knownGreatCircleBaseline_londonToNewYork() {
        // Canonical great-circle distance ~5570 km.
        double d = MaidenheadGrid.greatCircleDistanceKm(51.5074, -0.1278, 40.7128, -74.0060);
        assertThat(d).isWithin(KM_TOL).of(5570.0);
    }

    @Test
    public void antipodalPoints_isHalfCircumference() {
        // Exactly opposite points: distance is half Earth's circumference
        // (~20015 km). cos = -1 here, so this also exercises the lower clamp.
        double d = MaidenheadGrid.greatCircleDistanceKm(0.0, 0.0, 0.0, 180.0);
        assertThat(Double.isNaN(d)).isFalse();
        assertThat(d).isWithin(KM_TOL).of(20015.0);
    }

    @Test
    public void symmetric_distanceIsOrderIndependent() {
        double ab = MaidenheadGrid.greatCircleDistanceKm(41.0, -71.0, 51.0, 0.0);
        double ba = MaidenheadGrid.greatCircleDistanceKm(51.0, 0.0, 41.0, -71.0);
        assertThat(ab).isWithin(1e-6).of(ba);
    }
}
