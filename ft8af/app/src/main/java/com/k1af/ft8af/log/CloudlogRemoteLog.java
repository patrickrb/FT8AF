package com.k1af.ft8af.log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The QSOs already in the user's Wavelog logbook, read back through
 * {@code api/get_contacts_adif} so the catch-up sync can skip them instead of uploading
 * them again (issue #936).
 *
 * <p>Before this, every QSLTable row with {@code synced_cloudlog = 0} was uploaded:
 * rows logged before Wavelog was connected, rows imported from an ADIF file, and rows
 * Wavelog already had from WSJT-X or another logger. Wavelog's own server-side dupe
 * check only catches an exact match on the same minute and the same station location,
 * so anything that differed slightly was stored twice.
 *
 * <p>A local row counts as already present when a remote QSO has the same call, band
 * and mode, and its start time is within {@link #MATCH_WINDOW_MINUTES}. That window is
 * the one Wavelog itself uses to match QSOs (its {@code import_check}). It absorbs a
 * logger that records time_on at the end of the exchange rather than the start. Station
 * callsigns are compared only when both sides have one.
 *
 * <p>Pure (no network, no Android types besides org.json) so it is unit-testable.
 */
final class CloudlogRemoteLog {

    /** How far apart two start times may be and still be the same QSO, in minutes. */
    static final int MATCH_WINDOW_MINUTES = 15;

    /** Fields requested from Wavelog's JSON output. Older servers ignore this and send ADIF. */
    static final String[] FIELDS = {
            "CALL", "QSO_DATE", "TIME_ON", "BAND", "MODE", "SUBMODE", "STATION_CALLSIGN"
    };

    private static final class Entry {
        final long minute;
        final String stationCallsign;

        Entry(long minute, String stationCallsign) {
            this.minute = minute;
            this.stationCallsign = stationCallsign;
        }
    }

    private final Map<String, List<Entry>> byCallBandMode = new HashMap<>();
    private int size;

    /** Number of remote QSOs recorded. */
    int size() {
        return size;
    }

    /** Records one remote QSO. Rows without a call or a parseable date/time are ignored. */
    void add(String call, String qsoDate, String timeOn, String band, String mode,
             String submode, String stationCallsign) {
        long minute = epochMinute(qsoDate, timeOn);
        String key = key(call, band, AdifFormat.resolveImportMode(mode, submode));
        if (key == null || minute == Long.MIN_VALUE) return;
        List<Entry> list = byCallBandMode.get(key);
        if (list == null) {
            list = new ArrayList<>();
            byCallBandMode.put(key, list);
        }
        list.add(new Entry(minute, normalizeCall(stationCallsign)));
        size++;
    }

    /** Whether a local QSO (stored FT8AF mode, e.g. {@code FT4}) is already in the remote log. */
    boolean contains(String call, String qsoDate, String timeOn, String band, String mode,
                     String stationCallsign) {
        long minute = epochMinute(qsoDate, timeOn);
        String key = key(call, band, mode);
        if (key == null || minute == Long.MIN_VALUE) return false;
        List<Entry> list = byCallBandMode.get(key);
        if (list == null) return false;
        String station = normalizeCall(stationCallsign);
        for (Entry e : list) {
            if (Math.abs(e.minute - minute) > MATCH_WINDOW_MINUTES) continue;
            if (!station.isEmpty() && !e.stationCallsign.isEmpty()
                    && !station.equals(e.stationCallsign)) continue;
            return true;
        }
        return false;
    }

    /**
     * Adds the QSOs from one {@code get_contacts_adif} response and returns its
     * {@code lastfetchedid}, the cursor for the next page. Returns -1 when the body is not
     * a successful reply, or when the page is empty (no more QSOs) — Wavelog answers
     * {@code "adif": null} once the cursor is past the newest QSO.
     *
     * <p>Handles both shapes: {@code "qsos": [...]} when the server honoured
     * {@code output_format: "json"}, and the {@code "adif"} string older Wavelog sends.
     */
    long addPage(String body) {
        if (body == null) return -1;
        JSONObject obj;
        try {
            obj = new JSONObject(body);
        } catch (Exception e) {
            return -1;
        }
        if (!"successful".equalsIgnoreCase(obj.optString("status"))) return -1;
        boolean any;
        JSONArray qsos = obj.optJSONArray("qsos");
        if (qsos != null) {
            any = qsos.length() > 0;
            for (int i = 0; i < qsos.length(); i++) {
                JSONObject q = qsos.optJSONObject(i);
                if (q == null) continue;
                add(str(q, "CALL"), str(q, "QSO_DATE"), str(q, "TIME_ON"), str(q, "BAND"),
                        str(q, "MODE"), str(q, "SUBMODE"), str(q, "STATION_CALLSIGN"));
            }
        } else {
            String adif = str(obj, "adif");
            any = adif != null && !adif.isEmpty();
            addAdif(adif);
        }
        if (!any) return -1;
        return obj.optLong("lastfetchedid", -1);
    }

    /** Adds every record of an ADIF document (header optional). */
    void addAdif(String adif) {
        if (adif == null || adif.isEmpty()) return;
        String[] parts = adif.split("[<][Ee][Oo][Hh][>]");
        String body = parts[parts.length - 1];
        for (String raw : body.split("[<][Ee][Oo][Rr][>]")) {
            if (!raw.contains("<")) continue;
            Map<String, String> r;
            try {
                r = LogFileImport.parseRecord(raw);
            } catch (Exception e) {
                continue;
            }
            add(r.get("CALL"), r.get("QSO_DATE"), r.get("TIME_ON"), r.get("BAND"),
                    r.get("MODE"), r.get("SUBMODE"), r.get("STATION_CALLSIGN"));
        }
    }

    /** JSON request body for one page of {@code api/get_contacts_adif}. */
    static String pageRequestJson(String apiKey, String stationId, long fetchFromId, int limit) {
        try {
            JSONObject req = new JSONObject();
            req.put("key", apiKey);
            req.put("station_id", stationId);
            req.put("fetchfromid", fetchFromId);
            req.put("limit", limit);
            req.put("output_format", "json");
            JSONArray fields = new JSONArray();
            for (String f : FIELDS) fields.put(f);
            req.put("fields", fields);
            return req.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JSONObject o, String name) {
        return o.isNull(name) ? null : o.optString(name, null);
    }

    private static String key(String call, String band, String mode) {
        String c = normalizeCall(call);
        if (c.isEmpty()) return null;
        String b = band == null ? "" : band.trim().toLowerCase(Locale.US);
        String m = mode == null ? "" : mode.trim().toUpperCase(Locale.US);
        return c + "|" + b + "|" + m;
    }

    private static String normalizeCall(String call) {
        return AdifFormat.sanitizeCallsign(call).toUpperCase(Locale.US);
    }

    /**
     * Minutes since 1970-01-01 UTC for an ADIF {@code YYYYMMDD} date and {@code HHMM[SS]}
     * time, or {@link Long#MIN_VALUE} when either is malformed.
     */
    static long epochMinute(String qsoDate, String timeOn) {
        if (qsoDate == null || timeOn == null) return Long.MIN_VALUE;
        String d = qsoDate.trim();
        String t = timeOn.trim();
        if (d.length() != 8 || t.length() < 4 || !digits(d) || !digits(t)) return Long.MIN_VALUE;
        int y = Integer.parseInt(d.substring(0, 4));
        int mo = Integer.parseInt(d.substring(4, 6));
        int day = Integer.parseInt(d.substring(6, 8));
        int h = Integer.parseInt(t.substring(0, 2));
        int mi = Integer.parseInt(t.substring(2, 4));
        if (mo < 1 || mo > 12 || day < 1 || day > 31 || h > 23 || mi > 59) return Long.MIN_VALUE;
        return daysFromCivil(y, mo, day) * 1440L + h * 60L + mi;
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
