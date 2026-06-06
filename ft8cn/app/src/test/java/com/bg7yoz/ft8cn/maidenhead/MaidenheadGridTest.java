package com.bg7yoz.ft8cn.maidenhead;

import static com.google.common.truth.Truth.assertThat;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Exercise the Maidenhead grid math: grid->LatLng, LatLng->grid, distance,
 * and the format validator. Robolectric is required because the production
 * class uses Google Play Services {@code LatLng} and Android framework types
 * elsewhere in the file.
 *
 * Reference values were cross-checked against the published Maidenhead
 * locator system definition and HamWaves' online calculator.
 */
@RunWith(RobolectricTestRunner.class)
public class MaidenheadGridTest {

    private static final double POS_TOL = 0.05;   // degrees
    private static final double DIST_TOL = 5.0;   // kilometres

    // ---------- gridToLatLng ----------

    @Test
    public void gridToLatLng_fourChar_returnsCenterOfSquare() {
        // FN42 is roughly Boston, MA — center should fall around 42.5N, -71.0W.
        LatLng p = MaidenheadGrid.gridToLatLng("FN42");
        assertThat(p).isNotNull();
        assertThat(p.latitude).isWithin(POS_TOL).of(42.5);
        assertThat(p.longitude).isWithin(POS_TOL).of(-71.0);
    }

    @Test
    public void gridToLatLng_sixChar_narrowsToSubsquare() {
        // FN42aa: south-west corner of FN42.
        LatLng p = MaidenheadGrid.gridToLatLng("FN42aa");
        assertThat(p).isNotNull();
        // Sub-square centroid lat: 42 + (0.5 * 1/18) ≈ 42.0278
        assertThat(p.latitude).isWithin(POS_TOL).of(42.028);
        // Sub-square centroid lng: -72 + (0.5 * 2/18) ≈ -71.944
        assertThat(p.longitude).isWithin(POS_TOL).of(-71.944);
    }

    @Test
    public void gridToLatLng_clampsAboveEightyFiveDegrees() {
        // Anything past lat ±85° is clamped to ±85° so the map projection
        // (Mercator-style) doesn't blow up.
        LatLng p = MaidenheadGrid.gridToLatLng("JR99");
        assertThat(p).isNotNull();
        assertThat(p.latitude).isAtMost(85.0);
    }

    @Test
    public void gridToLatLng_rr73IsRejected() {
        // "RR73" is a 73-greeting collision with the locator parser; the
        // production code explicitly rejects it (line 36) to avoid plotting
        // a fake grid for the sign-off greeting.
        assertThat(MaidenheadGrid.gridToLatLng("RR73")).isNull();
    }

    @Test
    public void gridToLatLng_emptyOrNull_returnsNull() {
        assertThat(MaidenheadGrid.gridToLatLng(null)).isNull();
        assertThat(MaidenheadGrid.gridToLatLng("")).isNull();
    }

    @Test
    public void gridToLatLng_badLength_returnsNull() {
        // Valid Maidenhead lengths are 2/4/6; anything else is rejected.
        assertThat(MaidenheadGrid.gridToLatLng("ABC")).isNull();
        assertThat(MaidenheadGrid.gridToLatLng("ABCDE")).isNull();
        assertThat(MaidenheadGrid.gridToLatLng("ABCDEFG")).isNull();
    }

    // ---------- getGridSquare ----------

    @Test
    public void getGridSquare_roundTripsFourCharCenter() {
        // Convert FN42 center to LatLng, then back; should land in FN42.
        LatLng p = MaidenheadGrid.gridToLatLng("FN42");
        String grid = MaidenheadGrid.getGridSquare(p);
        assertThat(grid).isEqualTo("FN42");
    }

    @Test
    public void getGridSquare_roundTripsKnownLocation() {
        // Boston-ish coordinates.
        String grid = MaidenheadGrid.getGridSquare(new LatLng(42.5, -71.0));
        assertThat(grid).isEqualTo("FN42");
    }

    // ---------- getDist ----------

    @Test
    public void getDist_zeroForSamePoint() {
        LatLng p = new LatLng(40.0, -75.0);
        assertThat(MaidenheadGrid.getDist(p, p)).isWithin(DIST_TOL).of(0.0);
    }

    @Test
    public void getDist_knownGreatCircleBaseline() {
        // London (51.5074, -0.1278) to New York (40.7128, -74.0060):
        // canonical great-circle distance is ~5570 km.
        LatLng london = new LatLng(51.5074, -0.1278);
        LatLng newYork = new LatLng(40.7128, -74.0060);
        double dist = MaidenheadGrid.getDist(london, newYork);
        assertThat(dist).isWithin(20.0).of(5570.0);
    }

    @Test
    public void getDist_betweenGridsMatchesLatLngFormula() {
        // FN42 (Boston ~42N/-71W) <-> IO91 (London ~51N/0W); a well-known
        // transatlantic baseline of ~5300 km.
        double dist = MaidenheadGrid.getDist("FN42", "IO91");
        assertThat(dist).isGreaterThan(5000.0);
        assertThat(dist).isLessThan(5800.0);
    }

    @Test
    public void getDist_invalidGridReturnsZero() {
        // Per the production contract: if either grid fails to parse, return 0.
        // Grids of an unsupported length (3, 5, 7+) are rejected outright;
        // gridToLatLng will not coerce them.
        assertThat(MaidenheadGrid.getDist("FN42", "ABC")).isEqualTo(0.0);
    }

    // ---------- checkMaidenhead ----------

    @Test
    public void checkMaidenhead_acceptsCanonicalFourChar() {
        assertThat(MaidenheadGrid.checkMaidenhead("FN42")).isTrue();
        assertThat(MaidenheadGrid.checkMaidenhead("JN58")).isTrue();
    }

    @Test
    public void checkMaidenhead_rejectsRR73() {
        // Same protection as gridToLatLng — "RR73" looks structurally valid
        // but is the sign-off greeting, not a locator.
        assertThat(MaidenheadGrid.checkMaidenhead("RR73")).isFalse();
    }

    @Test
    public void checkMaidenhead_rejectsBadShapes() {
        assertThat(MaidenheadGrid.checkMaidenhead("12AB")).isFalse(); // digits first
        assertThat(MaidenheadGrid.checkMaidenhead("FNXX")).isFalse(); // letters in digit slot
        assertThat(MaidenheadGrid.checkMaidenhead("FN4")).isFalse();  // wrong length
    }

    // ---------- distance formatting (convertDist / formatDist / getDistStr*) ----------

    @Test
    public void convertDist_zeroIsZeroRegardlessOfUnit() {
        assertThat(MaidenheadGrid.convertDist(0.0)).isEqualTo(0.0);
    }

    @Test
    public void getDistUnitLabel_isKmOrMiles() {
        assertThat(MaidenheadGrid.getDistUnitLabel()).isAnyOf("km", "mi");
    }

    @Test
    public void formatDist_roundsAndAppendsCurrentUnit() {
        // Derive the expected unit from the current setting so the assertion is
        // independent of the km/miles preference.
        String label = MaidenheadGrid.getDistUnitLabel();
        assertThat(MaidenheadGrid.formatDist(0.0)).isEqualTo("0 " + label);
    }

    @Test
    public void getDistStr_samePointIsZeroOrEmpty() {
        // getDistStr returns "" only when the great-circle distance is exactly
        // 0.0; for identical grids haversine float error leaves a tiny non-zero
        // that rounds to "0 <unit>". Accept either.
        String label = MaidenheadGrid.getDistUnitLabel();
        assertThat(MaidenheadGrid.getDistStr("FN42", "FN42")).isAnyOf("", "0 " + label);
        assertThat(MaidenheadGrid.getDistStrEN("FN42", "FN42")).isAnyOf("", "0 " + label);
    }

    @Test
    public void getDistStr_distinctGridsAreNonEmptyWithUnit() {
        String label = MaidenheadGrid.getDistUnitLabel();
        assertThat(MaidenheadGrid.getDistStr("FN42", "IO91")).endsWith(label);
        assertThat(MaidenheadGrid.getDistStrEN("FN42", "IO91")).endsWith(label);
    }
}
