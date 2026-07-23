package com.k1af.ft8af.maidenhead;

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
        // FN42aa: the south-west subsquare of FN42. Subsquares span 1/24° lat and
        // 2/24° lng (letters a–x), so the 'aa' centroid sits half a subsquare in.
        LatLng p = MaidenheadGrid.gridToLatLng("FN42aa");
        assertThat(p).isNotNull();
        // Sub-square centroid lat: 42 + (0.5 * 1/24) ≈ 42.0208
        assertThat(p.latitude).isWithin(POS_TOL).of(42.021);
        // Sub-square centroid lng: -72 + (0.5 * 2/24) ≈ -71.9583
        assertThat(p.longitude).isWithin(POS_TOL).of(-71.958);
    }

    @Test
    public void gridToLatLng_sixChar_highSubsquareMatchesRealLocation() {
        // Regression for the subsquare divisor: the a–x third pair has 24
        // divisions per axis, not 18. The +0.5 centre offset masks the error for
        // the low letters ('aa'), so use a high subsquare — IO91wm is the classic
        // central-London locator, true centre ≈ (51.52, -0.125). With the old /18
        // divisor this resolved to (51.69, +0.5): ~45 km east and into the wrong
        // hemisphere of the prime meridian.
        LatLng p = MaidenheadGrid.gridToLatLng("IO91wm");
        assertThat(p).isNotNull();
        assertThat(p.latitude).isWithin(POS_TOL).of(51.521);
        assertThat(p.longitude).isWithin(POS_TOL).of(-0.125);
    }

    @Test
    public void gridToPolygon_sixChar_cellSpansOneSubsquare() {
        // A 6-char cell outline must be exactly one subsquare: 1/24° tall and
        // 2/24° wide. The /18 divisor inflated it to 1/18° × 2/18° (≈33% too big),
        // drawing overlapping grid squares on the map.
        LatLng[] poly = MaidenheadGrid.gridToPolygon("IO91wm");
        assertThat(poly).isNotNull();
        double minLat = Math.min(Math.min(poly[0].latitude, poly[1].latitude),
                Math.min(poly[2].latitude, poly[3].latitude));
        double maxLat = Math.max(Math.max(poly[0].latitude, poly[1].latitude),
                Math.max(poly[2].latitude, poly[3].latitude));
        double minLng = Math.min(Math.min(poly[0].longitude, poly[1].longitude),
                Math.min(poly[2].longitude, poly[3].longitude));
        double maxLng = Math.max(Math.max(poly[0].longitude, poly[1].longitude),
                Math.max(poly[2].longitude, poly[3].longitude));
        assertThat(maxLat - minLat).isWithin(1e-4).of(1.0 / 24.0);
        assertThat(maxLng - minLng).isWithin(1e-4).of(2.0 / 24.0);
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

    @Test
    public void gridToLatLng_rightLengthWrongAlphabet_returnsNull() {
        // A token of a legal length (2/4/6) but that is not a Maidenhead locator
        // must be rejected, not coerced into an arbitrary LatLng. Before this
        // guard an ADIF GRIDSQUARE of "1234" (or any 4-char junk) decoded to a
        // bogus point that was plotted on the map and fed into distance stats.
        assertThat(MaidenheadGrid.gridToLatLng("1234")).isNull(); // digits in field slots
        assertThat(MaidenheadGrid.gridToLatLng("FN4X")).isNull(); // letter in a digit slot
        assertThat(MaidenheadGrid.gridToLatLng("ZZ99")).isNull(); // field letters past R
        assertThat(MaidenheadGrid.gridToLatLng("AB1@")).isNull(); // symbol in a digit slot
        assertThat(MaidenheadGrid.gridToLatLng("FN42zz")).isNull(); // subsquare past x
        assertThat(MaidenheadGrid.gridToLatLng("1A")).isNull();   // 2-char field with a digit
    }

    @Test
    public void gridToLatLng_validGridsStillDecode() {
        // Regression guard: the alphabet check must be a no-op for real locators
        // of every supported length and case.
        assertThat(MaidenheadGrid.gridToLatLng("FN")).isNotNull();
        assertThat(MaidenheadGrid.gridToLatLng("FN42")).isNotNull();
        assertThat(MaidenheadGrid.gridToLatLng("IO91wm")).isNotNull();
        assertThat(MaidenheadGrid.gridToLatLng("io91WM")).isNotNull(); // mixed case
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

    @Test
    public void getGridSquare_northPoleStaysWithinFieldRange() {
        // A GPS fix at the North Pole (lat == 90) drove the latitude field index
        // to 18 — one past the legal A-R range — emitting the letter 'S', i.e. an
        // invalid locator that was then written to config as the operator's grid,
        // transmitted in FT8 messages, and uploaded to PSKReporter. (Play-Services
        // LatLng clamps latitude to [-90, 90] so 90 survives; it normalizes
        // longitude 180 -> -180, so the antimeridian overflow is only reachable in
        // the raw math — see MaidenheadGridSquareTest.) The field letters must stay
        // in A-R.
        String grid = MaidenheadGrid.getGridSquare(new LatLng(90.0, 0.0));
        assertThat(MaidenheadGrid.checkMaidenhead(grid)).isTrue();
        assertThat(grid.charAt(0)).isAtMost('R');
        assertThat(grid.charAt(1)).isAtMost('R');
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
    public void getDist_samePointNeverProducesNaN() {
        // Regression: for two stations in the SAME Maidenhead grid, gridToLatLng
        // resolves both to the identical grid centre. The great-circle dot
        // product then rounds to fractionally above 1.0 for ~2% of grids, and an
        // unclamped Math.acos(>1) returns NaN — surfacing as "NaN km" in the log
        // and calling-list distance column and silently dropping the contact from
        // the distance statistics. AI04's centre (-5.5, -179.0) is one such point.
        LatLng ai04 = new LatLng(-5.5, -179.0);
        double d = MaidenheadGrid.getDist(ai04, ai04);
        assertThat(Double.isNaN(d)).isFalse();
        assertThat(d).isWithin(DIST_TOL).of(0.0);
    }

    @Test
    public void getDistStr_sameNaNProneGridIsBlankOrZero() {
        // The same fault seen through the display formatter: getDistStr checks
        // dist == 0 (false for NaN) then formats, so before the acos clamp this
        // rendered the literal "NaN km"/"NaN mi". It must be "" or "0 <unit>".
        String label = MaidenheadGrid.getDistUnitLabel();
        assertThat(MaidenheadGrid.getDistStr("AI04", "AI04")).isAnyOf("", "0 " + label);
        assertThat(MaidenheadGrid.getDistStrEN("AI04", "AI04")).isAnyOf("", "0 " + label);
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

    // ---------- gridToLatLng additional coverage ----------

    @Test
    public void gridToLatLng_twoCharGrid_returnsFieldCenter() {
        // "FN" field: longitude field index 5 (F), latitude field index 13 (N).
        // The 2-char result is the centre of the field: half a field added to the
        // SW corner (lat field = 10° tall, lng field = 20° wide).
        // lat = 13*10 - 90 + 5 = 45.0; lng = 5*20 - 180 + 10 = -70.0
        LatLng p = MaidenheadGrid.gridToLatLng("FN");
        assertThat(p).isNotNull();
        assertThat(p.latitude).isWithin(POS_TOL).of(45.0);
        assertThat(p.longitude).isWithin(POS_TOL).of(-70.0);
    }

    @Test
    public void gridToLatLng_southernEasternHemisphere() {
        // OF is roughly central/southern Australia. O=14, F=5.
        // lat = 5*10 - 90 + (5+0.5) [from "OF66" digits 6/6] ...
        // Use OF66: lng field O=14 -> 14*20-180=100; lng sq 6*2=12 +0.5? (4-char adds .5)
        // lat field F=5 -> 5*10-90=-40; lat sq 6+0.5=6.5 -> -33.5
        LatLng p = MaidenheadGrid.gridToLatLng("OF66");
        assertThat(p).isNotNull();
        // latitude: 5*10 - 90 + (6 + 0.5) = -33.5
        assertThat(p.latitude).isWithin(POS_TOL).of(-33.5);
        // longitude: 14*20 - 180 + (6 + 0.5)*2 = 100 + 13 = 113.0
        assertThat(p.longitude).isWithin(POS_TOL).of(113.0);
    }

    @Test
    public void gridToLatLng_rrAlsoRejected() {
        // The bare "RR" greeting collision is rejected just like "RR73".
        assertThat(MaidenheadGrid.gridToLatLng("RR")).isNull();
    }

    @Test
    public void gridToLatLng_clampsBelowMinus85() {
        // AA is the south-west origin field; AA00 sits well below -85 lat once
        // the floor (-90) is approached, so it must clamp to -85.
        LatLng p = MaidenheadGrid.gridToLatLng("AA00");
        assertThat(p).isNotNull();
        assertThat(p.latitude).isAtLeast(-85.0);
    }

    // ---------- gridToPolygon ----------

    @Test
    public void gridToPolygon_returnsFourCornersBoundingTheCenter() {
        LatLng[] poly = MaidenheadGrid.gridToPolygon("FN42");
        assertThat(poly).isNotNull();
        assertThat(poly).hasLength(4);
        // The polygon corners must bracket the FN42 center (~42.5N, -71.0W).
        double minLat = Math.min(Math.min(poly[0].latitude, poly[1].latitude),
                Math.min(poly[2].latitude, poly[3].latitude));
        double maxLat = Math.max(Math.max(poly[0].latitude, poly[1].latitude),
                Math.max(poly[2].latitude, poly[3].latitude));
        double minLng = Math.min(Math.min(poly[0].longitude, poly[1].longitude),
                Math.min(poly[2].longitude, poly[3].longitude));
        double maxLng = Math.max(Math.max(poly[0].longitude, poly[1].longitude),
                Math.max(poly[2].longitude, poly[3].longitude));
        assertThat(minLat).isWithin(POS_TOL).of(42.0);
        assertThat(maxLat).isWithin(POS_TOL).of(43.0);
        assertThat(minLng).isWithin(POS_TOL).of(-72.0);
        assertThat(maxLng).isWithin(POS_TOL).of(-70.0);
    }

    @Test
    public void gridToPolygon_badLength_returnsNull() {
        assertThat(MaidenheadGrid.gridToPolygon("ABC")).isNull();
        assertThat(MaidenheadGrid.gridToPolygon("ABCDE")).isNull();
    }

    @Test
    public void gridToPolygon_nullReturnsNullNotNPE() {
        // Unlike its sibling gridToLatLng, gridToPolygon had no null guard and
        // NPE'd on grid.length(). A null gridsquare reaching the GridPolygon
        // overlay must yield null, not throw.
        assertThat(MaidenheadGrid.gridToPolygon(null)).isNull();
    }

    @Test
    public void gridToPolygon_rightLengthWrongAlphabet_returnsNull() {
        // Same alphabet contract as gridToLatLng: a legal-length non-locator
        // token must be rejected rather than drawn as a bogus cell outline.
        assertThat(MaidenheadGrid.gridToPolygon("1234")).isNull();
        assertThat(MaidenheadGrid.gridToPolygon("FN4X")).isNull();
        assertThat(MaidenheadGrid.gridToPolygon("ZZ99")).isNull();
    }

    @Test
    public void gridToPolygon_cornersFormARectangle() {
        // latLngs[0]/[1] share lat1; [2]/[3] share lat2; [0]/[3] share lng1;
        // [1]/[2] share lng2 — i.e. an axis-aligned box.
        LatLng[] poly = MaidenheadGrid.gridToPolygon("IO91");
        assertThat(poly).isNotNull();
        assertThat(poly[0].latitude).isEqualTo(poly[1].latitude);
        assertThat(poly[2].latitude).isEqualTo(poly[3].latitude);
        assertThat(poly[0].longitude).isEqualTo(poly[3].longitude);
        assertThat(poly[1].longitude).isEqualTo(poly[2].longitude);
    }

    // ---------- getGridSquare round-trips ----------

    @Test
    public void getGridSquare_sixCharInputStillReturnsFourChars() {
        // getGridSquare always truncates to the first 4 characters.
        String grid = MaidenheadGrid.getGridSquare(MaidenheadGrid.gridToLatLng("FN42aa"));
        assertThat(grid).hasLength(4);
        assertThat(grid).isEqualTo("FN42");
    }

    @Test
    public void getGridSquare_southernHemisphereRoundTrip() {
        // Sydney-ish; QF56 is the standard locator there.
        String grid = MaidenheadGrid.getGridSquare(new LatLng(-33.87, 151.21));
        assertThat(grid).isEqualTo("QF56");
    }

    // ---------- getDistLatLngStr ----------

    @Test
    public void getDistLatLngStr_formatsWithCurrentUnit() {
        String label = MaidenheadGrid.getDistUnitLabel();
        LatLng london = new LatLng(51.5074, -0.1278);
        LatLng newYork = new LatLng(40.7128, -74.0060);
        String s = MaidenheadGrid.getDistLatLngStr(london, newYork);
        assertThat(s).endsWith(label);
        // Distance is ~5570 km / ~3461 mi -> a multi-digit number plus unit.
        assertThat(s).matches("\\d+ " + label);
    }

    @Test
    public void getDistLatLngStr_samePointIsZeroWithUnit() {
        String label = MaidenheadGrid.getDistUnitLabel();
        LatLng p = new LatLng(40.0, -75.0);
        assertThat(MaidenheadGrid.getDistLatLngStr(p, p)).isEqualTo("0 " + label);
    }

    // ---------- convertDist / formatDist structural ----------

    @Test
    public void convertDist_isIdentityInKmAndScalesInMiles() {
        // We can't force the GeneralVariables flag here, but we CAN assert the
        // relationship: convertDist(x) equals x when unit is km, or x*0.621371
        // when miles. Derive expectation from the live unit label.
        double km = 100.0;
        double got = MaidenheadGrid.convertDist(km);
        if (MaidenheadGrid.getDistUnitLabel().equals("km")) {
            assertThat(got).isWithin(1e-6).of(100.0);
        } else {
            assertThat(got).isWithin(1e-3).of(62.1371);
        }
    }

    @Test
    public void getDistUnitLabel_matchesFormatDistSuffix() {
        // formatDist must end with whatever getDistUnitLabel reports.
        String label = MaidenheadGrid.getDistUnitLabel();
        assertThat(MaidenheadGrid.formatDist(123.0)).endsWith(label);
    }

    // ---------- checkMaidenhead additional ----------

    @Test
    public void checkMaidenhead_acceptsSixCharByPrefixRule() {
        // checkMaidenhead only validates length (4 or 6) plus the first four
        // characters' shape; a structurally valid 6-char grid passes.
        assertThat(MaidenheadGrid.checkMaidenhead("FN42ab")).isTrue();
    }

    @Test
    public void checkMaidenhead_rejectsTwoCharField() {
        // Unlike gridToLatLng, the validator does not accept 2-char fields.
        assertThat(MaidenheadGrid.checkMaidenhead("FN")).isFalse();
    }
}
