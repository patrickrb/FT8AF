package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tests for the pure JSON helpers behind {@link ThirdPartyService#createCloudlogStation}:
 * {@link ThirdPartyService#buildCreateStationRequestJson} and
 * {@link ThirdPartyService#parseCreateStationResponse}. These touch {@code org.json},
 * which is an Android framework type stubbed on the plain JVM, so the test runs under
 * Robolectric for a real JSON implementation. No network is exercised.
 */
@RunWith(RobolectricTestRunner.class)
public class ThirdPartyServiceCreateStationTest {

    @Test
    public void buildRequest_emitsExpectedFields() throws Exception {
        String json = ThirdPartyService.buildCreateStationRequestJson(
                "FN42", "K1AF", "Albany", "291", true);
        JSONObject obj = new JSONObject(json);
        assertThat(obj.getString("station_gridsquare")).isEqualTo("FN42");
        assertThat(obj.getString("station_callsign")).isEqualTo("K1AF");
        assertThat(obj.getString("station_city")).isEqualTo("Albany");
        assertThat(obj.getString("station_dxcc")).isEqualTo("291");
        assertThat(obj.getString("link_active_logbook")).isEqualTo("1");
    }

    @Test
    public void buildRequest_linkFalseEmitsZeroAndNullsBecomeEmpty() throws Exception {
        String json = ThirdPartyService.buildCreateStationRequestJson(
                null, "K1AF", null, "291", false);
        JSONObject obj = new JSONObject(json);
        assertThat(obj.getString("station_gridsquare")).isEmpty();
        assertThat(obj.getString("station_city")).isEmpty();
        assertThat(obj.getString("link_active_logbook")).isEqualTo("0");
    }

    @Test
    public void parseResponse_topLevelStationProfileId() {
        assertThat(ThirdPartyService.parseCreateStationResponse(
                "{\"station_profile_id\":\"12\"}")).isEqualTo("12");
    }

    @Test
    public void parseResponse_fallsBackToStationIdThenId() {
        assertThat(ThirdPartyService.parseCreateStationResponse(
                "{\"station_id\":\"7\"}")).isEqualTo("7");
        assertThat(ThirdPartyService.parseCreateStationResponse(
                "{\"id\":\"9\"}")).isEqualTo("9");
    }

    @Test
    public void parseResponse_numericIdIsStringified() {
        // Some servers return the id as a JSON number, not a string.
        assertThat(ThirdPartyService.parseCreateStationResponse(
                "{\"station_profile_id\":15}")).isEqualTo("15");
    }

    @Test
    public void parseResponse_nestedUnderData() {
        assertThat(ThirdPartyService.parseCreateStationResponse(
                "{\"status\":\"created\",\"data\":{\"station_profile_id\":\"3\"}}"))
                .isEqualTo("3");
    }

    @Test
    public void parseResponse_returnsNullWhenNoId() {
        assertThat(ThirdPartyService.parseCreateStationResponse(
                "{\"status\":\"error\",\"reason\":\"bad key\"}")).isNull();
    }

    @Test
    public void parseResponse_returnsNullForNullEmptyOrGarbage() {
        assertThat(ThirdPartyService.parseCreateStationResponse(null)).isNull();
        assertThat(ThirdPartyService.parseCreateStationResponse("")).isNull();
        assertThat(ThirdPartyService.parseCreateStationResponse("not json")).isNull();
    }

    @Test
    public void createCloudlogStation_returnsNullOnMissingCredentials() {
        // No network reached: blank address/key short-circuits to null.
        assertThat(ThirdPartyService.createCloudlogStation(
                "", "key", "FN42", "K1AF", "Albany", "291", true)).isNull();
        assertThat(ThirdPartyService.createCloudlogStation(
                "https://example.org/", "", "FN42", "K1AF", "Albany", "291", true)).isNull();
    }

    @Test
    public void buildRequest_returnsParseableObjectNotEmptySentinel() throws Exception {
        // Fail-fast contract: a valid build must yield a real object, never a "{}" or
        // null sentinel that the caller would blindly POST.
        String json = ThirdPartyService.buildCreateStationRequestJson(
                "FN42", "K1AF", "Albany", "291", true);
        assertThat(json).isNotNull();
        assertThat(json).isNotEqualTo("{}");
        assertThat(new JSONObject(json).length()).isGreaterThan(0);
    }

    @Test
    public void redactUrlApiKey_hidesCreateStationKey() {
        String key = "s3cr3t-api-key-value";
        String url = "https://example.org/api/create_station/" + key;
        String redacted = ThirdPartyService.redactUrlApiKey(url);
        assertThat(redacted).doesNotContain(key);
        assertThat(redacted).isEqualTo("https://example.org/api/create_station/***");
    }

    @Test
    public void redactUrlApiKey_hidesStationInfoAndAuthKeys() {
        String key = "ABC123DEF456";
        assertThat(ThirdPartyService.redactUrlApiKey(
                "https://example.org/api/station_info/" + key))
                .doesNotContain(key);
        assertThat(ThirdPartyService.redactUrlApiKey(
                "https://example.org/api/auth/" + key))
                .doesNotContain(key);
    }

    @Test
    public void redactUrlApiKey_keepsTrailingPathAndQueryButHidesKey() {
        String key = "topsecret";
        String redacted = ThirdPartyService.redactUrlApiKey(
                "https://example.org/api/create_station/" + key + "?x=1");
        assertThat(redacted).doesNotContain(key);
        assertThat(redacted).isEqualTo("https://example.org/api/create_station/***?x=1");
    }

    @Test
    public void redactUrlApiKey_nullPassesThrough() {
        assertThat(ThirdPartyService.redactUrlApiKey(null)).isNull();
    }
}
