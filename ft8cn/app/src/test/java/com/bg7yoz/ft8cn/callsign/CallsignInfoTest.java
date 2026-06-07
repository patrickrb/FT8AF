package com.bg7yoz.ft8cn.callsign;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Exercise {@link CallsignInfo}'s data-model constructors. The 10-arg
 * constructor is a plain field-assigner; the single-arg String form parses
 * cty.dat-style colon-delimited records.
 *
 * Robolectric is needed because the class imports android.util.Log for its
 * error-path logging.
 */
@RunWith(RobolectricTestRunner.class)
public class CallsignInfoTest {

    @Test
    public void argConstructor_assignsAllFields() {
        CallsignInfo info = new CallsignInfo(
                "K1ABC", "United States", "美国", 5, 8,
                "NA", 42.5f, -71.0f, -5.0f, "K");

        assertThat(info.CallSign).isEqualTo("K1ABC");
        assertThat(info.CountryNameEn).isEqualTo("United States");
        assertThat(info.CountryNameCN).isEqualTo("美国");
        assertThat(info.CQZone).isEqualTo(5);
        assertThat(info.ITUZone).isEqualTo(8);
        assertThat(info.Continent).isEqualTo("NA");
        assertThat(info.Latitude).isEqualTo(42.5f);
        assertThat(info.Longitude).isEqualTo(-71.0f);
        assertThat(info.GMT_offset).isEqualTo(-5.0f);
        assertThat(info.DXCC).isEqualTo("K");
    }

    @Test
    public void stringConstructor_parsesColonDelimitedRecord() {
        // cty.dat-style row: country:cq:itu:continent:lat:lon:gmtOffset:dxcc:callsign
        CallsignInfo info = new CallsignInfo(
                "United States:5:8:NA:42.5:-71.0:-5.0:K:K1ABC");

        assertThat(info.CountryNameEn).isEqualTo("United States");
        assertThat(info.CQZone).isEqualTo(5);
        assertThat(info.ITUZone).isEqualTo(8);
        assertThat(info.Continent).isEqualTo("NA");
        assertThat(info.Latitude).isEqualTo(42.5f);
        assertThat(info.Longitude).isEqualTo(-71.0f);
        assertThat(info.GMT_offset).isEqualTo(-5.0f);
        assertThat(info.DXCC).isEqualTo("K");
        assertThat(info.CallSign).isEqualTo("K1ABC");
    }

    @Test
    public void stringConstructor_stripsWhitespaceFromNumericFields() {
        // Real cty.dat input has leading spaces around the numeric columns.
        CallsignInfo info = new CallsignInfo(
                "Canada: 5: 8:NA: 45.0: -75.0: -5.0:VE:VE3XYZ");

        assertThat(info.CQZone).isEqualTo(5);
        assertThat(info.ITUZone).isEqualTo(8);
        assertThat(info.Latitude).isEqualTo(45.0f);
        assertThat(info.Longitude).isEqualTo(-75.0f);
    }

    @Test
    public void stringConstructor_belowMinFieldCount_doesNotThrow() {
        // Fewer than 9 fields logs an error and bails without populating; we
        // assert it doesn't throw and that the default-initialised fields stay
        // at their zero values rather than triggering a NumberFormatException.
        CallsignInfo info = new CallsignInfo("too:few:fields");
        assertThat(info.CallSign).isNull();
        assertThat(info.CountryNameEn).isNull();
        assertThat(info.CQZone).isEqualTo(0);
    }

    @Test
    public void stringConstructor_stripsNewlinesFromCountryName() {
        // CountryNameEn keeps interior spaces but strips leading/trailing
        // newlines (replace("\n","").trim()).
        CallsignInfo info = new CallsignInfo(
                "United States\n:5:8:NA:42.5:-71.0:-5.0:K:K1ABC");
        assertThat(info.CountryNameEn).isEqualTo("United States");
    }

    @Test
    public void stringConstructor_ignoresExtraTrailingFields() {
        // Only the first 9 colon-delimited fields are consumed; anything after
        // index 8 is ignored.
        CallsignInfo info = new CallsignInfo(
                "United States:5:8:NA:42.5:-71.0:-5.0:K:K1ABC:extra:more");
        assertThat(info.CallSign).isEqualTo("K1ABC");
        assertThat(info.DXCC).isEqualTo("K");
    }

    @Test
    public void stringConstructor_stripsSpacesFromContinentAndDxcc() {
        // Continent and DXCC have all spaces removed (replace(" ","")), unlike
        // CountryNameEn which only trims edges.
        CallsignInfo info = new CallsignInfo(
                "Country: 5: 8: N A : 42.5: -71.0: -5.0: K K :K1ABC");
        assertThat(info.Continent).isEqualTo("NA");
        assertThat(info.DXCC).isEqualTo("KK");
    }

    @Test
    public void stringConstructor_parsesNegativeAndPositiveCoordinates() {
        CallsignInfo info = new CallsignInfo(
                "Far South:38:74:OC:-33.87:151.21:10.0:VK:VK2ABC");
        assertThat(info.Latitude).isEqualTo(-33.87f);
        assertThat(info.Longitude).isEqualTo(151.21f);
        assertThat(info.GMT_offset).isEqualTo(10.0f);
        assertThat(info.Continent).isEqualTo("OC");
    }

    @Test
    public void argConstructor_preservesChineseCountryName() {
        CallsignInfo info = new CallsignInfo(
                "BY1QH", "China", "中国", 24, 44,
                "AS", 39.9f, -116.4f, 8.0f, "BY");
        assertThat(info.CountryNameCN).isEqualTo("中国");
        assertThat(info.Continent).isEqualTo("AS");
        assertThat(info.GMT_offset).isEqualTo(8.0f);
    }
}
