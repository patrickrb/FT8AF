package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Operator position in ADIF: the standard {@code MY_LAT}/{@code MY_LON} Location
 * datatype ({@code XDDD MM.MMM}) and its exact decimal twin.
 *
 * <p>The sign lives entirely in the hemisphere letter, so an error there moves a QSO to
 * the wrong side of the planet rather than merely mislocating it — hence the explicit
 * south/west cases. The 60.000-minute carry is the other trap: rounding a coordinate
 * whose minutes land just under 60 produces a value the format has no way to express.
 * Pure JUnit.
 */
public class AdifLocationTest {

    @Test
    public void location_formatsNorthAndWestWithFixedWidth() {
        // Denver: 39.7392, -104.9903.
        assertThat(AdifFormat.location(39.7392, true)).isEqualTo("N039 44.352");
        assertThat(AdifFormat.location(-104.9903, false)).isEqualTo("W104 59.418");
    }

    @Test
    public void location_degreesAreAlwaysThreeDigits() {
        // Importers parse this format by position, so the padding is not cosmetic.
        assertThat(AdifFormat.location(9.5, true)).isEqualTo("N009 30.000");
        assertThat(AdifFormat.location(0.5, false)).isEqualTo("E000 30.000");
    }

    @Test
    public void location_hemisphereCarriesTheSign() {
        assertThat(AdifFormat.location(-33.8688, true)).startsWith("S033");
        assertThat(AdifFormat.location(151.2093, false)).startsWith("E151");
    }

    @Test
    public void location_zeroIsNorthAndEast() {
        assertThat(AdifFormat.location(0.0, true)).isEqualTo("N000 00.000");
        assertThat(AdifFormat.location(0.0, false)).isEqualTo("E000 00.000");
    }

    @Test
    public void location_carriesRatherThanEmittingSixtyMinutes() {
        // 39.999992 deg is 59.99952 minutes, which rounds to 60.000 — not a legal value,
        // so the degree has to absorb it.
        String formatted = AdifFormat.location(39.999992, true);
        assertThat(formatted).isEqualTo("N040 00.000");
        assertThat(formatted).doesNotContain("60.000");

        // Just below the carry threshold the minutes stay put — the branch must not
        // fire early and shift a coordinate a whole minute north.
        assertThat(AdifFormat.location(39.99999, true)).isEqualTo("N039 59.999");
    }

    @Test
    public void location_rejectsAbsentAndOutOfRangeValues() {
        assertThat(AdifFormat.location(null, true)).isNull();
        assertThat(AdifFormat.location(Double.NaN, true)).isNull();
        assertThat(AdifFormat.location(91.0, true)).isNull();     // latitude limit
        assertThat(AdifFormat.location(181.0, false)).isNull();   // longitude limit
        // 91 is a perfectly good longitude, just not a latitude.
        assertThat(AdifFormat.location(91.0, false)).isNotNull();
    }

    @Test
    public void parseLocation_roundTripsWhatWeWrite() {
        double lat = 39.7392;
        double lon = -104.9903;
        Double parsedLat = AdifFormat.parseLocation(AdifFormat.location(lat, true));
        Double parsedLon = AdifFormat.parseLocation(AdifFormat.location(lon, false));
        assertThat(parsedLat).isNotNull();
        assertThat(parsedLon).isNotNull();
        // The format quantizes to a thousandth of a minute — about 1.8 m.
        assertThat(Math.abs(parsedLat - lat)).isLessThan(0.0001);
        assertThat(Math.abs(parsedLon - lon)).isLessThan(0.0001);
    }

    @Test
    public void parseLocation_readsSouthAndWestAsNegative() {
        assertThat(AdifFormat.parseLocation("S033 52.128")).isLessThan(0.0);
        assertThat(AdifFormat.parseLocation("W104 59.418")).isLessThan(0.0);
        assertThat(AdifFormat.parseLocation("N039 44.352")).isGreaterThan(0.0);
    }

    @Test
    public void parseLocation_rejectsMalformedValuesRatherThanGuessing() {
        // A mis-parse puts the QSO in the wrong hemisphere, which beats having none.
        assertThat(AdifFormat.parseLocation(null)).isNull();
        assertThat(AdifFormat.parseLocation("")).isNull();
        assertThat(AdifFormat.parseLocation("39.7392")).isNull();      // no hemisphere
        assertThat(AdifFormat.parseLocation("X039 44.352")).isNull();  // bad hemisphere
        assertThat(AdifFormat.parseLocation("N039")).isNull();         // no minutes
        assertThat(AdifFormat.parseLocation("N039 61.000")).isNull();  // minutes out of range
    }

    @Test
    public void decimalDegrees_keepsFullPrecisionForTheAppFields() {
        assertThat(AdifFormat.decimalDegrees(39.7392)).isEqualTo("39.739200");
        assertThat(AdifFormat.decimalDegrees(-104.9903)).isEqualTo("-104.990300");
        assertThat(AdifFormat.decimalDegrees(null)).isNull();
        assertThat(AdifFormat.decimalDegrees(Double.NaN)).isNull();
    }

    @Test
    public void record_emitsBothFieldPairsWhenPositionIsKnown() {
        String adif = new AdifRecord()
                .call("K1ABC")
                .myLat(39.7392)
                .myLon(-104.9903)
                .build();
        assertThat(adif).contains("<MY_LAT:11>N039 44.352");
        assertThat(adif).contains("<MY_LON:11>W104 59.418");
        assertThat(adif).contains("<APP_RTOTA_LAT:9>39.739200");
        assertThat(adif).contains("<APP_RTOTA_LON:11>-104.990300");
    }

    @Test
    public void record_emitsNoPositionFieldsWhenUnknown() {
        String adif = new AdifRecord().call("K1ABC").build();
        assertThat(adif).doesNotContain("MY_LAT");
        assertThat(adif).doesNotContain("APP_RTOTA_LAT");
    }

    @Test
    public void record_emitsNothingWhenOnlyHalfThePositionIsKnown() {
        // A lone longitude would place the QSO on the equator.
        String adif = new AdifRecord().call("K1ABC").myLat(39.7392).build();
        assertThat(adif).doesNotContain("MY_LAT");
        assertThat(adif).doesNotContain("MY_LON");
    }
}
