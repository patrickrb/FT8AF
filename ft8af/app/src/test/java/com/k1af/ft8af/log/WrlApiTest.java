package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link WrlApi}, the World Radio League client (issue #800): the contact
 * payload WRL validates strictly, error/rate-limit interpretation, and the upload retry and
 * stop-the-batch decisions driven through a fake transport. Robolectric for org.json.
 */
@RunWith(RobolectricTestRunner.class)
public class WrlApiTest {

    /** Every top-level field WRL's ContactCreate schema accepts; anything else is a 400. */
    private static final Set<String> CONTACT_CREATE_FIELDS = new HashSet<>(Arrays.asList(
            "programId", "logbookId", "call", "timestamp", "freq", "band", "mode", "rstSent",
            "rstRcvd", "txPwr", "notes", "stationCallsign", "myGridsquare", "name", "gridsquare",
            "qth", "state", "operator", "myActivities", "theirActivities"));

    private WrlApi.Transport savedTransport;
    private WrlApi.Sleeper savedSleeper;
    private final List<String> urls = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();

    @Before
    public void setUp() {
        savedTransport = WrlApi.transport;
        savedSleeper = WrlApi.sleeper;
        WrlApi.sleeper = sleeps::add;
    }

    @After
    public void tearDown() {
        WrlApi.transport = savedTransport;
        WrlApi.sleeper = savedSleeper;
    }

    /** Transport answering each call with the next response in turn (the last one repeats). */
    private void respond(WrlApi.Response... responses) {
        WrlApi.transport = (method, url, key, body) -> {
            urls.add(method + " " + url);
            keys.add(key);
            return responses[Math.min(urls.size() - 1, responses.length - 1)];
        };
    }

    private static WrlApi.Response status(int code) {
        return new WrlApi.Response(code, "{\"data\":{},\"meta\":null,\"error\":null}", null);
    }

    private static WrlApi.Response error(int code, String errCode, String message, String field) {
        String fieldJson = field == null ? "" : ",\"field\":\"" + field + "\"";
        return new WrlApi.Response(code, "{\"data\":null,\"meta\":null,\"error\":{\"code\":\""
                + errCode + "\",\"message\":\"" + message + "\"" + fieldJson + "}}", null);
    }

    private static WrlApi.ContactFields ft8() {
        WrlApi.ContactFields f = new WrlApi.ContactFields();
        f.call = "w1aw";
        f.qsoDate = "20260805";
        f.timeOn = "143500";
        f.freqMhz = "14.074";
        f.band = "20M";
        f.mode = "ft8";
        f.rstSent = "-12";
        f.rstRcvd = "-08";
        f.gridsquare = "FN31pr";
        f.myGridsquare = "EN52";
        f.stationCallsign = "k1af";
        f.notes = "Distance: 99 km";
        return f;
    }

    private static JSONObject build(WrlApi.ContactFields f, String logbookId) throws Exception {
        String json = WrlApi.buildContactJson(f, logbookId, null);
        assertThat(json).isNotNull();
        return new JSONObject(json);
    }

    // -- contact payload --

    @Test
    public void contactJson_carriesRequiredFieldsInWrlShape() throws Exception {
        JSONObject o = build(ft8(), "");
        assertThat(o.getString("programId")).isEqualTo("FT8AF");
        assertThat(o.getString("call")).isEqualTo("W1AW");
        JSONObject ts = o.getJSONObject("timestamp");
        assertThat(ts.getString("qsoDate")).isEqualTo("20260805");
        assertThat(ts.getString("timeOn")).isEqualTo("143500");
        assertThat(o.getDouble("freq")).isWithin(1e-9).of(14.074);
        assertThat(o.getString("band")).isEqualTo("20m");
        assertThat(o.getString("mode")).isEqualTo("FT8");
        assertThat(o.getString("rstSent")).isEqualTo("-12");
        assertThat(o.getString("rstRcvd")).isEqualTo("-08");
        assertThat(o.getString("gridsquare")).isEqualTo("FN31pr");
        assertThat(o.getString("myGridsquare")).isEqualTo("EN52");
        assertThat(o.getString("stationCallsign")).isEqualTo("K1AF");
        assertThat(o.getString("notes")).isEqualTo("Distance: 99 km");
        // Omitted, not blank: a blank logbookId is not "the default logbook".
        assertThat(o.has("logbookId")).isFalse();
    }

    @Test
    public void contactJson_onlyUsesFieldNamesWrlAccepts() throws Exception {
        WrlApi.ContactFields f = ft8();
        f.mySig = "POTA";
        f.mySigInfo = "US-0001";
        f.sig = "SOTA";
        f.sigInfo = "W7A/MN-001";
        JSONObject o = build(f, "6f1c0b7e-6c2a-4c4e-9d1f-2a5b3c4d5e6f");
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            assertThat(CONTACT_CREATE_FIELDS).contains(it.next());
        }
    }

    @Test
    public void contactJson_includesConfiguredLogbook() throws Exception {
        JSONObject o = build(ft8(), " 6f1c0b7e-6c2a-4c4e-9d1f-2a5b3c4d5e6f ");
        assertThat(o.getString("logbookId")).isEqualTo("6f1c0b7e-6c2a-4c4e-9d1f-2a5b3c4d5e6f");
    }

    @Test
    public void contactJson_ft4IsSentAsItsOwnModeNotMfsk() throws Exception {
        WrlApi.ContactFields f = ft8();
        f.mode = "FT4";
        assertThat(build(f, null).getString("mode")).isEqualTo("FT4");
    }

    @Test
    public void contactJson_dropsGridsWrlWouldRejectInsteadOfFailingTheContact() throws Exception {
        WrlApi.ContactFields f = ft8();
        f.gridsquare = "ZZ99";
        f.myGridsquare = "  ";
        JSONObject o = build(f, null);
        assertThat(o.has("gridsquare")).isFalse();
        assertThat(o.has("myGridsquare")).isFalse();
    }

    @Test
    public void contactJson_clipsOverlongReportsAndNotes() throws Exception {
        WrlApi.ContactFields f = ft8();
        f.rstSent = "123456789012345";
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < 2100; i++) notes.append('x');
        f.notes = notes.toString();
        JSONObject o = build(f, null);
        assertThat(o.getString("rstSent")).hasLength(WrlApi.MAX_RST_LEN);
        assertThat(o.getString("notes")).hasLength(WrlApi.MAX_NOTES_LEN);
    }

    @Test
    public void contactJson_emptyOptionalFieldsAreOmitted() throws Exception {
        WrlApi.ContactFields f = ft8();
        f.notes = "";
        f.rstRcvd = null;
        f.stationCallsign = " ";
        JSONObject o = build(f, null);
        assertThat(o.has("notes")).isFalse();
        assertThat(o.has("rstRcvd")).isFalse();
        assertThat(o.has("stationCallsign")).isFalse();
    }

    @Test
    public void contactJson_missingRequiredFieldIsNotSentAndSaysWhy() {
        WrlApi.ContactFields noCall = ft8();
        noCall.call = " ";
        assertRefused(noCall, "missing call");

        WrlApi.ContactFields noBand = ft8();
        noBand.band = "";
        assertRefused(noBand, "missing band");

        WrlApi.ContactFields badFreq = ft8();
        badFreq.freqMhz = "abc";
        assertRefused(badFreq, "missing frequency");

        WrlApi.ContactFields badDate = ft8();
        badDate.qsoDate = "2026";
        assertRefused(badDate, "qso_date");

        WrlApi.ContactFields badTime = ft8();
        badTime.timeOn = "1";
        assertRefused(badTime, "time_on");

        WrlApi.ContactFields noMode = ft8();
        noMode.mode = null;
        assertRefused(noMode, "missing mode");
    }

    private static void assertRefused(WrlApi.ContactFields f, String reason) {
        StringBuilder why = new StringBuilder();
        assertThat(WrlApi.buildContactJson(f, null, why)).isNull();
        assertThat(why.toString()).contains(reason);
    }

    @Test
    public void normalizeTimeOn_restoresDroppedLeadingZeroAndStripsSeparators() {
        assertThat(WrlApi.normalizeTimeOn("815")).isEqualTo("0815");
        assertThat(WrlApi.normalizeTimeOn("81500")).isEqualTo("081500");
        assertThat(WrlApi.normalizeTimeOn("1435")).isEqualTo("1435");
        assertThat(WrlApi.normalizeTimeOn("14:35:00")).isEqualTo("143500");
        assertThat(WrlApi.normalizeTimeOn("1")).isNull();
        assertThat(WrlApi.normalizeTimeOn("1234567")).isNull();
        assertThat(WrlApi.normalizeTimeOn(null)).isNull();
    }

    @Test
    public void parseFreqMhz_acceptsMhzAndConvertsHz() {
        assertThat(WrlApi.parseFreqMhz("14.074")).isWithin(1e-9).of(14.074);
        assertThat(WrlApi.parseFreqMhz("144.174")).isWithin(1e-9).of(144.174);
        assertThat(WrlApi.parseFreqMhz("1296.174")).isWithin(1e-9).of(1296.174);
        assertThat(WrlApi.parseFreqMhz("14074000")).isWithin(1e-9).of(14.074);
        assertThat(WrlApi.parseFreqMhz("0")).isNull();
        assertThat(WrlApi.parseFreqMhz("-7.074")).isNull();
        assertThat(WrlApi.parseFreqMhz("x")).isNull();
        assertThat(WrlApi.parseFreqMhz(null)).isNull();
    }

    // -- activities --

    @Test
    public void activities_potaTwoFerBecomesTwoEntriesWithFirstPrimary() throws Exception {
        JSONArray arr = WrlApi.activities("pota", "us-0001, US-0002");
        assertThat(arr.length()).isEqualTo(2);
        assertThat(arr.getJSONObject(0).getString("type")).isEqualTo("POTA");
        assertThat(arr.getJSONObject(0).getString("ref")).isEqualTo("US-0001");
        assertThat(arr.getJSONObject(0).getBoolean("isPrimary")).isTrue();
        assertThat(arr.getJSONObject(1).getString("ref")).isEqualTo("US-0002");
        assertThat(arr.getJSONObject(1).has("isPrimary")).isFalse();
    }

    @Test
    public void activities_dedupesAndCapsAtWrlLimit() throws Exception {
        JSONArray arr = WrlApi.activities("POTA",
                "US-0001,US-0001,US-0002,US-0003,US-0004,US-0005,US-0006,US-0007,US-0008");
        assertThat(arr.length()).isEqualTo(WrlApi.MAX_ACTIVITIES);
        assertThat(arr.getJSONObject(1).getString("ref")).isEqualTo("US-0002");
    }

    @Test
    public void activities_unknownProgrammeOrNoRefsIsOmitted() throws Exception {
        assertThat(WrlApi.activities("CQWW", "X")).isNull();
        assertThat(WrlApi.activities("POTA", " ")).isNull();
        assertThat(WrlApi.activities(null, "US-0001")).isNull();
    }

    @Test
    public void contactJson_mapsBothSidesOfAnActivation() throws Exception {
        WrlApi.ContactFields f = ft8();
        f.mySig = "POTA";
        f.mySigInfo = "US-0662";
        f.sig = "POTA";
        f.sigInfo = "US-1234";
        JSONObject o = build(f, null);
        assertThat(o.getJSONArray("myActivities").getJSONObject(0).getString("ref")).isEqualTo("US-0662");
        assertThat(o.getJSONArray("theirActivities").getJSONObject(0).getString("ref")).isEqualTo("US-1234");
    }

    // -- failures --

    @Test
    public void describeFailure_usesWrlErrorEnvelope() {
        WrlApi.Response r = error(401, "INVALID_KEY", "The API key is not valid.", null);
        assertThat(WrlApi.describeFailure(r.status, r.body))
                .isEqualTo("HTTP 401 INVALID_KEY: The API key is not valid.");

        WrlApi.Response withField = error(404, "NOT_FOUND", "No such logbook.", "logbookId");
        assertThat(WrlApi.describeFailure(withField.status, withField.body))
                .isEqualTo("HTTP 404 NOT_FOUND: No such logbook. [logbookId]");
    }

    @Test
    public void describeFailure_nonJsonBodyIsFlattenedAndTruncated() {
        StringBuilder page = new StringBuilder("<html>\n<body>");
        for (int i = 0; i < 100; i++) page.append(" Bad Gateway");
        String out = WrlApi.describeFailure(502, page.toString());
        assertThat(out).startsWith("HTTP 502: <html> <body>");
        assertThat(out).doesNotContain("\n");
        assertThat(out.length()).isAtMost(201);
        assertThat(WrlApi.describeFailure(500, null)).isEqualTo("HTTP 500");
    }

    @Test
    public void isBatchFatal_accountWideProblemsStopTheBatch() {
        assertThat(WrlApi.isBatchFatal(401, error(401, "INVALID_KEY", "x", null).body)).isTrue();
        assertThat(WrlApi.isBatchFatal(403, error(403, "MEMBERSHIP_REQUIRED", "x", null).body)).isTrue();
        assertThat(WrlApi.isBatchFatal(429, null)).isTrue();
        assertThat(WrlApi.isBatchFatal(422, error(422, "LOGBOOK_REQUIRED", "x", "logbookId").body)).isTrue();
        assertThat(WrlApi.isBatchFatal(404, error(404, "NOT_FOUND", "x", "logbookId").body)).isTrue();
        assertThat(WrlApi.isBatchFatal(409, error(409, "CONFLICT", "locked", "logbookId").body)).isTrue();
        assertThat(WrlApi.isBatchFatal(503, error(503, "API_DISABLED", "x", null).body)).isTrue();
    }

    @Test
    public void isBatchFatal_contactSpecificProblemsDoNot() {
        assertThat(WrlApi.isBatchFatal(400, error(400, "VALIDATION_ERROR", "x", "band").body)).isFalse();
        assertThat(WrlApi.isBatchFatal(422, error(422, "VALIDATION_ERROR", "x", "gridsquare").body)).isFalse();
        assertThat(WrlApi.isBatchFatal(500, error(500, "INTERNAL_ERROR", "x", null).body)).isFalse();
        assertThat(WrlApi.isBatchFatal(502, "<html>")).isFalse();
    }

    @Test
    public void parseRetryAfterSeconds_onlyDeltaSeconds() {
        assertThat(WrlApi.parseRetryAfterSeconds("30")).isEqualTo(30);
        assertThat(WrlApi.parseRetryAfterSeconds(" 0 ")).isEqualTo(0);
        assertThat(WrlApi.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(-1);
        assertThat(WrlApi.parseRetryAfterSeconds("-5")).isEqualTo(-1);
        assertThat(WrlApi.parseRetryAfterSeconds(null)).isEqualTo(-1);
    }

    // -- upload --

    @Test
    public void upload_createdIsOkAndPostsToContactsWithTheKey() {
        respond(status(201));
        assertThat(WrlApi.uploadContact("wrl_live_k", "{}", null)).isEqualTo(WrlApi.UploadResult.OK);
        assertThat(urls).containsExactly("POST https://api.worldradioleague.com/v1/contacts");
        assertThat(keys).containsExactly("wrl_live_k");
    }

    @Test
    public void upload_perMinuteRateLimitIsWaitedOutThenRetried() {
        respond(new WrlApi.Response(429, error(429, "RATE_LIMITED", "slow", null).body, "7"),
                status(201));
        assertThat(WrlApi.uploadContact("k", "{}", null)).isEqualTo(WrlApi.UploadResult.OK);
        assertThat(urls).hasSize(2);
        assertThat(sleeps).containsExactly(7000L);
    }

    @Test
    public void upload_zeroRetryAfterStillPausesASecond() {
        respond(new WrlApi.Response(429, "", "0"), status(201));
        assertThat(WrlApi.uploadContact("k", "{}", null)).isEqualTo(WrlApi.UploadResult.OK);
        assertThat(sleeps).containsExactly(1000L);
    }

    @Test
    public void upload_dailyQuotaStopsTheBatchWithoutWaiting() {
        respond(new WrlApi.Response(429,
                error(429, "RATE_LIMITED", "Daily API quota exhausted.", null).body, "40000"));
        StringBuilder why = new StringBuilder();
        assertThat(WrlApi.uploadContact("k", "{}", why)).isEqualTo(WrlApi.UploadResult.FAILED_STOP_BATCH);
        assertThat(sleeps).isEmpty();
        assertThat(urls).hasSize(1);
        assertThat(why.toString()).contains("Daily API quota exhausted.");
    }

    @Test
    public void upload_persistentRateLimitGivesUpAfterBoundedRetries() {
        respond(new WrlApi.Response(429, "", "1"));
        assertThat(WrlApi.uploadContact("k", "{}", null)).isEqualTo(WrlApi.UploadResult.FAILED_STOP_BATCH);
        assertThat(urls).hasSize(WrlApi.MAX_RATE_LIMIT_RETRIES + 1);
        assertThat(sleeps).hasSize(WrlApi.MAX_RATE_LIMIT_RETRIES);
    }

    @Test
    public void upload_validationErrorFailsOnlyThisContact() {
        respond(error(400, "VALIDATION_ERROR", "Unrecognised field.", "grid_square"));
        StringBuilder why = new StringBuilder();
        assertThat(WrlApi.uploadContact("k", "{}", why)).isEqualTo(WrlApi.UploadResult.FAILED);
        assertThat(why.toString()).isEqualTo("HTTP 400 VALIDATION_ERROR: Unrecognised field. [grid_square]");
    }

    @Test
    public void upload_transportErrorStopsTheBatch() {
        WrlApi.transport = (method, url, key, body) -> {
            throw new IOException("Unable to resolve host");
        };
        StringBuilder why = new StringBuilder();
        assertThat(WrlApi.uploadContact("k", "{}", why)).isEqualTo(WrlApi.UploadResult.FAILED_STOP_BATCH);
        assertThat(why.toString()).isEqualTo("IOException: Unable to resolve host");
    }

    // -- connection check --

    @Test
    public void interpretMe_resolvedDefaultLogbookPasses() {
        ThirdPartyService.ConnectionCheck c = WrlApi.interpretMe(
                "{\"data\":{\"uid\":\"u\",\"defaultLogbook\":{\"logbookId\":\"L\",\"resolution\":\"sole\"}},"
                        + "\"meta\":null,\"error\":null}", "");
        assertThat(c.ok).isTrue();
        assertThat(c.detail).isNull();
    }

    @Test
    public void interpretMe_ambiguousDefaultFailsUnlessALogbookIsChosen() {
        String body = "{\"data\":{\"defaultLogbook\":{\"logbookId\":null,\"resolution\":\"ambiguous\"}},"
                + "\"meta\":null,\"error\":null}";
        ThirdPartyService.ConnectionCheck none = WrlApi.interpretMe(body, null);
        assertThat(none.ok).isFalse();
        assertThat(none.detail).contains("choose a logbook");
        assertThat(WrlApi.interpretMe(body, "6f1c0b7e").ok).isTrue();
    }

    @Test
    public void interpretMe_noUsableLogbookFails() {
        ThirdPartyService.ConnectionCheck c = WrlApi.interpretMe(
                "{\"data\":{\"defaultLogbook\":{\"logbookId\":null,\"resolution\":\"none\"}}}", "");
        assertThat(c.ok).isFalse();
        assertThat(c.detail).contains("create one");
    }

    @Test
    public void interpretMe_notTheWrlApiFails() {
        assertThat(WrlApi.interpretMe("<html>login</html>", "").ok).isFalse();
        assertThat(WrlApi.interpretMe("{\"data\":null}", "").ok).isFalse();
        assertThat(WrlApi.interpretMe(null, "").detail).contains("not the World Radio League API");
    }

    @Test
    public void checkConnection_withoutKeyNeverCallsTheApi() {
        respond(status(200));
        ThirdPartyService.ConnectionCheck c = WrlApi.checkConnection("  ", "");
        assertThat(c.ok).isFalse();
        assertThat(c.detail).isEqualTo("no API key configured");
        assertThat(urls).isEmpty();
    }

    @Test
    public void checkConnection_rejectedKeyReportsWrlReason() {
        respond(error(401, "KEY_REVOKED", "This key was revoked.", null));
        ThirdPartyService.ConnectionCheck c = WrlApi.checkConnection(" wrl_live_k ", "");
        assertThat(c.ok).isFalse();
        assertThat(c.detail).isEqualTo("HTTP 401 KEY_REVOKED: This key was revoked.");
        assertThat(urls).containsExactly("GET https://api.worldradioleague.com/v1/me");
        assertThat(keys).containsExactly("wrl_live_k");
    }

    // -- logbooks --

    @Test
    public void parseLogbooks_skipsLockedAndIdlessLogbooks() {
        List<ThirdPartyService.StationProfile> books = WrlApi.parseLogbooks(
                "{\"data\":["
                        + "{\"id\":\"a\",\"name\":\"Home\",\"defaultCallSign\":\"K1AF\",\"isLocked\":false},"
                        + "{\"id\":\"b\",\"name\":\"Contest 2025\",\"isLocked\":true},"
                        + "{\"name\":\"broken\"},"
                        + "{\"id\":\"c\",\"name\":\"POTA\",\"defaultCallSign\":null,\"isLocked\":null}"
                        + "],\"meta\":{\"count\":4},\"error\":null}");
        assertThat(books).hasSize(2);
        assertThat(books.get(0).stationId).isEqualTo("a");
        assertThat(books.get(0).callsign).isEqualTo("K1AF");
        assertThat(books.get(1).stationId).isEqualTo("c");
        assertThat(books.get(1).callsign).isEmpty();
        assertThat(WrlApi.parseLogbooks("not json")).isEmpty();
        assertThat(WrlApi.parseLogbooks(null)).isEmpty();
    }

    @Test
    public void logbookLabel_prefersNameAndCallsignOverUuid() {
        assertThat(WrlApi.logbookLabel(new ThirdPartyService.StationProfile("id-1", "Home", "K1AF", "")))
                .isEqualTo("Home (K1AF)");
        assertThat(WrlApi.logbookLabel(new ThirdPartyService.StationProfile("id-1", "Home", "", "")))
                .isEqualTo("Home");
        assertThat(WrlApi.logbookLabel(new ThirdPartyService.StationProfile("id-1", "", "", "")))
                .isEqualTo("id-1");
    }

    @Test
    public void fetchLogbooks_failureIsAnEmptyList() {
        respond(error(403, "INSUFFICIENT_SCOPE", "x", null));
        assertThat(WrlApi.fetchLogbooks("k")).isEmpty();
        assertThat(urls).containsExactly("GET https://api.worldradioleague.com/v1/logbooks?limit=100");
        assertThat(WrlApi.fetchLogbooks("")).isEmpty();
        assertThat(urls).hasSize(1);
    }
}
