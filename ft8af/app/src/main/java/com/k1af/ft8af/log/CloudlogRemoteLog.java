package com.k1af.ft8af.log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * logger that records time_on at the end of the exchange rather than the start. Each
 * remote QSO accounts for at most one local row ({@link #matchPresent}). Station
 * callsigns are compared when both sides have one; a local row without one is compared
 * against the station location uploads go to, when that is known.
 *
 * <p>When built with {@code keepRecords}, each remote QSO also keeps its full field map, so
 * the QSOs with no local row can be imported (issue #965, {@link #pair}).
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

    /**
     * Fields requested when the QSOs missing locally are imported (issue #965): enough to
     * build a full QSLTable row through the ADIF import's {@link QSLRecord} constructor.
     */
    static final String[] IMPORT_FIELDS = {
            "CALL", "QSO_DATE", "TIME_ON", "QSO_DATE_OFF", "TIME_OFF", "BAND", "FREQ",
            "MODE", "SUBMODE", "RST_SENT", "RST_RCVD", "GRIDSQUARE", "STATION_CALLSIGN",
            "MY_GRIDSQUARE", "NAME", "COMMENT", "SIG", "SIG_INFO", "MY_SIG", "MY_SIG_INFO"
    };

    /**
     * Most remote QSOs whose fields are held for the import (issue #965). Each kept QSO
     * costs a couple of KB of heap; past this the logbook is too big to import in one pass
     * without risking an OutOfMemoryError, so the kept fields are dropped and the import is
     * skipped ({@link #tooLargeToImport}) while the duplicate check still works.
     */
    static final int MAX_KEPT_RECORDS = 20_000;
    /** Longest kept value of a downloaded field, so one huge field can't bloat a DB row. */
    static final int MAX_FIELD_CHARS = 1024;
    /** As {@link #MAX_FIELD_CHARS}, for the free-text COMMENT. */
    static final int MAX_COMMENT_CHARS = 4096;

    private static final class Entry {
        final long minute;
        final String stationCallsign;
        /** Import fields of the remote QSO, upper-case keys; null unless kept. */
        Map<String, String> record;

        Entry(long minute, String stationCallsign, Map<String, String> record) {
            this.minute = minute;
            this.stationCallsign = stationCallsign;
            this.record = record;
        }
    }

    private final Map<String, List<Entry>> byCallBandMode = new HashMap<>();
    /** Every entry, in download order, so unmatched QSOs are imported oldest first. */
    private final List<Entry> entries = new ArrayList<>();
    private final boolean keepRecords;
    private final int maxKeptRecords;
    private int keptRecords;
    private boolean tooLargeToImport;
    private int size;
    private String defaultStationCallsign = "";

    /** A log for the duplicate check only; remote QSOs are not kept field by field. */
    CloudlogRemoteLog() {
        this(false);
    }

    /** @param keepRecords keep each remote QSO's fields, for {@link Pairing#unmatchedRemote}. */
    CloudlogRemoteLog(boolean keepRecords) {
        this(keepRecords, MAX_KEPT_RECORDS);
    }

    /** As {@link #CloudlogRemoteLog(boolean)} with a different {@link #MAX_KEPT_RECORDS}. */
    CloudlogRemoteLog(boolean keepRecords, int maxKeptRecords) {
        this.keepRecords = keepRecords;
        this.maxKeptRecords = maxKeptRecords;
    }

    /** Number of remote QSOs recorded. */
    int size() {
        return size;
    }

    /**
     * True when more QSOs came down than {@link #MAX_KEPT_RECORDS}: no fields are kept, so
     * {@link Pairing#unmatchedRemote} is empty, but the duplicate check still sees them all.
     */
    boolean tooLargeToImport() {
        return tooLargeToImport;
    }

    /** Records one remote QSO. Rows without a call or a parseable date/time are ignored. */
    void add(String call, String qsoDate, String timeOn, String band, String mode,
             String submode, String stationCallsign) {
        add(call, qsoDate, timeOn, band, mode, submode, stationCallsign, null);
    }

    /** Records one remote QSO from its field map (upper-case ADIF names). */
    void addRecord(Map<String, String> r) {
        add(r.get("CALL"), r.get("QSO_DATE"), r.get("TIME_ON"), r.get("BAND"),
                r.get("MODE"), r.get("SUBMODE"), r.get("STATION_CALLSIGN"), r);
    }

    private void add(String call, String qsoDate, String timeOn, String band, String mode,
                     String submode, String stationCallsign, Map<String, String> record) {
        long minute = epochMinute(qsoDate, timeOn);
        String key = key(call, band, AdifFormat.resolveImportMode(mode, submode));
        if (key == null || minute == Long.MIN_VALUE) return;
        List<Entry> list = byCallBandMode.get(key);
        if (list == null) {
            list = new ArrayList<>();
            byCallBandMode.put(key, list);
        }
        Map<String, String> kept = null;
        if (keepRecords && record != null && !tooLargeToImport) {
            if (keptRecords < maxKeptRecords) {
                kept = importFieldsOf(record);
                keptRecords++;
            } else {
                // Too many to import in one pass: free what is held rather than run the
                // heap out (that crash would repeat on every launch via auto-sync).
                tooLargeToImport = true;
                for (Entry held : entries) held.record = null;
                keptRecords = 0;
            }
        }
        Entry e = new Entry(minute, normalizeCall(stationCallsign), kept);
        list.add(e);
        entries.add(e);
        size++;
    }

    /**
     * The callsign of the station location uploads go to, used for a local row that has no
     * station callsign of its own (Wavelog then logs it under that location's callsign).
     * Empty when unknown, in which case such a row matches any station.
     */
    void setDefaultStationCallsign(String stationCallsign) {
        defaultStationCallsign = normalizeCall(stationCallsign);
    }

    /** One local QSO to look up; {@code mode} is the stored FT8AF mode, e.g. {@code FT4}. */
    static final class Local {
        final String call;
        final String qsoDate;
        final String timeOn;
        final String band;
        final String mode;
        final String stationCallsign;

        Local(String call, String qsoDate, String timeOn, String band, String mode,
              String stationCallsign) {
            this.call = call;
            this.qsoDate = qsoDate;
            this.timeOn = timeOn;
            this.band = band;
            this.mode = mode;
            this.stationCallsign = stationCallsign;
        }
    }

    /** Whether a single local QSO is already in the remote log. */
    boolean contains(String call, String qsoDate, String timeOn, String band, String mode,
                     String stationCallsign) {
        List<Local> one = new ArrayList<>();
        one.add(new Local(call, qsoDate, timeOn, band, mode, stationCallsign));
        return !matchPresent(one).isEmpty();
    }

    /**
     * Indexes of the local QSOs that are already in the remote log.
     *
     * <p>Each remote QSO accounts for at most one local QSO, so two local QSOs with the
     * same call, band and mode inside the window (a QSO worked twice, a busted one redone)
     * are not both skipped because Wavelog has one of them. Pairs are taken closest start
     * time first, so the remote QSO goes to the local row it most likely is. Does not change
     * the remote log; each call starts from the full set.
     */
    Set<Integer> matchPresent(List<Local> locals) {
        return pair(locals).presentLocals;
    }

    /** The result of pairing local QSOs with remote ones, one to one ({@link #pair}). */
    static final class Pairing {
        /** Indexes of the local QSOs that have a remote QSO. */
        final Set<Integer> presentLocals;
        /**
         * Field maps of the remote QSOs no local QSO was paired with, in download order.
         * Empty unless the log was built with {@code keepRecords}.
         */
        final List<Map<String, String>> unmatchedRemote;

        Pairing(Set<Integer> presentLocals, List<Map<String, String>> unmatchedRemote) {
            this.presentLocals = presentLocals;
            this.unmatchedRemote = unmatchedRemote;
        }
    }

    /**
     * Pairs local QSOs with remote ones the way {@link #matchPresent} does, and also says
     * which remote QSOs are left over. Pass every local QSO, synced or not, so a remote
     * QSO counts as missing locally only when no local row accounts for it (issue #965).
     */
    Pairing pair(List<Local> locals) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < locals.size(); i++) {
            Local l = locals.get(i);
            long minute = epochMinute(l.qsoDate, l.timeOn);
            String key = key(l.call, l.band, l.mode);
            if (key == null || minute == Long.MIN_VALUE) continue;
            List<Entry> list = byCallBandMode.get(key);
            if (list == null) continue;
            String station = normalizeCall(l.stationCallsign);
            if (station.isEmpty()) station = defaultStationCallsign;
            for (Entry e : list) {
                long dt = Math.abs(e.minute - minute);
                if (dt > MATCH_WINDOW_MINUTES) continue;
                if (!station.isEmpty() && !e.stationCallsign.isEmpty()
                        && !station.equals(e.stationCallsign)) continue;
                candidates.add(new Candidate(dt, i, e));
            }
        }
        Collections.sort(candidates, (a, b) -> a.dt != b.dt
                ? Long.compare(a.dt, b.dt) : Integer.compare(a.local, b.local));
        Set<Integer> present = new HashSet<>();
        Set<Entry> used = new HashSet<>();
        for (Candidate c : candidates) {
            if (present.contains(c.local) || used.contains(c.entry)) continue;
            present.add(c.local);
            used.add(c.entry);
        }
        List<Map<String, String>> unmatched = new ArrayList<>();
        for (Entry e : entries) {
            if (e.record != null && !used.contains(e)) unmatched.add(e.record);
        }
        return new Pairing(present, unmatched);
    }

    private static final class Candidate {
        final long dt;
        final int local;
        final Entry entry;

        Candidate(long dt, int local, Entry entry) {
            this.dt = dt;
            this.local = local;
            this.entry = entry;
        }
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
        } catch (Exception | StackOverflowError e) {
            // org.json parses recursively: a short body of nested brackets from a hostile
            // server (or a man-in-the-middle on http://) overflows the stack.
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
                addRecord(jsonRecord(q));
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
            addRecord(r);
        }
    }

    /**
     * The {@link #IMPORT_FIELDS} of a remote QSO. Older Wavelog ignores the requested field
     * list and sends every ADIF field; keeping only these holds memory down on a big logbook
     * and imports the same fields from either reply shape. Values are cut to
     * {@link #MAX_FIELD_CHARS} ({@link #MAX_COMMENT_CHARS} for COMMENT): a multi-MB field
     * would make the inserted row too big for a CursorWindow and break every later read of
     * the logbook.
     */
    static Map<String, String> importFieldsOf(Map<String, String> record) {
        Map<String, String> kept = new HashMap<>();
        for (String f : IMPORT_FIELDS) {
            String v = record.get(f);
            if (v == null) continue;
            int max = "COMMENT".equals(f) ? MAX_COMMENT_CHARS : MAX_FIELD_CHARS;
            kept.put(f, v.length() > max ? v.substring(0, max) : v);
        }
        return kept;
    }

    /** A JSON QSO as a field map: upper-case names, string values, nulls and blanks dropped. */
    private static Map<String, String> jsonRecord(JSONObject q) {
        Map<String, String> r = new HashMap<>();
        java.util.Iterator<String> names = q.keys();
        while (names.hasNext()) {
            String name = names.next();
            String value = str(q, name);
            if (value != null && !value.isEmpty()) r.put(name.toUpperCase(Locale.US), value);
        }
        return r;
    }

    /** JSON request body for one page of {@code api/get_contacts_adif}. */
    static String pageRequestJson(String apiKey, String stationId, long fetchFromId, int limit) {
        return pageRequestJson(apiKey, stationId, fetchFromId, limit, FIELDS);
    }

    /** Same, asking for {@code fields} (e.g. {@link #IMPORT_FIELDS}). */
    static String pageRequestJson(String apiKey, String stationId, long fetchFromId, int limit,
                                  String[] fields) {
        try {
            JSONObject req = new JSONObject();
            req.put("key", apiKey);
            req.put("station_id", stationId);
            req.put("fetchfromid", fetchFromId);
            req.put("limit", limit);
            req.put("output_format", "json");
            JSONArray names = new JSONArray();
            for (String f : fields) names.put(f);
            req.put("fields", names);
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
