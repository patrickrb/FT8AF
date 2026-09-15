package com.k1af.ft8af.log;

import android.database.Cursor;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Client for the World Radio League logbook API (issue #800).
 *
 * <p>WRL is a fixed cloud service at {@code https://api.worldradioleague.com/v1/} — there is
 * no server address to configure. The operator generates one account-wide key under
 * Integrations → Developer API and it is sent as {@code Authorization: Bearer}. Contacts
 * are JSON with camelCased ADIF field names, one per {@code POST /v1/contacts}, and unknown
 * fields are <em>rejected</em> rather than ignored — so the payload is built field-by-field
 * here instead of reusing the ADIF string the Cloudlog/QRZ paths send.
 *
 * <p>Request building and response parsing are pure and package-private for unit tests; the
 * HTTP call goes through the swappable {@link #transport} (and rate-limit waits through
 * {@link #sleeper}) so the retry and stop-the-batch decisions are testable without a network.
 */
public final class WrlApi {
    private static final String TAG = "WrlApi";

    static final String BASE_URL = "https://api.worldradioleague.com/v1/";
    /** The ADIF PROGRAMID equivalent. WRL requires it on every contact. */
    static final String PROGRAM_ID = "FT8AF";

    static final int MAX_ACTIVITIES = 7;
    static final int MAX_NOTES_LEN = 2000;
    static final int MAX_RST_LEN = 10;
    /**
     * Longest {@code Retry-After} a sync pass sits out: the per-minute write window (60
     * writes/min). WRL computes the header from the window that actually blocked, so a
     * longer value means the daily quota is gone and waiting in-process is pointless.
     */
    static final long MAX_RETRY_AFTER_S = 65;
    static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final int MAX_FAILURE_LEN = 200;
    private static final int MAX_BODY_CHARS = 64_000;

    /** WRL's own Maidenhead pattern: a grid it would refuse must not sink the whole contact. */
    private static final Pattern GRID = Pattern.compile(
            "^[A-Ra-r]{2}(?:[0-9]{2}(?:[A-Xa-x]{2}(?:[0-9]{2})?)?)?$");
    private static final Set<String> ACTIVITY_TYPES =
            new HashSet<>(Arrays.asList("POTA", "SOTA", "WWFF", "IOTA"));

    private WrlApi() {
    }

    /** Outcome of one contact upload. */
    public enum UploadResult {
        OK,
        /** This contact was refused (a bad field, a server hiccup); the next one may still go. */
        FAILED,
        /**
         * Every following upload would fail the same way — bad or revoked key, no usable
         * logbook, quota exhausted, offline — so a batch should stop calling WRL.
         */
        FAILED_STOP_BATCH
    }

    static final class Response {
        final int status;
        final String body;
        /** Raw {@code Retry-After} header, or null. */
        final String retryAfter;

        Response(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    interface Transport {
        /** One HTTP exchange. {@code jsonBody} null means no request body. Throws on transport failure. */
        Response call(String method, String url, String apiKey, String jsonBody) throws IOException;
    }

    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    static Transport transport = WrlApi::httpCall;
    static Sleeper sleeper = Thread::sleep;

    /** The QSO fields WRL accepts, as plain strings, whichever source they came from. */
    static final class ContactFields {
        String call;
        String qsoDate;
        String timeOn;
        String freqMhz;
        String band;
        String mode;
        String rstSent;
        String rstRcvd;
        String gridsquare;
        String myGridsquare;
        String stationCallsign;
        String notes;
        String mySig;
        String mySigInfo;
        String sig;
        String sigInfo;
    }

    /** Fields for the immediate post-QSO upload. {@link QSLRecord} carries the frequency in Hz. */
    static ContactFields fromRecord(QSLRecord r) {
        ContactFields f = new ContactFields();
        f.call = r.getToCallsign();
        f.qsoDate = r.getQso_date();
        f.timeOn = r.getTime_on();
        f.freqMhz = r.getBandFreq() > 0
                ? BigDecimal.valueOf(r.getBandFreq()).movePointLeft(6).stripTrailingZeros().toPlainString()
                : null;
        f.band = r.getBandLength();
        f.mode = r.getMode();
        f.rstSent = AdifFormat.formatReport(r.getMode(), r.getSendReport());
        f.rstRcvd = AdifFormat.formatReport(r.getMode(), r.getReceivedReport());
        f.gridsquare = r.getToMaidenGrid();
        f.myGridsquare = r.getMyMaidenGrid();
        f.stationCallsign = r.getMyCallsign();
        f.notes = r.getComment();
        f.mySig = r.getMySig();
        f.mySigInfo = r.getMySigInfo();
        f.sig = r.getSig();
        f.sigInfo = r.getSigInfo();
        return f;
    }

    /** Fields for the catch-up sync, from a {@code select * from QSLTable} row (freq already in MHz). */
    static ContactFields fromCursor(Cursor c) {
        ContactFields f = new ContactFields();
        f.call = col(c, "call");
        f.qsoDate = col(c, "qso_date");
        f.timeOn = col(c, "time_on");
        f.freqMhz = col(c, "freq");
        f.band = col(c, "band");
        f.mode = col(c, "mode");
        f.rstSent = col(c, "rst_sent");
        f.rstRcvd = col(c, "rst_rcvd");
        f.gridsquare = col(c, "gridsquare");
        f.myGridsquare = col(c, "my_gridsquare");
        f.stationCallsign = col(c, "station_callsign");
        f.notes = col(c, "comment");
        f.mySig = col(c, "my_sig");
        f.mySigInfo = col(c, "my_sig_info");
        f.sig = col(c, "sig");
        f.sigInfo = col(c, "sig_info");
        return f;
    }

    private static String col(Cursor c, String name) {
        int idx = c.getColumnIndex(name);
        return idx < 0 ? null : c.getString(idx);
    }

    /**
     * Builds the {@code POST /v1/contacts} body, or returns null (with the reason appended to
     * {@code failureOut}) when a field WRL requires is missing or malformed — sending it would
     * only earn a 400/422. Optional fields WRL would reject (an off-pattern grid, an over-long
     * report) are dropped or clipped instead, so one bad detail never loses the whole contact.
     */
    static String buildContactJson(ContactFields f, String logbookId, StringBuilder failureOut) {
        String call = trimToNull(f.call);
        if (call == null) {
            appendFailure(failureOut, "missing call");
            return null;
        }
        String date = digitsOnly(f.qsoDate);
        if (date == null || date.length() != 8) {
            appendFailure(failureOut, "missing or malformed qso_date");
            return null;
        }
        String time = normalizeTimeOn(f.timeOn);
        if (time == null) {
            appendFailure(failureOut, "missing or malformed time_on");
            return null;
        }
        Double freq = parseFreqMhz(f.freqMhz);
        if (freq == null) {
            appendFailure(failureOut, "missing frequency");
            return null;
        }
        String band = trimToNull(f.band);
        if (band == null) {
            appendFailure(failureOut, "missing band");
            return null;
        }
        String mode = trimToNull(f.mode);
        if (mode == null) {
            appendFailure(failureOut, "missing mode");
            return null;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("programId", PROGRAM_ID);
            String logbook = trimToNull(logbookId);
            if (logbook != null) {
                o.put("logbookId", logbook);
            }
            o.put("call", call.toUpperCase(Locale.ROOT));
            // ADIF's own QSO_DATE/TIME_ON pair — WRL accepts it verbatim, so no local-time
            // conversion can creep in.
            JSONObject ts = new JSONObject();
            ts.put("qsoDate", date);
            ts.put("timeOn", time);
            o.put("timestamp", ts);
            o.put("freq", freq.doubleValue());
            o.put("band", band.toLowerCase(Locale.ROOT));
            // WRL stores one mode field and wants the submode itself ("FT4"), not MFSK+submode.
            o.put("mode", mode.toUpperCase(Locale.ROOT));
            putClipped(o, "rstSent", f.rstSent, MAX_RST_LEN);
            putClipped(o, "rstRcvd", f.rstRcvd, MAX_RST_LEN);
            putGrid(o, "gridsquare", f.gridsquare);
            putGrid(o, "myGridsquare", f.myGridsquare);
            String station = trimToNull(f.stationCallsign);
            if (station != null) {
                o.put("stationCallsign", station.toUpperCase(Locale.ROOT));
            }
            putClipped(o, "notes", f.notes, MAX_NOTES_LEN);
            JSONArray mine = activities(f.mySig, f.mySigInfo);
            if (mine != null) {
                o.put("myActivities", mine);
            }
            JSONArray theirs = activities(f.sig, f.sigInfo);
            if (theirs != null) {
                o.put("theirActivities", theirs);
            }
            return o.toString();
        } catch (JSONException e) {
            appendFailure(failureOut, "could not build contact: " + e.getMessage());
            return null;
        }
    }

    /** ADIF TIME_ON as WRL's HHMM/HHMMSS, restoring a dropped leading zero ("815" → "0815"). */
    static String normalizeTimeOn(String raw) {
        String d = digitsOnly(raw);
        if (d == null) {
            return null;
        }
        if (d.length() == 3 || d.length() == 5) {
            d = "0" + d;
        }
        return (d.length() == 4 || d.length() == 6) ? d : null;
    }

    /**
     * Frequency in MHz, or null when absent/unparseable/non-positive. A value of 100000 or
     * more is taken as Hz — no amateur band sits there in MHz, and some imported rows carry Hz.
     */
    static Double parseFreqMhz(String raw) {
        String s = trimToNull(raw);
        if (s == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(s);
            if (!(v > 0) || Double.isInfinite(v)) {
                return null;
            }
            if (v >= 100_000d) {
                v = v / 1_000_000d;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * ADIF SIG/SIG_INFO as a WRL activity array. WRL takes only the array form (not MY_SIG),
     * one entry per reference, so a two-fer stored as {@code "US-0001,US-0002"} becomes two
     * entries with the first marked primary. Returns null for no refs or a programme WRL
     * does not track.
     */
    static JSONArray activities(String sig, String sigInfo) throws JSONException {
        String type = trimToNull(sig);
        String refs = trimToNull(sigInfo);
        if (type == null || refs == null) {
            return null;
        }
        type = type.toUpperCase(Locale.ROOT);
        if (!ACTIVITY_TYPES.contains(type)) {
            return null;
        }
        Set<String> seen = new LinkedHashSet<>();
        JSONArray arr = new JSONArray();
        for (String part : refs.split("[,\\s]+")) {
            String ref = part.trim().toUpperCase(Locale.ROOT);
            if (ref.isEmpty() || !seen.add(ref)) {
                continue;
            }
            JSONObject a = new JSONObject();
            a.put("type", type);
            a.put("ref", ref);
            if (arr.length() == 0) {
                a.put("isPrimary", true);
            }
            arr.put(a);
            if (arr.length() == MAX_ACTIVITIES) {
                break;
            }
        }
        return arr.length() > 0 ? arr : null;
    }

    /**
     * One line for debug.log and the settings dialog: status, WRL's stable error code, its
     * message and the offending field — e.g. {@code HTTP 401 INVALID_KEY: The API key is not
     * valid.} Falls back to the flattened body for a non-WRL reply (a proxy error page).
     */
    static String describeFailure(int status, String body) {
        StringBuilder sb = new StringBuilder("HTTP ").append(status);
        JSONObject err = errorObject(body);
        if (err != null) {
            String code = optText(err, "code");
            String message = optText(err, "message");
            String field = optText(err, "field");
            if (code != null) {
                sb.append(' ').append(code);
            }
            if (message != null) {
                sb.append(": ").append(message);
            }
            if (field != null) {
                sb.append(" [").append(field).append(']');
            }
        } else if (body != null) {
            String flat = body.replaceAll("\\s+", " ").trim();
            if (!flat.isEmpty()) {
                sb.append(": ").append(flat);
            }
        }
        String out = sb.toString().replaceAll("\\s+", " ");
        return out.length() > MAX_FAILURE_LEN ? out.substring(0, MAX_FAILURE_LEN) + "…" : out;
    }

    /**
     * Whether a refused upload means every later one in the batch would be refused too, so
     * the sync should stop calling WRL: an auth/tier problem, a destination logbook that
     * cannot take contacts, the API being switched off, or a rate limit we will not wait out.
     * A validation error is specific to that contact and a 5xx may clear, so neither stops.
     */
    static boolean isBatchFatal(int status, String body) {
        if (status == 401 || status == 403 || status == 429) {
            return true;
        }
        JSONObject err = errorObject(body);
        String code = err == null ? null : optText(err, "code");
        String field = err == null ? null : optText(err, "field");
        if ("LOGBOOK_REQUIRED".equals(code) || "API_DISABLED".equals(code)
                || "RATE_LIMITED".equals(code)) {
            return true;
        }
        return (status == 404 || status == 409) && "logbookId".equals(field);
    }

    /** {@code Retry-After} in seconds, or -1 when absent or not the delta-seconds form. */
    static long parseRetryAfterSeconds(String header) {
        String s = trimToNull(header);
        if (s == null) {
            return -1;
        }
        try {
            long v = Long.parseLong(s);
            return v >= 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * POSTs one contact. A 429 whose {@code Retry-After} fits the per-minute window is waited
     * out and retried (a catch-up sync of a big backlog runs straight into 60 writes/min);
     * anything else is reported once into {@code failureOut}.
     */
    static UploadResult uploadContact(String apiKey, String json, StringBuilder failureOut) {
        for (int attempt = 0; ; attempt++) {
            Response resp;
            try {
                resp = transport.call("POST", BASE_URL + "contacts", apiKey, json);
            } catch (IOException e) {
                String why = describeTransportError(e);
                log("POST contacts -> " + why);
                appendFailure(failureOut, why);
                return UploadResult.FAILED_STOP_BATCH;
            }
            if (resp.status == HttpURLConnection.HTTP_OK || resp.status == HttpURLConnection.HTTP_CREATED) {
                log("POST contacts -> HTTP " + resp.status);
                return UploadResult.OK;
            }
            if (resp.status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                long wait = parseRetryAfterSeconds(resp.retryAfter);
                if (wait >= 0 && wait <= MAX_RETRY_AFTER_S) {
                    log("POST contacts -> HTTP 429, retrying in " + wait + " s");
                    try {
                        sleeper.sleep(Math.max(1L, wait) * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        appendFailure(failureOut, "interrupted while rate-limited");
                        return UploadResult.FAILED_STOP_BATCH;
                    }
                    continue;
                }
            }
            String why = describeFailure(resp.status, resp.body);
            log("POST contacts -> " + why);
            appendFailure(failureOut, why);
            return isBatchFatal(resp.status, resp.body)
                    ? UploadResult.FAILED_STOP_BATCH : UploadResult.FAILED;
        }
    }

    /** Uploads one contact with the configured key and logbook. */
    static UploadResult uploadFields(ContactFields f, StringBuilder failureOut) {
        String key = trimToNull(GeneralVariables.wrlApiKey);
        if (key == null) {
            appendFailure(failureOut, "no API key configured");
            return UploadResult.FAILED_STOP_BATCH;
        }
        StringBuilder why = new StringBuilder();
        String json = buildContactJson(f, GeneralVariables.wrlLogbookId, why);
        if (json == null) {
            log("not uploaded: " + why);
            appendFailure(failureOut, why.toString());
            return UploadResult.FAILED;
        }
        return uploadContact(key, json, failureOut);
    }

    /** Immediate post-QSO upload. Blocks — call from a background thread. */
    public static boolean uploadRecord(QSLRecord record) {
        return record != null && uploadFields(fromRecord(record), null) == UploadResult.OK;
    }

    /**
     * Test Connection: {@code GET /v1/me} confirms the key without writing anything, and its
     * {@code defaultLogbook} tells us up front whether a contact with no {@code logbookId}
     * would be refused with {@code LOGBOOK_REQUIRED}.
     */
    public static ThirdPartyService.ConnectionCheck checkConnection(String apiKey, String logbookId) {
        String key = trimToNull(apiKey);
        if (key == null) {
            return new ThirdPartyService.ConnectionCheck(false, "no API key configured");
        }
        try {
            Response r = transport.call("GET", BASE_URL + "me", key, null);
            if (r.status != HttpURLConnection.HTTP_OK) {
                String why = describeFailure(r.status, r.body);
                log("GET me -> " + why);
                return new ThirdPartyService.ConnectionCheck(false, why);
            }
            log("GET me -> HTTP 200");
            return interpretMe(r.body, logbookId);
        } catch (IOException e) {
            String why = describeTransportError(e);
            log("GET me -> " + why);
            return new ThirdPartyService.ConnectionCheck(false, why);
        }
    }

    /** Interprets a {@code GET /v1/me} body. Pure — unit-tested. */
    static ThirdPartyService.ConnectionCheck interpretMe(String body, String logbookId) {
        JSONObject data = dataObject(body);
        if (data == null) {
            return new ThirdPartyService.ConnectionCheck(false,
                    "unexpected reply (not the World Radio League API?)");
        }
        if (trimToNull(logbookId) == null) {
            JSONObject def = data.optJSONObject("defaultLogbook");
            String resolution = def == null ? null : optText(def, "resolution");
            if ("ambiguous".equals(resolution)) {
                return new ThirdPartyService.ConnectionCheck(false,
                        "several logbooks and no default: choose a logbook");
            }
            if ("none".equals(resolution)) {
                return new ThirdPartyService.ConnectionCheck(false,
                        "no unlocked logbook: create one in World Radio League");
            }
        }
        return new ThirdPartyService.ConnectionCheck(true, null);
    }

    /** The account's logbooks that can accept contacts. Empty (never null) on any failure. */
    public static List<ThirdPartyService.StationProfile> fetchLogbooks(String apiKey) {
        String key = trimToNull(apiKey);
        if (key == null) {
            return new ArrayList<>();
        }
        try {
            Response r = transport.call("GET", BASE_URL + "logbooks?limit=100", key, null);
            if (r.status == HttpURLConnection.HTTP_OK) {
                return parseLogbooks(r.body);
            }
            log("GET logbooks -> " + describeFailure(r.status, r.body));
        } catch (IOException e) {
            log("GET logbooks -> " + describeTransportError(e));
        }
        return new ArrayList<>();
    }

    /**
     * Parses a {@code GET /v1/logbooks} page into station profiles (id, name, default
     * callsign). Locked logbooks are skipped: WRL refuses new contacts into them. Pure.
     */
    static List<ThirdPartyService.StationProfile> parseLogbooks(String body) {
        List<ThirdPartyService.StationProfile> out = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(body).optJSONArray("data");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject lb = arr.optJSONObject(i);
                if (lb == null) {
                    continue;
                }
                String id = optText(lb, "id");
                if (id == null || lb.optBoolean("isLocked", false)) {
                    continue;
                }
                String name = optText(lb, "name");
                String call = optText(lb, "defaultCallSign");
                out.add(new ThirdPartyService.StationProfile(id,
                        name == null ? "" : name, call == null ? "" : call, ""));
            }
        } catch (JSONException e) {
            Log.d(TAG, "parseLogbooks error: " + e.getClass().getSimpleName());
        }
        return out;
    }

    /** Picker label for a logbook: its name plus default callsign — the UUID means nothing to a person. */
    public static String logbookLabel(ThirdPartyService.StationProfile p) {
        String name = trimToNull(p.profileName);
        String call = trimToNull(p.callsign);
        String base = name != null ? name : p.stationId;
        return call != null ? base + " (" + call + ")" : base;
    }

    // -------------------------------------------------------------------------------------

    private static Response httpCall(String method, String url, String apiKey, String jsonBody)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(ThirdPartyService.HTTP_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(ThirdPartyService.HTTP_READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", PROGRAM_ID);
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(status, readCapped(in), conn.getHeaderField("Retry-After"));
        } finally {
            conn.disconnect();
        }
    }

    private static String readCapped(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while (sb.length() < MAX_BODY_CHARS && (n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private static String describeTransportError(IOException e) {
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    private static JSONObject errorObject(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body).optJSONObject("error");
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject dataObject(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body).optJSONObject("data");
        } catch (JSONException e) {
            return null;
        }
    }

    /** A string member, or null when absent, JSON null, or blank (optString alone yields "null"). */
    private static String optText(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) {
            return null;
        }
        return trimToNull(o.optString(key, null));
    }

    private static void putClipped(JSONObject o, String key, String value, int maxLen)
            throws JSONException {
        String v = trimToNull(value);
        if (v != null) {
            o.put(key, v.length() > maxLen ? v.substring(0, maxLen) : v);
        }
    }

    private static void putGrid(JSONObject o, String key, String value) throws JSONException {
        String v = trimToNull(value);
        if (v != null && GRID.matcher(v).matches()) {
            o.put(key, v);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String digitsOnly(String s) {
        if (s == null) {
            return null;
        }
        String d = s.replaceAll("[^0-9]", "");
        return d.isEmpty() ? null : d;
    }

    private static void appendFailure(StringBuilder failureOut, String reason) {
        if (failureOut == null || reason == null || reason.isEmpty()) {
            return;
        }
        if (failureOut.length() > 0) {
            failureOut.append("; ");
        }
        failureOut.append(reason);
    }

    /** debug.log line, never carrying the key (it only ever travels in the Authorization header). */
    private static void log(String msg) {
        try {
            GeneralVariables.fileLog("WRL: " + msg);
        } catch (Exception ignored) {
            // Logging must never break an upload.
        }
    }
}
