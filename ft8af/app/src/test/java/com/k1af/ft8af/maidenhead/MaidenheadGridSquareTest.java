package com.k1af.ft8af.maidenhead;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM tests for {@link MaidenheadGrid#gridSquareFor(double, double)} — the raw
 * latitude/longitude -> Maidenhead grid math, extracted from
 * {@link MaidenheadGrid#getGridSquare} so the boundary clamps can be exercised without a
 * Play-Services {@code LatLng} (which clamps latitude and normalizes longitude before the
 * math ever runs, hiding the +180 antimeridian case). No Robolectric runner needed: the
 * method touches no Android types.
 */
public class MaidenheadGridSquareTest {

    // ---------- normal coordinates are byte-identical to the historical output ----------

    @Test
    public void gridSquareFor_knownLocationsUnchanged() {
        // Boston ~ FN42, London ~ IO91, Tokyo ~ PM95, Sydney ~ QF56. These reach no clamp
        // ceiling, so the clamped path must be identical to the pre-fix output.
        assertThat(MaidenheadGrid.gridSquareFor(42.5, -71.0)).isEqualTo("FN42");
        assertThat(MaidenheadGrid.gridSquareFor(51.5, -0.1)).isEqualTo("IO91");
        assertThat(MaidenheadGrid.gridSquareFor(35.68, 139.77)).isEqualTo("PM95");
        assertThat(MaidenheadGrid.gridSquareFor(-33.87, 151.21)).isEqualTo("QF56");
    }

    @Test
    public void gridSquareFor_producesValidLocatorAcrossTheGlobe() {
        // Sweep a coarse grid of coordinates; every result must be a structurally valid
        // 4-character Maidenhead locator (fields A-R, digits 0-9). Asserted on the raw
        // character ranges rather than checkMaidenhead, which additionally rejects the
        // "RR73" sign-off token even though it is a geometrically valid locator.
        for (double lat = -89; lat <= 89; lat += 7.5) {
            for (double lon = -179; lon <= 179; lon += 7.5) {
                assertValidLocator(MaidenheadGrid.gridSquareFor(lat, lon));
            }
        }
    }

    // ---------- boundary / out-of-range coordinates stay valid (the fix) ----------

    @Test
    public void gridSquareFor_plus180AntimeridianClampsToFieldR() {
        // lon == 180 pushed the longitude field index to 18 -> 'S' (one past A-R).
        // It must fold onto the easternmost field 'R'.
        String grid = MaidenheadGrid.gridSquareFor(0.0, 180.0);
        assertThat(MaidenheadGrid.checkMaidenhead(grid)).isTrue();
        assertThat(grid.charAt(0)).isEqualTo('R');
        assertThat(grid.charAt(0)).isAtMost('R');
    }

    @Test
    public void gridSquareFor_northPoleClampsToFieldR() {
        // lat == 90 pushed the latitude field index to 18 -> 'S'. It must fold onto the
        // northernmost field 'R'.
        String grid = MaidenheadGrid.gridSquareFor(90.0, 0.0);
        assertThat(MaidenheadGrid.checkMaidenhead(grid)).isTrue();
        assertThat(grid.charAt(1)).isEqualTo('R');
        assertThat(grid.charAt(1)).isAtMost('R');
    }

    @Test
    public void gridSquareFor_southPoleAndWestAntimeridianAreLowerBound() {
        // The low edges were already in range (index 0 -> 'A'); guard against a
        // regression from an over-eager clamp.
        assertThat(MaidenheadGrid.gridSquareFor(-90.0, -180.0)).isEqualTo("AA00");
    }

    @Test
    public void gridSquareFor_outOfRangeCoordinatesStillYieldValidLocator() {
        // Defensive: even nonsensical coordinates (garbage fix, uncorrected sensor) must
        // never emit a character outside a legal Maidenhead pair.
        for (double lat : new double[] {-1000, -90.0001, 90.0001, 1000}) {
            for (double lon : new double[] {-1000, -180.0001, 180.0001, 1000}) {
                assertValidLocator(MaidenheadGrid.gridSquareFor(lat, lon));
            }
        }
    }

    /** Assert a 4-char string is a structurally valid Maidenhead locator: fields A-R, digits 0-9. */
    private static void assertValidLocator(String grid) {
        assertThat(grid).hasLength(4);
        assertThat(grid.charAt(0)).isAtLeast('A');
        assertThat(grid.charAt(0)).isAtMost('R');
        assertThat(grid.charAt(1)).isAtLeast('A');
        assertThat(grid.charAt(1)).isAtMost('R');
        assertThat(Character.isDigit(grid.charAt(2))).isTrue();
        assertThat(Character.isDigit(grid.charAt(3))).isTrue();
    }
}
