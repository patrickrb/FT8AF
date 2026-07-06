package com.k1af.ft8af.log;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

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
import java.util.HashMap;
import java.util.List;

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
        if (address == null || address.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            return stations;
        }
        if (!address.endsWith("/")) {
            address += "/";
        }
        try {
            String url = address + "api/station_info/" + apiKey;
            String result = sendGetRequest(url);
            if (result == null || result.isEmpty()) return stations;
            JSONArray arr = new JSONArray(result);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                stations.add(new StationProfile(
                        obj.optString("station_id", ""),
                        obj.optString("station_profile_name", ""),
                        obj.optString("station_callsign", ""),
                        obj.optString("station_gridsquare", "")
                ));
            }
        } catch (Exception e) {
            Log.d(TAG, "FetchCloudlogStations error: " + e.getClass().getSimpleName());
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
        if (address == null || address.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        if (!address.endsWith("/")) {
            address += "/";
        }
        try {
            String body = buildCreateStationRequestJson(
                    gridsquare, callsign, city, dxcc, linkActiveLogbook);
            String url = address + "api/create_station/" + apiKey;
            String result = sendPostRequest(url, body);
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
            return "{}";
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

    private static String QSLRecordToADIF(QSLRecord qslRecord, ServiceType serv){
        StringBuilder logStr = new StringBuilder();
        logStr.append(AdifFormat.callField(qslRecord.getToCallsign()));

        if (qslRecord.getToMaidenGrid() != null) {
            logStr.append(String.format("<gridsquare:%d>%s "
                    , qslRecord.getToMaidenGrid().length()
                    , qslRecord.getToMaidenGrid()));
        }

        if (qslRecord.getMode() != null) {
            logStr.append(String.format("<mode:%d>%s "
                    , qslRecord.getMode().length()
                    , qslRecord.getMode()));
        }

        String rstSent = AdifFormat.formatReport(qslRecord.getSendReport());
        logStr.append(String.format("<rst_sent:%d>%s ", rstSent.length(), rstSent));

        String rstRcvd = AdifFormat.formatReport(qslRecord.getReceivedReport());
        logStr.append(String.format("<rst_rcvd:%d>%s ", rstRcvd.length(), rstRcvd));

        if (qslRecord.getQso_date() != null) {
            logStr.append(String.format("<qso_date:%d>%s "
                    , qslRecord.getQso_date().length()
                    , qslRecord.getQso_date()));
        }

        if (qslRecord.getTime_on() != null) {
            logStr.append(String.format("<time_on:%d>%s "
                    , qslRecord.getTime_on().length()
                    , qslRecord.getTime_on()));
        }
        if (qslRecord.getBandLength() != null) {
            logStr.append(String.format("<band:%d>%s "
                    , qslRecord.getBandLength().length()
                    , qslRecord.getBandLength()));
        }

        if (qslRecord.getQso_date_off() != null) {
            logStr.append(String.format("<qso_date_off:%d>%s "
                    , qslRecord.getQso_date_off().length()
                    , qslRecord.getQso_date_off()));
        }

        if (qslRecord.getTime_off() != null) {
            logStr.append(String.format("<time_off:%d>%s "
                    , qslRecord.getTime_off().length()
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
                    , freq.length()
                    , freq));
        }

        if (qslRecord.getMyCallsign() != null) {
            logStr.append(String.format("<station_callsign:%d>%s "
                    , qslRecord.getMyCallsign().length()
                    , qslRecord.getMyCallsign()));
        }

        if (qslRecord.getMyMaidenGrid() != null) {
            logStr.append(String.format("<my_gridsquare:%d>%s "
                    , qslRecord.getMyMaidenGrid().length()
                    , qslRecord.getMyMaidenGrid()));
        }

        String comment = qslRecord.getComment();

        //<comment:15>Distance: 99 km <eor>
        //When writing to the database, be sure to append " km"
        logStr.append(String.format("<comment:%d>%s <eor>\n"
                , comment.length()
                , comment));
        return logStr.toString();
    }
    public static boolean UploadToCloudLog(QSLRecord qslRecord){
        // Convert to ADIF format
        String logStr = QSLRecordToADIF(qslRecord,ServiceType.Cloudlog);
        return uploadAdifToCloudlog(logStr);
    }

    /**
     * Posts a single ADIF record (or any ADIF body) to Cloudlog/Wavelog/Nextlog.
     * Returns true on HTTP 2xx, false otherwise.
     */
    public static boolean uploadAdifToCloudlog(String adif) {
        String address = GeneralVariables.getCloudlogServerAddress();
        if (address == null || address.isEmpty()) return false;
        if (!address.endsWith("/")){
            address+="/";
        }
        JSONStringer js = new JSONStringer();
        try {
            String result = js.object().key("key").value(GeneralVariables.getCloudlogServerApiKey()).key("station_profile_id").value(GeneralVariables.getCloudlogStationID())
                    .key("type").value("adif").key("string").value(adif).endObject().toString();
            // Cloudlog's documented endpoint is /api/qso (no trailing slash). Wavelog and
            // Nextlog both 308-redirect when the trailing slash is present, which
            // HttpURLConnection won't follow on a POST.
            String clRes = sendPostRequest(address+"api/qso",result);
            Log.d(TAG, "Cloudlog upload " + (clRes != null ? "succeeded" : "failed"));
            return clRes != null;
        }catch (Exception k){
            Log.d(TAG, "Cloudlog upload error: " + k.getClass().getSimpleName());
            return false;
        }
    }
    public static boolean CheckCloudlogConnection(){
        String address = GeneralVariables.getCloudlogServerAddress();
        String apiKey = GeneralVariables.getCloudlogServerApiKey();
        // Check if the address ends with /
        if (!address.endsWith("/")){
            address+="/";
        }
        try{
            // The Cloudlog auth endpoint takes the key as a path segment, so the constructed
            // URL is unavoidably credential-bearing. Do not log it.
            String url = address + "api/auth/"+ apiKey;
            String result = sendGetRequest(url);
            if (result == null) {
                Log.d(TAG, "Cloudlog connection failed: no response");
                return false;
            }
            // Nextlog and Wavelog both implement /api/auth but return slightly different shapes
            // (XML declaration, extra whitespace, etc.). Match on the meaningful markers so all
            // three Cloudlog-compatible backends report Pass.
            String compact = result.replaceAll("\\s+", "");
            return compact.contains("<status>Valid</status>")
                    && compact.contains("<rights>rw</rights>");
        }catch (Exception e){
            Log.d(TAG, "Cloudlog auth error: " + e.getClass().getSimpleName());
            return false;
        }
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
        String apikey = GeneralVariables.getQrzApiKey();
        if (apikey == null || apikey.isEmpty()) return false;
        try {
            // POST keeps both the API key and the ADIF payload out of the URL.
            String body = "KEY=" + URLEncoder.encode(apikey, StandardCharsets.UTF_8.name())
                    + "&ACTION=INSERT"
                    + "&ADIF=" + URLEncoder.encode(adif, StandardCharsets.UTF_8.name());
            String result = sendPostFormRequest("https://logbook.qrz.com/api", body);
            Log.d(TAG, "QRZ upload " + (result != null ? "succeeded" : "failed"));
            if (result == null) return false;
            // QRZ encodes status as RESULT=OK|FAIL|REPLACE within an &-separated body
            String qrzResult = parseQrzResult(result);
            return "OK".equals(qrzResult) || "REPLACE".equals(qrzResult);
        }catch (Exception k){
            Log.d(TAG, "QRZ upload error: " + k.getClass().getSimpleName());
            return false;
        }
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
     * Progress callback used during a batch re-upload.
     */
    public interface SyncProgress {
        void onProgress(int done, int total, int cloudlogOk, int qrzOk);
    }

    public static class SyncResult {
        public final int total;
        public final int cloudlogOk;
        public final int qrzOk;
        public final boolean cloudlogAttempted;
        public final boolean qrzAttempted;

        SyncResult(int total, int cloudlogOk, int qrzOk,
                   boolean cloudlogAttempted, boolean qrzAttempted) {
            this.total = total;
            this.cloudlogOk = cloudlogOk;
            this.qrzOk = qrzOk;
            this.cloudlogAttempted = cloudlogAttempted;
            this.qrzAttempted = qrzAttempted;
        }
    }

    /**
     * The {@code WHERE} clause (with a leading space) selecting QSLTable rows that
     * still need an upload to at least one enabled service. Returns an empty string
     * when neither service is enabled (caller should not query in that case). Single
     * source of truth shared by {@link #syncAllQSOs} and {@link #countUnsyncedQSOs}.
     */
    private static String unsyncedFilter(boolean cloudlog, boolean qrz) {
        if (cloudlog && qrz) {
            return " where synced_cloudlog = 0 or synced_qrz = 0";
        } else if (cloudlog) {
            return " where synced_cloudlog = 0";
        } else if (qrz) {
            return " where synced_qrz = 0";
        }
        return "";
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
        if (db == null || (!cl && !qrz)) return 0;
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "select count(*) from QSLTable" + unsyncedFilter(cl, qrz), null);
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

    /**
     * Re-upload every QSO in QSLTable to whichever third-party services the user has
     * enabled. Services dedupe by callsign+date+time+mode so repeated calls are safe.
     *
     * Blocks the calling thread — invoke from a background thread/coroutine.
     */
    public static SyncResult syncAllQSOs(SQLiteDatabase db, SyncProgress progress) {
        boolean cl = GeneralVariables.enableCloudlog;
        boolean qrz = GeneralVariables.enableQRZ;
        int total = 0;
        int cloudlogOk = 0;
        int qrzOk = 0;
        if (db == null || (!cl && !qrz)) {
            return new SyncResult(0, 0, 0, cl, qrz);
        }
        Cursor cursor = null;
        try {
            // Skip rows already accepted by every enabled service. The user can still
            // tell something happened via the dialog's row counts, and a re-press isn't
            // wasted on already-confirmed records.
            cursor = db.rawQuery(
                    "select * from QSLTable" + unsyncedFilter(cl, qrz) + " order by id asc", null);
            total = cursor.getCount();
            if (progress != null) progress.onProgress(0, total, 0, 0);
            int idCol = cursor.getColumnIndex("id");
            int syncedClCol = cursor.getColumnIndex("synced_cloudlog");
            int syncedQrzCol = cursor.getColumnIndex("synced_qrz");
            int done = 0;
            while (cursor.moveToNext()) {
                long rowId = idCol >= 0 ? cursor.getLong(idCol) : -1;
                boolean alreadyCl = syncedClCol >= 0 && cursor.getInt(syncedClCol) == 1;
                boolean alreadyQrz = syncedQrzCol >= 0 && cursor.getInt(syncedQrzCol) == 1;
                if (cl && !alreadyCl) {
                    String adif = buildAdifFromCursor(cursor, ServiceType.Cloudlog);
                    if (uploadAdifToCloudlog(adif)) {
                        cloudlogOk++;
                        if (rowId >= 0) markRowSynced(db, rowId, "synced_cloudlog");
                    }
                }
                if (qrz && !alreadyQrz) {
                    String adif = buildAdifFromCursor(cursor, ServiceType.QRZ);
                    if (uploadAdifToQrz(adif)) {
                        qrzOk++;
                        if (rowId >= 0) markRowSynced(db, rowId, "synced_qrz");
                    }
                }
                done++;
                if (progress != null) progress.onProgress(done, total, cloudlogOk, qrzOk);
            }
        } catch (Exception e) {
            Log.e(TAG, "syncAllQSOs error: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return new SyncResult(total, cloudlogOk, qrzOk, cl, qrz);
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
        s.append(String.format("<comment:%d>%s <eor>\n", comment.length(), comment));
        return s.toString();
    }

    private static void appendAdif(StringBuilder sb, String tag, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append(String.format("<%s:%d>%s ", tag, value.length(), value));
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
        if (db == null || r == null) return;
        if (!cloudlogOk && !qrzOk) return;
        try {
            StringBuilder set = new StringBuilder();
            if (cloudlogOk) set.append("synced_cloudlog = 1");
            if (qrzOk) {
                if (set.length() > 0) set.append(", ");
                set.append("synced_qrz = 1");
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

    public static String sendPostRequest(String url, String json) throws IOException {
        // HttpURLConnection does not auto-follow 30x on a POST. Walk redirects manually
        // (capped) so deployments that rewrite trailing slashes, http→https, or move
        // the API path still work.
        String currentUrl = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            BufferedReader reader = null;
            try {
                URL urlObj = new URL(currentUrl);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");

                OutputStream os = conn.getOutputStream();
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                // Cloudlog uses HTTP_CREATED as the response for successful record creation
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
                if (responseCode == 301 || responseCode == 302 || responseCode == 307
                        || responseCode == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) {
                        Log.d(TAG, "POST " + currentUrl + " -> HTTP " + responseCode
                                + " (no Location header)");
                        return null;
                    }
                    // Resolve relative redirect against the previous URL.
                    URL resolved = new URL(urlObj, loc);
                    Log.d(TAG, "POST " + currentUrl + " -> HTTP " + responseCode
                            + " redirect to " + resolved);
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
                Log.d(TAG, "POST " + currentUrl + " -> HTTP " + responseCode
                        + (err.length() > 0 ? " body=" + err : ""));
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
        Log.d(TAG, "POST " + url + " exceeded redirect limit");
        return null;
    }
    public static String sendPostFormRequest(String url, String formBody) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();
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
        HttpURLConnection conn = null;
        BufferedReader reader = null;

        try {
            URL urlObj = new URL(url);
            conn = (HttpURLConnection) urlObj.openConnection();

            // Set request method to GET
            conn.setRequestMethod("GET");
            // Set request headers
            conn.setRequestProperty("Content-Type", "application/json");

            // Get server response
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
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
}
