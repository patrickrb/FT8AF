package com.k1af.ft8af.log;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.database.AfterInsertQSLData;
import com.k1af.ft8af.database.DatabaseOpr;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import radio.ks3ckc.ft8af.sync.CloudlogFailureHint;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

enum ServiceType{
    Cloudlog,
    QRZ
}

public class ThirdPartyService {
    public static String TAG = "ThirdPartyService";

    public static class StationProfile {
        public final String stationId;
        public final String profileName;
        public final String callsign;
        public final String gridsquare;

        public StationProfile(String stationId, String profileName,
                              String callsign, String gridsquare) {
            this.stationId = stationId;
            this.profileName = profileName;
            this.callsign = callsign;
            this.gridsquare = gridsquare;
        }

        public String displayLabel() {
            StringBuilder sb = new StringBuilder();
            sb.append(stationId);
            if (profileName != null && !profileName.isEmpty()) {
                sb.append(" - ").append(profileName);
            }
            if (callsign != null && !callsign.isEmpty()) {
                sb.append(" (").append(callsign);
                if (gridsquare != null && !gridsquare.isEmpty()) {
                    sb.append(", ").append(gridsquare);
                }
                sb.append(")");
            }
            return sb.toString();
        }
    }

    /**
     * Fetches station profiles from a Cloudlog/Wavelog/Nextlog server.
     * Returns an empty list on any failure (network, bad JSON, 404, etc.) — never null.
     */
    public static List<StationProfile> FetchCloudlogStations(String address, String apiKey) {
        List<StationProfile> stations = new ArrayList<>();
        String base = CloudlogEndpoint.normalizeBase(address);
        if (base == null || apiKey == null || apiKey.trim().isEmpty()) {
            return stations;
        }
        return parseStationProfiles(cloudlogGet(base, "api/station_info/" + apiKey.trim(), null));
    }

    /**
     * Station profiles from an {@code api/station_info} reply. Entries that aren't JSON
     * objects are skipped; null, empty or bad JSON gives an empty list, never null.
     */
    static List<StationProfile> parseStationProfiles(String json) {
        List<StationProfile> stations = new ArrayList<>();
        if (json == null || json.isEmpty()) return stations;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                stations.add(new StationProfile(
                        obj.optString("station_id", ""),
                        obj.optString("station_profile_name", ""),
                        obj.optString("station_callsign", ""),
                        obj.optString("station_gridsquare", "")
                ));
            }
        } catch (Exception | StackOverflowError e) {
            Log.d(TAG, "station_info parse error: " + e.getClass().getSimpleName());
        }
        return stations;
    }

    /**
     * Creates a new station profile ("station location") on a Wavelog server and
     * returns its {@code station_profile_id}, or null on failure. Mirrors
     * {@link #FetchCloudlogStations}: the API key is a path segment
     * ({@code POST api/create_station/[key]}) and the station fields are the JSON body.
     *
     * <p><b>Provisional wire shape.</b> We could not verify the exact live
     * request/response against a running server, so the request-building and
     * response-parsing are isolated into the pure, unit-tested helpers
     * {@link #buildCreateStationRequestJson} and {@link #parseCreateStationResponse}.
     * The field names ({@code station_gridsquare, station_callsign, station_city,
     * station_dxcc, link_active_logbook}) and endpoint follow the Wavelog API docs
     * and must be confirmed against a live Wavelog &ge; 2.1.2 server before this is
     * wired into any live path.
     *
     * <p><b>Not yet called from any live path</b> — foundation only for issue #437,
     * gated by {@code GeneralVariables.perLocationStationEnabled} (default false).
     */
    public static String createCloudlogStation(String address, String apiKey,
                                               String gridsquare, String callsign,
                                               String city, String dxcc,
                                               boolean linkActiveLogbook) {
        String base = CloudlogEndpoint.normalizeBase(address);
        if (base == null || apiKey == null || apiKey.trim().isEmpty()) {
            return null;
        }
        try {
            String body = buildCreateStationRequestJson(
                    gridsquare, callsign, city, dxcc, linkActiveLogbook);
            if (body == null) {
                // Fail fast: never POST an empty/malformed body, which could create a
                // blank station profile on the server.
                Log.d(TAG, "createCloudlogStation aborted: could not build request body");
                return null;
            }
            String result = cloudlogPost(base, "api/create_station/" + apiKey.trim(), body, null);
            String id = parseCreateStationResponse(result);
            Log.d(TAG, "createCloudlogStation " + (id != null ? "created id" : "failed/no id"));
            return id;
        } catch (Exception e) {
            Log.d(TAG, "createCloudlogStation error: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Builds the JSON request body for {@link #createCloudlogStation}. Pure and
     * network-free so it can be unit-tested against captured/example payloads.
     *
     * <p>Field set is provisional (see {@link #createCloudlogStation}). Null field
     * values are emitted as empty strings; {@code link_active_logbook} is emitted as
     * {@code "1"}/{@code "0"} (Wavelog treats the API booleans as string flags).
     *
     * <p>Returns {@code null} if the body cannot be built, so the caller can fail fast
     * rather than POST an empty/malformed body that might create a blank station.
     */
    static String buildCreateStationRequestJson(String gridsquare, String callsign,
                                                String city, String dxcc,
                                                boolean linkActiveLogbook) {
        JSONStringer js = new JSONStringer();
        try {
            return js.object()
                    .key("station_gridsquare").value(nullToEmpty(gridsquare))
                    .key("station_callsign").value(nullToEmpty(callsign))
                    .key("station_city").value(nullToEmpty(city))
                    .key("station_dxcc").value(nullToEmpty(dxcc))
                    .key("link_active_logbook").value(linkActiveLogbook ? "1" : "0")
                    .endObject()
                    .toString();
        } catch (Exception e) {
            Log.d(TAG, "buildCreateStationRequestJson error: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Extracts the newly-created {@code station_profile_id} from a create_station
     * response, or null if the response is null/empty/unparseable or carries no id.
     * Pure and network-free for unit testing.
     *
     * <p><b>Provisional.</b> The exact response envelope is unconfirmed, so this
     * probes the id under several plausible keys — top-level {@code station_profile_id}
     * / {@code station_id} / {@code id}, and the same keys nested under a {@code data}
     * or {@code station} object — and returns the first non-empty one found.
     */
    static String parseCreateStationResponse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(json);
            String top = idFromObject(obj);
            if (top != null) {
                return top;
            }
            for (String nestKey : new String[]{"data", "station", "result"}) {
                JSONObject nested = obj.optJSONObject(nestKey);
                if (nested != null) {
                    String id = idFromObject(nested);
                    if (id != null) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "parseCreateStationResponse error: " + e.getClass().getSimpleName());
        }
        return null;
    }

    private static String idFromObject(JSONObject obj) {
        for (String key : new String[]{"station_profile_id", "station_id", "id"}) {
            String v = obj.optString(key, "");
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    // Package-private (not private) so the ADIF payload can be unit-tested directly.
    static String QSLRecordToADIF(QSLRecord qslRecord, ServiceType serv){
        StringBuilder logStr = new StringBuilder();
        logStr.append(AdifFormat.callField(qslRecord.getToCallsign()));

        if (qslRecord.getToMaidenGrid() != null) {
            logStr.append(String.format("<gridsquare:%d>%s "
                    , AdifFormat.utf8Length(qslRecord.getToMaidenGrid())
                    , qslRecord.getToMaidenGrid()));
        }

        if (qslRecord.getMode() != null) {
            // FT4/FT2 are ADIF submodes of MFSK, not standalone modes — a bare
            // <mode>FT2 is rejected as invalid by QRZ/Cloudlog and other ADIF consumers.
            String submode = AdifFormat.mfskSubmode(qslRecord.getMode());
            if (submode != null) {
                logStr.append(String.format("<mode:4>MFSK <submode:%d>%s "
                        , AdifFormat.utf8Length(submode), submode));
            } else {
                logStr.append(String.format("<mode:%d>%s "
                        , AdifFormat.utf8Length(qslRecord.getMode())
                        , qslRecord.getMode()));
            }
        }

        String rstSent = AdifFormat.formatReport(qslRecord.getMode(), qslRecord.getSendReport());
        logStr.append(String.format("<rst_sent:%d>%s ", AdifFormat.utf8Length(rstSent), rstSent));

        String rstRcvd = AdifFormat.formatReport(qslRecord.getMode(), qslRecord.getReceivedReport());
        logStr.append(String.format("<rst_rcvd:%d>%s ", AdifFormat.utf8Length(rstRcvd), rstRcvd));

        if (qslRecord.getQso_date() != null) {
            logStr.append(String.format("<qso_date:%d>%s "
                    , AdifFormat.utf8Length(qslRecord.getQso_date())
                    , qslRecord.getQso_date()));
        }

        if (qslRecord.getTime_on() != null) {
            logStr.append(String.format("<time_on:%d>%s "
                    , AdifFormat.utf8Length(qslRecord.getTime_on())
                    , qslRecord.getTime_on()));
        }
        if (qslRecord.getBandLength() != null) {
            logStr.append(String.format("<band:%d>%s "
                    , AdifFormat.utf8Length(qslRecord.getBandLength())
                    , qslRecord.getBandLength()));
        }

        if (qslRecord.getQso_date_off() != null) {
            logStr.append(String.format("<qso_date_off:%d>%s "
                    , AdifFormat.utf8Length(qslRecord.getQso_date_off())
                    , qslRecord.getQso_date_off()));
        }

        if (qslRecord.getTime_off() != null) {
            logStr.append(String.format("<time_off:%d>%s "
                    , AdifFormat.utf8Length(qslRecord.getTime_off())
                    , qslRecord.getTime_off()));
        }

        if (String.valueOf(qslRecord.getBandFreq()) != null) {
            String freq = "";
            Log.d(TAG,String.valueOf(qslRecord.getBandFreq()));
            if (serv == ServiceType.Cloudlog || serv == ServiceType.QRZ){
                double i = (double)qslRecord.getBandFreq() / 1000000;
                freq = String.valueOf(i);
            }

            logStr.append(String.format("<freq:%d>%s "
                    , AdifFormat.utf8Length(freq)
                    , freq));
        }

        if (qslRecord.getMyCallsign() != null) {
            logStr.append(String.format("<station_callsign:%d>%s "
                    , AdifFormat.utf8Length(qslRecord.getMyCallsign())
                    , qslRecord.getMyCallsign()));
        }

        if (qslRecord.getMyMaidenGrid() != null) {
            logStr.append(String.format("<my_gridsquare:%d>%s "
                    , AdifFormat.utf8Length(qslRecord.getMyMaidenGrid())
                    , qslRecord.getMyMaidenGrid()));
        }

        String comment = qslRecord.getComment();
        if (comment == null) comment = "";

        //<comment:15>Distance: 99 km <eor>
        //When writing to the database, be sure to append " km"
        logStr.append(String.format("<comment:%d>%s <eor>\n"
                , AdifFormat.utf8Length(comment)
                , comment));
        return logStr.toString();
    }
    /**
     * Uploads one logged QSO to Cloudlog / Wavelog / Nextlog, recording why it failed into
     * {@code failureOut} so the post-QSO path can toast the server's reason instead of
     * failing silently.
     */
    public static boolean UploadToCloudLog(QSLRecord qslRecord, StringBuilder failureOut){
        // Convert to ADIF format
        String logStr = QSLRecordToADIF(qslRecord,ServiceType.Cloudlog);
        return uploadAdifToCloudlog(logStr, failureOut);
    }

    /**
     * Posts a single ADIF record (or any ADIF body) to Cloudlog/Wavelog/Nextlog.
     * Returns true on HTTP 2xx, false otherwise.
     */
    public static boolean uploadAdifToCloudlog(String adif) {
        return uploadAdifToCloudlog(adif, null);
    }

    /**
     * As {@link #uploadAdifToCloudlog(String)}, but records why the upload failed into
     * {@code failureOut} when one is supplied, so callers can surface the server's own
     * explanation instead of a bare "0 uploaded".
     */
    public static boolean uploadAdifToCloudlog(String adif, StringBuilder failureOut) {
        String base = CloudlogEndpoint.normalizeBase(GeneralVariables.getCloudlogServerAddress());
        if (base == null) {
            appendFailure(failureOut, "no server address configured");
            return false;
        }
        JSONStringer js = new JSONStringer();
        try {
            String result = js.object().key("key").value(GeneralVariables.getCloudlogServerApiKey()).key("station_profile_id").value(GeneralVariables.getCloudlogStationID())
                    .key("type").value("adif").key("string").value(adif).endObject().toString();
            // Cloudlog's documented endpoint is /api/qso (no trailing slash). Wavelog and
            // Nextlog both 308-redirect when the trailing slash is present, which
            // HttpURLConnection won't follow on a POST.
            String clRes = cloudlogApi.post(base, "api/qso", result, failureOut);
            Log.d(TAG, "Cloudlog upload " + (clRes != null ? "succeeded" : "failed"));
            if (clRes == null) {
                // The station id is the one request field the server's reason can point
                // at ("station id does not belong to the API key owner") and the one thing
                // the POST log line above doesn't show. Not a secret, so log it plainly.
                GeneralVariables.fileLog("Cloudlog: upload rejected with station_profile_id="
                        + describeStationId(GeneralVariables.getCloudlogStationID()));
            }
            return clRes != null;
        }catch (Exception k){
            Log.d(TAG, "Cloudlog upload error: " + k.getClass().getSimpleName());
            appendFailure(failureOut, k.getClass().getSimpleName()
                    + (k.getMessage() != null ? ": " + k.getMessage() : ""));
            return false;
        }
    }

    /** Outcome of {@link #checkCloudlogConnection()}: pass/fail plus a one-line reason. */
    public static final class ConnectionCheck {
        public final boolean ok;
        /** Why it failed (HTTP status, transport error, bad key…); null when {@link #ok}. */
        public final String detail;

        ConnectionCheck(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    public static boolean CheckCloudlogConnection(){
        return checkCloudlogConnection().ok;
    }

    /**
     * Verifies the configured Cloudlog/Wavelog/Nextlog address + API key via
     * {@code api/auth/[key]} and says <em>why</em> when it fails. Before #756 every failure
     * — a cleartext block, a 404 from a no-rewrite install, a typo, a dead host — collapsed
     * to a bare "Fail" in the settings dialog with nothing in debug.log.
     */
    public static ConnectionCheck checkCloudlogConnection() {
        String base = CloudlogEndpoint.normalizeBase(GeneralVariables.getCloudlogServerAddress());
        String apiKey = GeneralVariables.getCloudlogServerApiKey();
        if (base == null) {
            return new ConnectionCheck(false, "no server address configured");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return new ConnectionCheck(false, "no API key configured");
        }
        // The Cloudlog auth endpoint takes the key as a path segment, so the constructed
        // URL is unavoidably credential-bearing; cloudlogGet redacts it before logging.
        StringBuilder why = new StringBuilder();
        String result = cloudlogGet(base, "api/auth/" + apiKey.trim(), why);
        if (result == null) {
            return new ConnectionCheck(false, why.length() > 0 ? why.toString() : "no response");
        }
        return interpretAuthResponse(result);
    }

    /**
     * Interprets the body of {@code api/auth/[key]}. Nextlog and Wavelog both implement it
     * but return slightly different shapes (XML declaration, extra whitespace, etc.), so
     * match on the meaningful markers. Pure — unit-tested.
     */
    static ConnectionCheck interpretAuthResponse(String body) {
        String compact = body == null ? "" : body.replaceAll("\\s+", "");
        boolean valid = compact.contains("<status>Valid</status>");
        boolean rw = compact.contains("<rights>rw</rights>");
        if (valid && rw) return new ConnectionCheck(true, null);
        if (valid) return new ConnectionCheck(false, "API key is read-only (needs rw rights)");
        if (compact.contains("<status>")) {
            return new ConnectionCheck(false, "API key rejected by server");
        }
        return new ConnectionCheck(false, "unexpected reply (not a Cloudlog API?)");
    }

    public static boolean CheckQRZConnection(){
        String apiKey = GeneralVariables.getQrzApiKey();
        try{
            // POST so the API key is in the body rather than the URL, where it could leak
            // via proxies, server access logs, or our own logcat.
            String body = "KEY=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
                    + "&ACTION=STATUS";
            String result = sendPostFormRequest("https://logbook.qrz.com/api", body);
            if (result == null) {
                Log.d(TAG, "QRZ connection failed: no response");
                return false;
            }
            String qrzResult = parseQrzResult(result);
            Log.d(TAG, "QRZ status RESULT=" + qrzResult);
            return "OK".equals(qrzResult);
        }catch (Exception e){
            Log.d(TAG, "QRZ status error: " + e.getClass().getSimpleName());
            return false;
        }
    }

    public static boolean UploadToQRZ(QSLRecord qslRecord){
        // Convert to ADIF format
        String logStr = QSLRecordToADIF(qslRecord, ServiceType.QRZ);
        return uploadAdifToQrz(logStr);
    }

    /**
     * Posts a single ADIF record to QRZ. Returns true if QRZ returned RESULT=OK or
     * RESULT=REPLACE (the latter means the QSO already existed and was updated —
     * still a success from a "the record is now on QRZ" standpoint).
     */
    public static boolean uploadAdifToQrz(String adif) {
        return uploadAdifToQrz(adif, null);
    }

    /**
     * As {@link #uploadAdifToQrz(String)}, but records the rejection reason into
     * {@code failureOut} when one is supplied. QRZ answers with {@code RESULT=FAIL}
     * plus a {@code REASON=...} pair, which is worth showing verbatim.
     */
    public static boolean uploadAdifToQrz(String adif, StringBuilder failureOut) {
        String apikey = GeneralVariables.getQrzApiKey();
        if (apikey == null || apikey.isEmpty()) {
            appendFailure(failureOut, "no API key configured");
            return false;
        }
        try {
            // POST keeps both the API key and the ADIF payload out of the URL.
            String body = "KEY=" + URLEncoder.encode(apikey, StandardCharsets.UTF_8.name())
                    + "&ACTION=INSERT"
                    + "&ADIF=" + URLEncoder.encode(adif, StandardCharsets.UTF_8.name());
            String result = sendPostFormRequest("https://logbook.qrz.com/api", body);
            Log.d(TAG, "QRZ upload " + (result != null ? "succeeded" : "failed"));
            if (result == null) {
                appendFailure(failureOut, "no response from QRZ");
                return false;
            }
            // QRZ encodes status as RESULT=OK|FAIL|REPLACE within an &-separated body
            String qrzResult = parseQrzResult(result);
            if ("OK".equals(qrzResult) || "REPLACE".equals(qrzResult)) {
                return true;
            }
            appendFailure(failureOut, describeQrzFailure(result));
            return false;
        }catch (Exception k){
            Log.d(TAG, "QRZ upload error: " + k.getClass().getSimpleName());
            appendFailure(failureOut, k.getClass().getSimpleName()
                    + (k.getMessage() != null ? ": " + k.getMessage() : ""));
            return false;
        }
    }

    /**
     * Summarise a rejected QRZ upload as {@code RESULT=<r>} plus QRZ's {@code REASON}
     * when it supplied one. Pure (no network) so it is unit-testable.
     *
     * <p>Deliberately does not echo the whole response: it contains the submitted ADIF,
     * and this string is written to {@code debug.log}.
     */
    static String describeQrzFailure(String response) {
        String result = parseQrzResult(response);
        StringBuilder sb = new StringBuilder("RESULT=")
                .append(result == null ? "(none)" : result);
        if (response != null) {
            for (String s : response.split("&")) {
                String[] kv = s.split("=", 2);
                if (kv.length > 1 && "REASON".equalsIgnoreCase(kv[0].trim())) {
                    String reason = kv[1].replaceAll("\\s+", " ").trim();
                    if (!reason.isEmpty()) {
                        if (reason.length() > MAX_FAILURE_LEN) {
                            reason = reason.substring(0, MAX_FAILURE_LEN) + "…";
                        }
                        sb.append(": ").append(reason);
                    }
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the {@code RESULT} value from a QRZ logbook API response. QRZ
     * replies with an {@code &}-separated body of {@code KEY=VALUE} pairs (e.g.
     * {@code RESULT=OK&COUNT=1...}); this returns the value of the {@code RESULT}
     * field, or {@code null} if the response is null/empty or has no such field.
     */
    static String parseQrzResult(String response) {
        if (response == null || response.isEmpty()) return null;
        for (String s : response.split("&")) {
            String[] split = s.split("=", 2);
            if (split.length > 1 && "RESULT".equals(split[0])) {
                return split[1];
            }
        }
        return null;
    }

    /**
     * Uploads one QSO to World Radio League (issue #800) — see {@link WrlApi}. Blocks; call
     * from a background thread. True when WRL accepted the contact.
     */
    public static boolean UploadToWrl(QSLRecord qslRecord) {
        return WrlApi.uploadRecord(qslRecord);
    }

    /** Whether any service with per-QSO sync flags (Cloudlog, QRZ, World Radio League) is on. */
    public static boolean anyUploadServiceEnabled() {
        return GeneralVariables.enableCloudlog || GeneralVariables.enableQRZ
                || GeneralVariables.enableWRL;
    }

    /**
     * Progress callback used during a batch re-upload.
     */
    public interface SyncProgress {
        void onProgress(int done, int total, int cloudlogOk, int qrzOk, int wrlOk);
    }

    public static class SyncResult {
        public final int total;
        public final int cloudlogOk;
        public final int qrzOk;
        public final boolean cloudlogAttempted;
        public final boolean qrzAttempted;
        /**
         * Why the first failed Cloudlog upload of this run failed, or null if none did.
         * Only the first is kept: when a server is broken every row fails the same way,
         * and repeating that 113 times helps nobody.
         */
        public final String cloudlogError;
        /** Same, for QRZ. */
        public final String qrzError;
        public final int wrlOk;
        public final boolean wrlAttempted;
        /** Same, for World Radio League. */
        public final String wrlError;
        /**
         * Rows not uploaded to Cloudlog/Wavelog because the logbook already had them (issue
         * #936), either found in the pre-sync download or rejected by the server as a
         * duplicate. They are marked synced, like an accepted upload.
         */
        public final int cloudlogAlreadyPresent;
        /**
         * QSOs the Wavelog logbook had and the local log didn't, inserted locally during this
         * sync (issue #965). 0 unless "Download QSOs from Wavelog when syncing" is on.
         */
        public final int cloudlogDownloaded;

        public SyncResult(int total, int cloudlogOk, int qrzOk,
                          boolean cloudlogAttempted, boolean qrzAttempted) {
            this(total, cloudlogOk, qrzOk, cloudlogAttempted, qrzAttempted, null, null);
        }

        public SyncResult(int total, int cloudlogOk, int qrzOk,
                          boolean cloudlogAttempted, boolean qrzAttempted,
                          String cloudlogError, String qrzError) {
            this(total, cloudlogOk, qrzOk, 0, cloudlogAttempted, qrzAttempted, false,
                    cloudlogError, qrzError, null);
        }

        public SyncResult(int total, int cloudlogOk, int qrzOk, int wrlOk,
                          boolean cloudlogAttempted, boolean qrzAttempted, boolean wrlAttempted,
                          String cloudlogError, String qrzError, String wrlError) {
            this(total, cloudlogOk, qrzOk, wrlOk, cloudlogAttempted, qrzAttempted, wrlAttempted,
                    cloudlogError, qrzError, wrlError, 0);
        }

        public SyncResult(int total, int cloudlogOk, int qrzOk, int wrlOk,
                          boolean cloudlogAttempted, boolean qrzAttempted, boolean wrlAttempted,
                          String cloudlogError, String qrzError, String wrlError,
                          int cloudlogAlreadyPresent) {
            this(total, cloudlogOk, qrzOk, wrlOk, cloudlogAttempted, qrzAttempted, wrlAttempted,
                    cloudlogError, qrzError, wrlError, cloudlogAlreadyPresent, 0);
        }

        public SyncResult(int total, int cloudlogOk, int qrzOk, int wrlOk,
                          boolean cloudlogAttempted, boolean qrzAttempted, boolean wrlAttempted,
                          String cloudlogError, String qrzError, String wrlError,
                          int cloudlogAlreadyPresent, int cloudlogDownloaded) {
            this.total = total;
            this.cloudlogOk = cloudlogOk;
            this.qrzOk = qrzOk;
            this.wrlOk = wrlOk;
            this.cloudlogAttempted = cloudlogAttempted;
            this.qrzAttempted = qrzAttempted;
            this.wrlAttempted = wrlAttempted;
            this.cloudlogError = cloudlogError;
            this.qrzError = qrzError;
            this.wrlError = wrlError;
            this.cloudlogAlreadyPresent = cloudlogAlreadyPresent;
            this.cloudlogDownloaded = cloudlogDownloaded;
        }
    }

    /**
     * The {@code WHERE} clause (with a leading space) selecting QSLTable rows that
     * still need an upload to at least one enabled service. Returns an empty string
     * when no service is enabled (caller should not query in that case). Single
     * source of truth shared by {@link #syncAllQSOs} and {@link #countUnsyncedQSOs}.
     */
    private static String unsyncedFilter(boolean cloudlog, boolean qrz, boolean wrl) {
        StringBuilder where = new StringBuilder();
        if (cloudlog) where.append("synced_cloudlog = 0");
        if (qrz) where.append(where.length() > 0 ? " or " : "").append("synced_qrz = 0");
        if (wrl) where.append(where.length() > 0 ? " or " : "").append("synced_wrl = 0");
        return where.length() > 0 ? " where " + where : "";
    }

    /**
     * Number of QSLTable rows still awaiting upload to an enabled service. Returns 0
     * when neither Cloudlog nor QRZ is enabled (nothing to do). Lets the auto-sync
     * skip spawning upload work when there's nothing pending. Uses the same filter as
     * {@link #syncAllQSOs} so the count and the actual sync always agree.
     */
    public static int countUnsyncedQSOs(SQLiteDatabase db) {
        boolean cl = GeneralVariables.enableCloudlog;
        boolean qrz = GeneralVariables.enableQRZ;
        boolean wrl = GeneralVariables.enableWRL;
        if (db == null || (!cl && !qrz && !wrl)) return 0;
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "select count(*) from QSLTable" + unsyncedFilter(cl, qrz, wrl), null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "countUnsyncedQSOs error: " + e.getClass().getSimpleName());
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    /** One Cloudlog ADIF upload; a seam so the catch-up loop is testable without a server. */
    interface CloudlogUploader {
        boolean upload(String adif, StringBuilder failureOut);
    }

    /** The uploader {@link #syncAllQSOs} uses. Tests swap it; production never does. */
    static CloudlogUploader cloudlogUploader = ThirdPartyService::uploadAdifToCloudlog;

    /**
     * Upload every not-yet-synced QSO in QSLTable to whichever third-party services the
     * user has enabled.
     *
     * <p>Cloudlog/Wavelog rows are first checked against the QSOs the logbook already has
     * ({@link #findRowsAlreadyInCloudlog}); a row found there is marked synced and not sent.
     * Without that, connecting an existing Wavelog logbook re-uploaded every QSO in it
     * (issue #936). A row the server rejects as a duplicate is marked synced too, so it is
     * not retried on every sync.
     *
     * <p>Never imports QSOs from Wavelog; use {@link #syncAllQSOs(DatabaseOpr, SyncProgress)}
     * for that.
     *
     * Blocks the calling thread — invoke from a background thread/coroutine.
     */
    public static SyncResult syncAllQSOs(SQLiteDatabase db, SyncProgress progress) {
        return syncAllQSOs(db, progress, null);
    }

    /**
     * As {@link #syncAllQSOs(SQLiteDatabase, SyncProgress)}, and when "Download QSOs from
     * Wavelog when syncing" is on ({@link GeneralVariables#cloudlogDownloadQsos}) the QSOs the
     * Wavelog logbook has and this log doesn't are inserted first (issue #965), through the
     * same {@link DatabaseOpr#doInsertQSLData} call the ADIF import uses.
     */
    public static SyncResult syncAllQSOs(DatabaseOpr opr, SyncProgress progress) {
        if (opr == null) return syncAllQSOs((SQLiteDatabase) null, progress);
        SyncResult result = syncAllQSOs(opr.getDb(), progress, (record, after) -> {
            // A remote QSO with the same call, date, time_on and mode as a local row the
            // matcher didn't pair (another band, say) would make doInsertQSLData update that
            // row with the remote QSO's grid and reports. Leave the local row alone instead.
            if (opr.checkIsQSL(record)) {
                after.doAfterInsert(false, false, false);
                return;
            }
            // No POTA stamping: these QSOs predate any activation running now.
            opr.doInsertQSLData(record, after, false, false);
        });
        // New rows change who counts as worked before; reload the cache the decode list's
        // worked/needed highlighting reads, as a fresh app start would.
        if (result.cloudlogDownloaded > 0) opr.getAllQSLCallsigns();
        return result;
    }

    /**
     * Inserts one QSO into the local log. {@link DatabaseOpr#doInsertQSLData} in the app; a
     * seam so the two-way sync can be tested without the whole database helper.
     */
    interface QsoInserter {
        void insert(QSLRecord record, AfterInsertQSLData afterInsert);
    }

    /**
     * The sync itself. With an {@code inserter} and the download setting on, the Wavelog
     * logbook is read once and used for both directions, in this order: download, insert
     * the remote QSOs no local row accounts for, then upload the local rows the remote log
     * doesn't have.
     */
    static SyncResult syncAllQSOs(SQLiteDatabase db, SyncProgress progress, QsoInserter inserter) {
        boolean cl = GeneralVariables.enableCloudlog;
        boolean qrz = GeneralVariables.enableQRZ;
        boolean wrl = GeneralVariables.enableWRL;
        int total = 0;
        int cloudlogOk = 0;
        int qrzOk = 0;
        int wrlOk = 0;
        int cloudlogAlreadyPresent = 0;
        int cloudlogDownloaded = 0;
        String cloudlogError = null;
        String qrzError = null;
        String wrlError = null;
        if (db == null || (!cl && !qrz && !wrl)) {
            return new SyncResult(0, 0, 0, 0, cl, qrz, wrl, null, null, null);
        }
        // Ids of the pending rows the Wavelog logbook already has, when the two-way sync
        // downloaded it; null when it didn't, and the upload pass checks on its own (#936).
        Set<Long> cloudlogPresentIds = null;
        if (cl && inserter != null && GeneralVariables.cloudlogDownloadQsos) {
            cloudlogPresentIds = new HashSet<>();
            StringBuilder downloadError = new StringBuilder();
            cloudlogDownloaded = downloadMissingFromCloudlog(db, inserter, cloudlogPresentIds,
                    downloadError);
            if (cloudlogDownloaded < 0) {
                // The local log couldn't be read, so nothing was paired: let the upload pass
                // check for rows the logbook already has on its own (#936).
                cloudlogPresentIds = null;
                cloudlogDownloaded = 0;
            }
            if (downloadError.length() > 0) cloudlogError = downloadError.toString();
        }
        Cursor cursor = null;
        try {
            // Skip rows already accepted by every enabled service. The user can still
            // tell something happened via the dialog's row counts, and a re-press isn't
            // wasted on already-confirmed records.
            cursor = db.rawQuery(
                    "select * from QSLTable" + unsyncedFilter(cl, qrz, wrl) + " order by id asc", null);
            total = cursor.getCount();
            if (progress != null) progress.onProgress(0, total, 0, 0, 0);
            int idCol = cursor.getColumnIndex("id");
            int syncedClCol = cursor.getColumnIndex("synced_cloudlog");
            int syncedQrzCol = cursor.getColumnIndex("synced_qrz");
            int syncedWrlCol = cursor.getColumnIndex("synced_wrl");
            // Cleared when WRL says every further upload would be refused the same way (bad
            // key, no logbook, daily quota, offline): a 113-row backlog must not turn into 113
            // identical rejections against a rate-limited API.
            boolean wrlLive = wrl;
            // Same for Cloudlog: a wrong Station ID or API key refuses every row identically.
            boolean clLive = cl;
            // Cursor positions of the rows the Cloudlog/Wavelog logbook already has (#936).
            Set<Integer> cloudlogPresent = !cl ? Collections.<Integer>emptySet()
                    : cloudlogPresentIds != null ? positionsOfIds(cursor, idCol, cloudlogPresentIds)
                    : findRowsAlreadyInCloudlog(cursor, syncedClCol);
            int done = 0;
            while (cursor.moveToNext()) {
                long rowId = idCol >= 0 ? cursor.getLong(idCol) : -1;
                boolean alreadyCl = syncedClCol >= 0 && cursor.getInt(syncedClCol) == 1;
                boolean alreadyQrz = syncedQrzCol >= 0 && cursor.getInt(syncedQrzCol) == 1;
                boolean alreadyWrl = syncedWrlCol >= 0 && cursor.getInt(syncedWrlCol) == 1;
                if (clLive && !alreadyCl) {
                    if (cloudlogPresent.contains(cursor.getPosition())) {
                        cloudlogAlreadyPresent++;
                        if (rowId >= 0) markRowSynced(db, rowId, "synced_cloudlog");
                    } else {
                        String adif = buildAdifFromCursor(cursor, ServiceType.Cloudlog);
                        StringBuilder why = new StringBuilder();
                        if (cloudlogUploader.upload(adif, why)) {
                            cloudlogOk++;
                            if (rowId >= 0) markRowSynced(db, rowId, "synced_cloudlog");
                        } else if (isDuplicateRejection(why.toString())) {
                            cloudlogAlreadyPresent++;
                            if (rowId >= 0) markRowSynced(db, rowId, "synced_cloudlog");
                        } else if (why.length() > 0) {
                            boolean stopsBatch =
                                    CloudlogFailureHint.cloudlogFailureStopsBatch(why.toString());
                            // A Station ID / API key rejection replaces an earlier download
                            // error: it is the one the operator has to fix, and its hint
                            // only shows when it is the reported error.
                            if (cloudlogError == null || stopsBatch) cloudlogError = why.toString();
                            if (stopsBatch) {
                                clLive = false;
                                GeneralVariables.fileLog("Cloudlog: catch-up sync stopped after a "
                                        + "Station ID / API key rejection; remaining rows stay queued");
                            }
                        }
                    }
                }
                if (qrz && !alreadyQrz) {
                    String adif = buildAdifFromCursor(cursor, ServiceType.QRZ);
                    StringBuilder why = qrzError == null ? new StringBuilder() : null;
                    if (uploadAdifToQrz(adif, why)) {
                        qrzOk++;
                        if (rowId >= 0) markRowSynced(db, rowId, "synced_qrz");
                    } else if (why != null && why.length() > 0) {
                        qrzError = why.toString();
                    }
                }
                if (wrlLive && !alreadyWrl) {
                    StringBuilder why = new StringBuilder();
                    WrlApi.UploadResult r = WrlApi.uploadFields(WrlApi.fromCursor(cursor), why);
                    if (r == WrlApi.UploadResult.OK) {
                        wrlOk++;
                        if (rowId >= 0) markRowSynced(db, rowId, "synced_wrl");
                    } else {
                        if (wrlError == null && why.length() > 0) wrlError = why.toString();
                        if (r == WrlApi.UploadResult.FAILED_STOP_BATCH) wrlLive = false;
                    }
                }
                done++;
                if (progress != null) progress.onProgress(done, total, cloudlogOk, qrzOk, wrlOk);
            }
        } catch (Exception e) {
            Log.e(TAG, "syncAllQSOs error: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return new SyncResult(total, cloudlogOk, qrzOk, wrlOk, cl, qrz, wrl,
                cloudlogError, qrzError, wrlError, cloudlogAlreadyPresent, cloudlogDownloaded);
    }

    /** Remote QSOs inserted per transaction, so a big first download isn't one fsync per row. */
    private static final int IMPORT_BATCH_SIZE = 200;

    /**
     * The download half of the two-way sync (issue #965). Reads the Wavelog logbook once,
     * pairs it with every local row (synced or not) using #936's one-to-one matcher, inserts
     * each remote QSO no local row accounts for, and adds to {@code presentIdsOut} the ids of
     * the not-yet-uploaded rows the logbook already has, for the upload pass that follows.
     *
     * <p>Inserted rows are marked synced to Cloudlog, QRZ and World Radio League. They came
     * from another logger by way of Wavelog, so they are already in Wavelog, and a download
     * from Wavelog shouldn't, on its own, push another logger's QSOs to QRZ or WRL.
     *
     * <p>Does nothing when the server can't list its QSOs (plain Cloudlog); the upload pass
     * then sends every pending row, as it does when the remote log can't be read in #936.
     *
     * @param errorOut gets a message for the sync dialog when the local log can't be read or
     *                 an insert batch fails
     * @return the number of QSOs inserted, or -1 when the local log couldn't be read (nothing
     *         paired, so {@code presentIdsOut} says nothing)
     */
    static int downloadMissingFromCloudlog(SQLiteDatabase db, QsoInserter inserter,
                                           Set<Long> presentIdsOut, StringBuilder errorOut) {
        CloudlogRemoteLog remote = fetchCloudlogRemoteLog(true);
        if (remote == null) return 0;

        List<CloudlogRemoteLog.Local> locals = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        List<Boolean> pending = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("select id, call, qso_date, time_on, band, mode, station_callsign,"
                    + " synced_cloudlog from QSLTable order by id asc", null);
            while (c.moveToNext()) {
                ids.add(c.getLong(0));
                locals.add(new CloudlogRemoteLog.Local(c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getString(5), c.getString(6)));
                pending.add(c.isNull(7) || c.getInt(7) == 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "two-way sync: local log read failed: " + e.getClass().getSimpleName());
            errorOut.append("Download from Wavelog skipped: local log read failed (")
                    .append(e.getClass().getSimpleName()).append(')');
            return -1;
        } finally {
            if (c != null) c.close();
        }

        CloudlogRemoteLog.Pairing pairing = remote.pair(locals);
        for (int i : pairing.presentLocals) {
            if (pending.get(i)) presentIdsOut.add(ids.get(i));
        }
        if (remote.tooLargeToImport()) {
            GeneralVariables.fileLog("Cloudlog: download skipped, logbook has " + remote.size()
                    + " QSOs (import limit " + CloudlogRemoteLog.MAX_KEPT_RECORDS + ")");
            errorOut.append("Download from Wavelog skipped: the logbook has more than ")
                    .append(CloudlogRemoteLog.MAX_KEPT_RECORDS)
                    .append(" QSOs. Import an ADIF export from Wavelog instead.");
            return 0;
        }
        int inserted = insertRemoteQsos(db, inserter, pairing.unmatchedRemote, errorOut);
        GeneralVariables.fileLog("Cloudlog: downloaded " + inserted + " of "
                + pairing.unmatchedRemote.size() + " QSOs missing from the local log");
        return inserted;
    }

    /**
     * Inserts remote QSOs (ADIF field maps) the way the ADIF import does, and marks each new
     * row synced everywhere ({@link #downloadMissingFromCloudlog} says why). A record the
     * insert treats as one the log already has (same call, date, time_on and mode) only
     * updates that row and is neither counted nor marked, so a local row still waiting to be
     * uploaded stays pending.
     *
     * <p>A QSO without a COMMENT is stored with an empty one, not the ADIF import's
     * "imported at ..." note: it was logged elsewhere, not imported by the user.
     *
     * @return the number of rows inserted
     */
    static int insertRemoteQsos(SQLiteDatabase db, QsoInserter inserter,
                                List<Map<String, String>> records, StringBuilder errorOut) {
        int inserted = 0;
        for (int start = 0; start < records.size(); start += IMPORT_BATCH_SIZE) {
            int end = Math.min(records.size(), start + IMPORT_BATCH_SIZE);
            int batch = 0;
            db.beginTransaction();
            try {
                for (int i = start; i < end; i++) {
                    HashMap<String, String> fields = new HashMap<>(records.get(i));
                    if (!fields.containsKey("COMMENT")) fields.put("COMMENT", "");
                    QSLRecord record = new QSLRecord(fields);
                    boolean[] isNew = {false};
                    inserter.insert(record, (invalid, newQsl, hasWarning) -> isNew[0] = newQsl && !invalid);
                    if (isNew[0]) {
                        markQsoSynced(db, record, true, true, true);
                        batch++;
                    }
                }
                db.setTransactionSuccessful();
            } catch (Exception e) {
                // The batch is rolled back. Leave it and the rest for the next sync rather
                // than skip the uploads that follow.
                Log.e(TAG, "two-way sync: insert failed: " + e.getClass().getSimpleName()
                        + " " + e.getMessage());
                GeneralVariables.fileLog("Cloudlog: download insert failed: "
                        + e.getClass().getSimpleName());
                errorOut.append("Download from Wavelog stopped after ").append(inserted)
                        .append(" QSOs: ").append(e.getClass().getSimpleName());
                return inserted;
            } finally {
                db.endTransaction();
            }
            inserted += batch;
        }
        return inserted;
    }

    /** Cursor positions of the rows whose id is in {@code ids}. Leaves the cursor before the first row. */
    private static Set<Integer> positionsOfIds(Cursor cursor, int idCol, Set<Long> ids) {
        Set<Integer> positions = new HashSet<>();
        if (idCol < 0 || ids.isEmpty()) return positions;
        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            if (ids.contains(cursor.getLong(idCol))) positions.add(cursor.getPosition());
        }
        cursor.moveToPosition(-1);
        return positions;
    }

    /**
     * Cursor positions of the rows not yet synced to Cloudlog/Wavelog that the logbook
     * already has. Downloads the remote log only when there is such a row, so a pass with
     * nothing to upload there costs no extra request. Empty when the server can't list its
     * QSOs (plain Cloudlog has no get_contacts_adif); uploads then go ahead as before.
     * All pending rows are matched together, so one remote QSO accounts for one row
     * ({@link CloudlogRemoteLog#matchPresent}). Leaves the cursor before the first row.
     */
    private static Set<Integer> findRowsAlreadyInCloudlog(Cursor cursor, int syncedClCol) {
        List<CloudlogRemoteLog.Local> pending = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            if (syncedClCol >= 0 && cursor.getInt(syncedClCol) == 1) continue;
            pending.add(new CloudlogRemoteLog.Local(colStr(cursor, "call"),
                    colStr(cursor, "qso_date"), colStr(cursor, "time_on"),
                    colStr(cursor, "band"), colStr(cursor, "mode"),
                    colStr(cursor, "station_callsign")));
            positions.add(cursor.getPosition());
        }
        cursor.moveToPosition(-1);
        Set<Integer> present = new HashSet<>();
        if (pending.isEmpty()) return present;
        CloudlogRemoteLog remote = fetchCloudlogRemoteLog(false);
        if (remote == null) return present;
        for (int i : remote.matchPresent(pending)) present.add(positions.get(i));
        return present;
    }

    /**
     * QSOs requested per {@code get_contacts_adif} page. Kept well under
     * {@link #MAX_RESPONSE_CHARS} even when an older Wavelog ignores the field list and
     * sends every ADIF field.
     */
    private static final int REMOTE_PAGE_SIZE = 2000;
    /** Page cap per station location, so a server that never advances its cursor can't loop. */
    static final int REMOTE_MAX_PAGES = 500;
    /**
     * Remote QSOs read in total before the download stops, across every station location.
     * Bounds the duplicate-check index (a small entry per QSO) on a huge or hostile log.
     */
    static final int REMOTE_MAX_QSOS = 250_000;
    /** {@link #REMOTE_MAX_QSOS}, lowered by tests. */
    static int remoteMaxQsos = REMOTE_MAX_QSOS;

    /**
     * Downloads the QSOs already in the user's Wavelog logbook, across all of the API key's
     * station locations (a QSO logged under another location is still the same QSO).
     * Returns null when nothing could be read: no API key, or a server without
     * {@code api/get_contacts_adif} (plain Cloudlog). A partial download is still returned,
     * since every QSO in it really is on the server. Blocks; call from a background thread.
     *
     * @param forImport ask for {@link CloudlogRemoteLog#IMPORT_FIELDS} and keep every QSO's
     *                  fields, so the ones missing locally can be inserted (issue #965);
     *                  otherwise only the fields the duplicate check needs
     */
    static CloudlogRemoteLog fetchCloudlogRemoteLog(boolean forImport) {
        String base = CloudlogEndpoint.normalizeBase(GeneralVariables.getCloudlogServerAddress());
        String key = GeneralVariables.getCloudlogServerApiKey();
        if (base == null || key == null || key.trim().isEmpty()) return null;
        key = key.trim();

        List<StationProfile> profiles =
                parseStationProfiles(cloudlogApi.get(base, "api/station_info/" + key));
        List<String> stationIds = stationIdsOf(profiles);
        String configured = GeneralVariables.getCloudlogStationID();
        if (stationIds.isEmpty() && configured != null && !configured.trim().isEmpty()) {
            stationIds.add(configured.trim());
        }

        CloudlogRemoteLog remote = new CloudlogRemoteLog(forImport);
        String[] fields = forImport ? CloudlogRemoteLog.IMPORT_FIELDS : CloudlogRemoteLog.FIELDS;
        // A row without a station callsign is uploaded to the configured location, so it
        // is only "already there" if that location's callsign logged it.
        remote.setDefaultStationCallsign(stationCallsignOf(profiles, configured));
        boolean anyRead = false;
        try {
            stations:
            for (String stationId : stationIds) {
                long cursor = 0;
                for (int page = 0; page < REMOTE_MAX_PAGES; page++) {
                    if (remote.size() >= remoteMaxQsos) {
                        GeneralVariables.fileLog("Cloudlog: stopped reading the logbook at "
                                + remote.size() + " QSOs");
                        break stations;
                    }
                    StringBuilder why = new StringBuilder();
                    String body = cloudlogApi.post(base, "api/get_contacts_adif",
                            CloudlogRemoteLog.pageRequestJson(key, stationId, cursor,
                                    REMOTE_PAGE_SIZE, fields),
                            why);
                    if (body == null) {
                        GeneralVariables.fileLog("Cloudlog: can't list QSOs for station "
                                + stationId + ": " + why);
                        break;
                    }
                    anyRead = true;
                    long next = remote.addPage(body);
                    if (next <= cursor) break;
                    cursor = next;
                }
            }
        } catch (OutOfMemoryError | StackOverflowError e) {
            // Drop the partial log so its memory is freed, and carry on as if the server
            // couldn't list its QSOs: uploads still run. Uncaught, this is a crash on every
            // launch while auto-sync keeps retrying the same download.
            GeneralVariables.fileLog("Cloudlog: reading the logbook failed: "
                    + e.getClass().getSimpleName());
            return null;
        }
        if (!anyRead) return null;
        GeneralVariables.fileLog("Cloudlog: " + remote.size()
                + " QSOs already in the logbook across " + stationIds.size() + " station(s)");
        return remote;
    }

    /** Station ids from an {@code api/station_info} reply; empty on null or bad JSON. */
    static List<String> parseStationIds(String json) {
        return stationIdsOf(parseStationProfiles(json));
    }

    /** The non-blank station ids of {@code profiles}, in order, without repeats. */
    static List<String> stationIdsOf(List<StationProfile> profiles) {
        List<String> ids = new ArrayList<>();
        for (StationProfile p : profiles) {
            String id = p.stationId == null ? "" : p.stationId.trim();
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /** Callsign of the station profile with id {@code stationId}, or "" when not listed. */
    static String stationCallsignOf(List<StationProfile> profiles, String stationId) {
        if (stationId == null) return "";
        String want = stationId.trim();
        for (StationProfile p : profiles) {
            if (p.stationId != null && p.stationId.trim().equals(want)) {
                return p.callsign == null ? "" : p.callsign;
            }
        }
        return "";
    }

    /**
     * Whether a failed Cloudlog upload was Wavelog refusing a QSO it already has. Wavelog
     * answers HTTP 400 with "... Duplicate for &lt;station&gt;" in its messages. That text is
     * translated into the Wavelog user's language, so this only catches English; the
     * pre-sync download is the main defence.
     */
    static boolean isDuplicateRejection(String failure) {
        return failure != null && failure.startsWith("HTTP 400")
                && failure.toLowerCase(Locale.US).contains("duplicate for");
    }

    /**
     * Whether the configured server can list its QSOs ({@code api/get_contacts_adif}), which
     * "Download QSOs from Wavelog when syncing" needs (issue #965). Asks for the page after
     * a cursor no logbook reaches, so nothing is downloaded. TRUE on Wavelog; FALSE when the
     * server has no such endpoint (plain Cloudlog); null when it couldn't be told (not
     * configured, offline, wrong key). Blocks; call from a background thread.
     */
    public static Boolean cloudlogCanListQsos() {
        String base = CloudlogEndpoint.normalizeBase(GeneralVariables.getCloudlogServerAddress());
        String key = GeneralVariables.getCloudlogServerApiKey();
        if (base == null || key == null || key.trim().isEmpty()) return null;
        key = key.trim();
        String stationId = GeneralVariables.getCloudlogStationID();
        if (stationId == null || stationId.trim().isEmpty()) {
            List<String> ids = parseStationIds(cloudlogApi.get(base, "api/station_info/" + key));
            if (ids.isEmpty()) return null;
            stationId = ids.get(0);
        }
        StringBuilder why = new StringBuilder();
        String body = cloudlogApi.post(base, "api/get_contacts_adif",
                CloudlogRemoteLog.pageRequestJson(key, stationId.trim(), Integer.MAX_VALUE, 1),
                why);
        return contactsListingSupport(body, why.toString());
    }

    /**
     * Reads a {@code get_contacts_adif} probe reply for {@link #cloudlogCanListQsos}: a
     * "successful" JSON reply means the server lists QSOs; a 404, or a 2xx body that isn't
     * JSON (an HTML error page), means it doesn't. Anything else (a JSON refusal such as a
     * station id the key doesn't own, a 401, a timeout) leaves it unknown.
     */
    static Boolean contactsListingSupport(String body, String failure) {
        if (body != null) {
            JSONObject obj;
            try {
                obj = new JSONObject(body);
            } catch (StackOverflowError e) {
                // Deeply nested brackets: not a Wavelog reply, and no reason to crash the
                // Logging settings screen over it.
                return null;
            } catch (Exception e) {
                return Boolean.FALSE;
            }
            return "successful".equalsIgnoreCase(obj.optString("status")) ? Boolean.TRUE : null;
        }
        if (failure != null && failure.startsWith("HTTP 404")) return Boolean.FALSE;
        return null;
    }

    /**
     * Builds a single-record ADIF body from a QSLTable cursor row. Mirrors the field
     * set produced by {@link #QSLRecordToADIF} so Cloudlog/QRZ see identical payloads
     * to the immediate-after-QSO upload path.
     */
    private static String buildAdifFromCursor(Cursor c, ServiceType serv) {
        StringBuilder s = new StringBuilder();
        appendAdif(s, "call", colStr(c, "call"));
        appendAdif(s, "gridsquare", colStr(c, "gridsquare"));
        appendAdif(s, "mode", colStr(c, "mode"));
        appendAdif(s, "rst_sent", colStr(c, "rst_sent"));
        appendAdif(s, "rst_rcvd", colStr(c, "rst_rcvd"));
        appendAdif(s, "qso_date", colStr(c, "qso_date"));
        appendAdif(s, "time_on", colStr(c, "time_on"));
        appendAdif(s, "band", colStr(c, "band"));
        appendAdif(s, "qso_date_off", colStr(c, "qso_date_off"));
        appendAdif(s, "time_off", colStr(c, "time_off"));

        // QSLTable stores freq as a string; QSLRecordToADIF outputs MHz floats for
        // both Cloudlog and QRZ. The DB column is already in MHz form (set by the
        // ADIF export path) so we can pass it through verbatim.
        appendAdif(s, "freq", colStr(c, "freq"));

        appendAdif(s, "station_callsign", colStr(c, "station_callsign"));
        appendAdif(s, "my_gridsquare", colStr(c, "my_gridsquare"));

        String comment = colStr(c, "comment");
        if (comment == null) comment = "";
        s.append(String.format("<comment:%d>%s <eor>\n", AdifFormat.utf8Length(comment), comment));
        return s.toString();
    }

    private static void appendAdif(StringBuilder sb, String tag, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append(String.format("<%s:%d>%s ", tag, AdifFormat.utf8Length(value), value));
    }

    private static String colStr(Cursor c, String name) {
        int idx = c.getColumnIndex(name);
        if (idx < 0) return null;
        return c.getString(idx);
    }

    private static void markRowSynced(SQLiteDatabase db, long rowId, String column) {
        try {
            db.execSQL("update QSLTable set " + column + " = 1 where id = ?",
                    new Object[]{rowId});
        } catch (Exception e) {
            Log.w(TAG, "markRowSynced(" + column + ") failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Mark the freshly-inserted QSL row as accepted by a service. Looks the row up
     * by (call, qso_date, time_on, mode) because the immediate-sync path doesn't
     * carry the row id. Safe to call from a background thread.
     */
    public static void markQsoSynced(SQLiteDatabase db, QSLRecord r,
                                     boolean cloudlogOk, boolean qrzOk) {
        markQsoSynced(db, r, cloudlogOk, qrzOk, false);
    }

    /** As {@link #markQsoSynced(SQLiteDatabase, QSLRecord, boolean, boolean)}, plus World Radio League. */
    public static void markQsoSynced(SQLiteDatabase db, QSLRecord r,
                                     boolean cloudlogOk, boolean qrzOk, boolean wrlOk) {
        if (db == null || r == null) return;
        if (!cloudlogOk && !qrzOk && !wrlOk) return;
        try {
            StringBuilder set = new StringBuilder();
            if (cloudlogOk) set.append("synced_cloudlog = 1");
            if (qrzOk) {
                if (set.length() > 0) set.append(", ");
                set.append("synced_qrz = 1");
            }
            if (wrlOk) {
                if (set.length() > 0) set.append(", ");
                set.append("synced_wrl = 1");
            }
            db.execSQL("update QSLTable set " + set
                            + " where [call] = ? and qso_date = ? and time_on = ? and mode = ?",
                    new Object[]{
                            r.getToCallsign(),
                            r.getQso_date(),
                            r.getTime_on(),
                            r.getMode()
                    });
        } catch (Exception e) {
            Log.w(TAG, "markQsoSynced failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Redacts credentials from a URL before it is logged. Two things are masked:
     * <ul>
     *   <li>the Cloudlog/Wavelog API key — a path segment following {@code create_station/},
     *       {@code station_info/}, or {@code auth/} — replaced with {@code ***};</li>
     *   <li>any authority user-info ({@code http://user:pw@host/…}) — replaced with
     *       {@code ***@}. Self-hosted logbooks behind basic auth are entered exactly that
     *       way, and {@link CloudlogEndpoint#normalizeBase} preserves it, so without this
     *       every {@code Cloudlog: GET/POST} line in debug.log would carry the password.</li>
     * </ul>
     * Only the LOGGED string is redacted — the real request URL is unchanged. Returns null
     * unchanged.
     */
    static String redactUrlApiKey(String url) {
        if (url == null) {
            return null;
        }
        String out = url.replaceFirst("^([A-Za-z][A-Za-z0-9+.\\-]*://)[^/?#@\\s]*@", "$1***@");
        return out.replaceAll("(create_station/|station_info/|auth/)[^/?#\\s]+", "$1***");
    }

    public static String sendPostRequest(String url, String json) throws IOException {
        return sendPostRequest(url, json, null);
    }

    /**
     * As {@link #sendPostRequest(String, String)}, but when the POST fails and
     * {@code failureOut} is non-null, the reason is appended to it — HTTP status plus
     * whatever the server said in the error body.
     *
     * <p>Why this exists: the failure reason used to go only to {@code Log.d}, so a server
     * that rejected every upload looked identical in {@code debug.log} to "nothing to do".
     * A Cloudlog-compatible backend reports real, actionable problems this way (a missing
     * DB column, a bad station id, an expired key), and none of it was reaching the user.
     */
    static String sendPostRequest(String url, String json, StringBuilder failureOut)
            throws IOException {
        return sendPostRequest(url, json, failureOut, null);
    }

    /**
     * As {@link #sendPostRequest(String, String, StringBuilder)}, additionally reporting the
     * final HTTP status in {@code statusOut[0]} (0 if none was received).
     */
    static String sendPostRequest(String url, String json, StringBuilder failureOut,
                                  int[] statusOut) throws IOException {
        // HttpURLConnection does not auto-follow 30x on a POST. Walk redirects manually
        // (capped) so deployments that rewrite trailing slashes, http→https, or move
        // the API path still work.
        String currentUrl = url;
        if (statusOut != null && statusOut.length > 0) statusOut[0] = 0;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            BufferedReader reader = null;
            try {
                URL urlObj = new URL(currentUrl);
                conn = (HttpURLConnection) urlObj.openConnection();
                applyTimeouts(conn);
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");

                OutputStream os = conn.getOutputStream();
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (statusOut != null && statusOut.length > 0) statusOut[0] = responseCode;
                // Cloudlog uses HTTP_CREATED as the response for successful record creation
                if (responseCode == HttpURLConnection.HTTP_OK
                        || responseCode == HttpURLConnection.HTTP_CREATED) {
                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream(),
                            StandardCharsets.UTF_8));
                    return readBodyCapped(reader, MAX_RESPONSE_CHARS);
                }
                if (responseCode == 301 || responseCode == 302 || responseCode == 307
                        || responseCode == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        Log.d(TAG, "POST " + redactUrlApiKey(currentUrl) + " -> HTTP "
                                + responseCode + " (no Location header)");
                        appendFailure(failureOut,
                                "HTTP " + responseCode + " redirect with no Location header");
                        return null;
                    }
                    // Resolve relative redirect against the previous URL.
                    URL resolved = new URL(urlObj, loc);
                    if (!redirectAllowed(urlObj, resolved)) {
                        // The body carries the API key: never re-send it to another host,
                        // or in the clear after an https request.
                        Log.d(TAG, "POST " + redactUrlApiKey(currentUrl) + " -> HTTP "
                                + responseCode + " redirect refused");
                        appendFailure(failureOut, "HTTP " + responseCode + " redirect to "
                                + resolved.getProtocol() + "://" + resolved.getHost()
                                + " refused: set that address in the Cloudlog settings");
                        return null;
                    }
                    Log.d(TAG, "POST " + redactUrlApiKey(currentUrl) + " -> HTTP " + responseCode
                            + " redirect to " + redactUrlApiKey(resolved.toString()));
                    currentUrl = resolved.toString();
                    continue;
                }
                // Non-2xx, non-redirect: capture error body (avoiding the JSON request body
                // since it contains the API key).
                StringBuilder err = new StringBuilder();
                try {
                    java.io.InputStream es = conn.getErrorStream();
                    if (es != null) {
                        BufferedReader eread = new BufferedReader(new InputStreamReader(es,
                                StandardCharsets.UTF_8));
                        String line;
                        while ((line = eread.readLine()) != null) {
                            err.append(line);
                            if (err.length() > 400) break;
                        }
                        eread.close();
                    }
                } catch (Exception ignored) {}
                Log.d(TAG, "POST " + redactUrlApiKey(currentUrl) + " -> HTTP " + responseCode
                        + (err.length() > 0 ? " body=" + err : ""));
                appendFailure(failureOut, describeHttpFailure(responseCode, err.toString()));
                return null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
                if (reader != null) {
                    reader.close();
                }
            }
        }
        Log.d(TAG, "POST " + redactUrlApiKey(url) + " exceeded redirect limit");
        appendFailure(failureOut, "exceeded redirect limit");
        return null;
    }

    /**
     * Longest response body read, in chars (about 16 MB of heap). A Wavelog page is a few
     * MB at most ({@link #REMOTE_PAGE_SIZE}); anything bigger is a broken or hostile server
     * and would otherwise be read whole into memory.
     */
    static final int MAX_RESPONSE_CHARS = 8_000_000;

    /**
     * Reads a response body the way the old {@code readLine()} loop did (line breaks
     * dropped), but in fixed-size chunks and only up to {@code maxChars}, so neither a huge
     * body nor one endless line can run the heap out.
     *
     * @throws IOException when the body is longer than {@code maxChars}
     */
    static String readBodyCapped(java.io.Reader reader, int maxChars) throws IOException {
        StringBuilder out = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
            for (int i = 0; i < n; i++) {
                char ch = buf[i];
                if (ch == '\n' || ch == '\r') continue;
                if (out.length() >= maxChars) {
                    throw new IOException("response larger than " + maxChars + " chars");
                }
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * Whether a POST may follow a redirect from {@code from} to {@code to}: same host only,
     * and never from https down to http. The POST body carries the API key, so following a
     * redirect anywhere else hands the key to that host (or to the network, in clear text).
     */
    static boolean redirectAllowed(URL from, URL to) {
        if (from == null || to == null) return false;
        if (!from.getHost().equalsIgnoreCase(to.getHost())) return false;
        boolean fromHttps = "https".equalsIgnoreCase(from.getProtocol());
        boolean toHttps = "https".equalsIgnoreCase(to.getProtocol());
        if (fromHttps && !toHttps) return false;
        return toHttps || "http".equalsIgnoreCase(to.getProtocol());
    }

    /** The configured station id for a log line: "(blank)" when unset, since "" vanishes. */
    static String describeStationId(String stationId) {
        if (stationId == null || stationId.trim().isEmpty()) return "(blank)";
        return stationId.trim();
    }

    private static void appendFailure(StringBuilder failureOut, String reason) {
        if (failureOut == null || reason == null || reason.isEmpty()) return;
        if (failureOut.length() > 0) failureOut.append("; ");
        failureOut.append(reason);
    }

    /** Longest failure description we keep. Server error bodies can be whole HTML pages. */
    private static final int MAX_FAILURE_LEN = 200;

    /**
     * Render an HTTP failure as one short line: the status, plus the server's error body
     * when it carried one. Pure (no network, no Android) so it is unit-testable.
     *
     * <p>The body is collapsed to a single line and truncated — Cloudlog-compatible
     * backends answer with a compact JSON envelope whose {@code messages} array holds the
     * real cause, but a misconfigured proxy can just as easily return an HTML error page,
     * and neither {@code debug.log} nor a toast wants all of that.
     */
    static String describeHttpFailure(int responseCode, String body) {
        StringBuilder sb = new StringBuilder("HTTP ").append(responseCode);
        if (body != null) {
            String flat = body.replaceAll("\\s+", " ").trim();
            if (!flat.isEmpty()) {
                if (flat.length() > MAX_FAILURE_LEN) {
                    flat = flat.substring(0, MAX_FAILURE_LEN) + "…";
                }
                sb.append(": ").append(flat);
            }
        }
        return sb.toString();
    }
    public static String sendPostFormRequest(String url, String formBody) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();
            applyTimeouts(conn);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(formBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK
                    || responseCode == HttpURLConnection.HTTP_CREATED) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(),
                        StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                return response.toString();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            if (reader != null) {
                reader.close();
            }
        }
        return null;
    }

    public static String sendGetRequest(String url) throws IOException {
        return sendGetRequest(url, null);
    }

    /**
     * As {@link #sendGetRequest(String)}, but reports the HTTP status in
     * {@code statusOut[0]} when supplied (0 if the connection never produced one), so the
     * caller can tell a 404 (try the other URL shape) from a 401/500 (stop).
     */
    static String sendGetRequest(String url, int[] statusOut) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        if (statusOut != null && statusOut.length > 0) statusOut[0] = 0;

        try {
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();
            applyTimeouts(conn);

            // Set request method to GET
            conn.setRequestMethod("GET");
            // Set request headers
            conn.setRequestProperty("Content-Type", "application/json");

            // Get server response
            int responseCode = conn.getResponseCode();
            if (statusOut != null && statusOut.length > 0) statusOut[0] = responseCode;
            if (responseCode == HttpURLConnection.HTTP_OK) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(),
                        StandardCharsets.UTF_8));
                return readBodyCapped(reader, MAX_RESPONSE_CHARS);
            }
            Log.d(TAG, "GET " + redactUrlApiKey(url) + " -> HTTP " + responseCode);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            if (reader != null) {
                reader.close();
            }
        }
        return null;
    }

    /**
     * Connect/read timeouts for every logbook request. {@code HttpURLConnection} defaults
     * to "forever", which on a LAN host that is off or firewalled left the Test Connection
     * spinner running until the OS gave up on the SYN retries (issue #756).
     */
    static final int HTTP_CONNECT_TIMEOUT_MS = 10_000;
    static final int HTTP_READ_TIMEOUT_MS = 20_000;

    private static void applyTimeouts(HttpURLConnection conn) {
        conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
    }

    // ---------------------------------------------------------------------------------
    // Cloudlog / Wavelog / Nextlog request plumbing (issue #756)
    // ---------------------------------------------------------------------------------

    /**
     * GET {@code path} under a Cloudlog-compatible base URL, trying each URL shape from
     * {@link CloudlogEndpoint#candidates} in turn. A 404 moves on to the next shape (an
     * install without URL rewriting only answers at {@code index.php/api/...}); any other
     * status, or a transport error, stops. Returns the body on HTTP 200, else null with the
     * reason appended to {@code failureOut}. Every attempt is written to debug.log with the
     * API key redacted.
     */
    static String cloudlogGet(String base, String path, StringBuilder failureOut) {
        return cloudlogRequest("GET", base, path, failureOut,
                (url, status, why) -> sendGetRequest(url, status));
    }

    /**
     * POST counterpart of {@link #cloudlogGet}: same candidate walk, same 404-only
     * fallback, same logging. Returns the body on HTTP 200/201, else null.
     */
    static String cloudlogPost(String base, String path, String json, StringBuilder failureOut) {
        return cloudlogRequest("POST", base, path, failureOut,
                (url, status, why) -> sendPostRequest(url, json, why, status));
    }

    /**
     * The Cloudlog/Wavelog calls the catch-up sync makes, behind a swappable seam so
     * {@link #syncAllQSOs} can be tested against a fake server.
     */
    interface CloudlogApi {
        String get(String base, String path);

        String post(String base, String path, String json, StringBuilder failureOut);
    }

    static CloudlogApi cloudlogApi = new CloudlogApi() {
        @Override
        public String get(String base, String path) {
            return cloudlogGet(base, path, null);
        }

        @Override
        public String post(String base, String path, String json, StringBuilder failureOut) {
            return cloudlogPost(base, path, json, failureOut);
        }
    };

    /**
     * One HTTP attempt against a single candidate URL. Returns the body on success or
     * {@code null} on a non-success status (reporting the status in {@code statusOut[0]}
     * and, optionally, a server-supplied reason in {@code why}); throws on transport
     * failure. Package-private so the candidate walk can be unit-tested with a fake.
     */
    interface CloudlogTransport {
        String call(String url, int[] statusOut, StringBuilder why) throws IOException;
    }

    /**
     * The candidate walk shared by {@link #cloudlogGet} and {@link #cloudlogPost}: try each
     * URL shape from {@link CloudlogEndpoint#candidates} in order; a 404 moves on to the
     * next shape, any other status or a transport exception stops; the first success pins
     * its shape via {@link CloudlogEndpoint#rememberWorking}. The reason for the last
     * failed attempt is appended to {@code failureOut}.
     */
    static String cloudlogRequest(String verb, String base, String path,
                                  StringBuilder failureOut, CloudlogTransport transport) {
        String lastFailure = null;
        for (String url : CloudlogEndpoint.candidates(base, path)) {
            String shown = redactUrlApiKey(url);
            int[] status = new int[1];
            StringBuilder why = new StringBuilder();
            try {
                String body = transport.call(url, status, why);
                if (body != null) {
                    CloudlogEndpoint.rememberWorking(base, url);
                    GeneralVariables.fileLog("Cloudlog: " + verb + " " + shown
                            + " -> HTTP " + status[0]);
                    return body;
                }
                lastFailure = why.length() > 0 ? why.toString()
                        : CloudlogEndpoint.describeFailure(shown, status[0], null);
                GeneralVariables.fileLog("Cloudlog: " + verb + " " + shown + " -> " + lastFailure);
                if (status[0] != HttpURLConnection.HTTP_NOT_FOUND) break;
            } catch (Exception e) {
                lastFailure = CloudlogEndpoint.describeFailure(shown, 0, e);
                GeneralVariables.fileLog("Cloudlog: " + verb + " " + shown + " -> " + lastFailure);
                break;
            }
        }
        appendFailure(failureOut, lastFailure);
        return null;
    }
}
