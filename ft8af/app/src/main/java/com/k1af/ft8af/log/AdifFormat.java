package com.k1af.ft8af.log;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Small helpers for writing ADIF fields safely.
 *
 * <p>FT8 renders a callsign that was only carried as a 22-bit hash with angle brackets
 * (e.g. {@code <DK4RH>}, or {@code <...>} when unresolved). If such a value reaches the ADIF
 * {@code CALL} field verbatim, the brackets are part of the value and the declared length is
 * wrong — ARRL LoTW (and eQSL) reject the record. Strip the brackets on the way out so the
 * exported call is bare.
 */
public final class AdifFormat {

    private AdifFormat() {}

    /**
     * Strip the hash-marker angle brackets from a callsign so the ADIF value is a bare call
     * ({@code "<DK4RH>"} → {@code "DK4RH"}). Null or all-bracket input becomes {@code ""}.
     */
    public static String sanitizeCallsign(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("<", "").replace(">", "").trim();
    }

    /**
     * The ADIF {@code <call:len>VALUE } field for a (possibly bracketed) callsign, with the
     * length computed from the sanitized value.
     */
    public static String callField(String rawCall) {
        String c = sanitizeCallsign(rawCall);
        return String.format(Locale.US, "<call:%d>%s ", c.length(), c);
    }

    /**
     * The ADIF SUBMODE name for a stored mode string when ADIF models that mode as a submode of
     * MFSK, or {@code null} for modes that stand alone as a MODE (FT8, SSB, CW, ...).
     *
     * <p>FT8 is a first-class ADIF MODE, but FT4 and FT2 are not — ADIF defines them as submodes
     * of MFSK. A bare {@code <mode:3>FT2} is rejected as an invalid mode by pota.app (and other
     * ADIF consumers); such QSOs must be exported as {@code MODE=MFSK} with {@code SUBMODE=FT2}
     * (likewise FT4). Callers use a non-null result to emit that MODE/SUBMODE pair, and a null
     * result to emit the mode verbatim. Match is case-insensitive; the returned token is
     * upper-cased. FT2/FT4/MFSK are ASCII, so char length == UTF-8 byte length for the caller.
     */
    public static String mfskSubmode(String rawMode) {
        if (rawMode == null) {
            return null;
        }
        String upper = rawMode.trim().toUpperCase(Locale.US);
        if (upper.equals("FT4") || upper.equals("FT2")) {
            return upper;
        }
        return null;
    }

    /**
     * The effective stored mode for an imported ADIF record, resolving ADIF's
     * MODE/SUBMODE split back to the single mode string FT8AF stores. This is the
     * reader-side inverse of {@link #mfskSubmode}.
     *
     * <p>ADIF models FT4 and FT2 as SUBMODEs of the generic {@code MFSK} MODE, not as
     * standalone modes. FT8AF — and WSJT-X, JTDX and pota.app — therefore export those
     * QSOs as {@code MODE=MFSK} with {@code SUBMODE=FT4} (or {@code FT2}). Reading only
     * MODE on import stored {@code "MFSK"}, silently losing the FT4/FT2 distinction:
     * that corrupts mode-keyed dedup and band/mode filtering and breaks FT8AF's own
     * export→import round-trip. When MODE is the generic {@code MFSK} and a non-empty
     * SUBMODE is present, the SUBMODE is the more specific mode and is used (trimmed and
     * upper-cased, mirroring {@link #mfskSubmode}); otherwise MODE is returned verbatim,
     * so every other value (FT8, SSB, CW, a bare {@code <mode>FT4}, …) is unaffected.
     *
     * @param mode    the ADIF MODE field value (may be null)
     * @param submode the ADIF SUBMODE field value, or {@code null} when absent
     * @return the mode string to store
     */
    public static String resolveImportMode(String mode, String submode) {
        if (mode != null && "MFSK".equalsIgnoreCase(mode.trim()) && submode != null) {
            String s = submode.trim();
            if (!s.isEmpty()) {
                return s.toUpperCase(Locale.US);
            }
        }
        return mode;
    }

    /** "No report" sentinels stored in the SNR int fields; left unformatted so the logbook's
     * empty-report check still recognises them. */
    private static final int NO_REPORT = -100;
    private static final int NO_REPORT_ALT = -120;

    /**
     * Format an FT8 signal report (SNR in dB) the WSJT-X way: always a sign and at least two
     * digits, so {@code 5 → "+05"}, {@code -3 → "-03"}, {@code 20 → "+20"}, {@code 0 → "+00"}.
     *
     * <p>The "no report" sentinels {@code -100} and {@code -120} are returned unchanged so the
     * logbook's empty-report check still recognises them.
     */
    public static String formatReport(int report) {
        if (report == NO_REPORT || report == NO_REPORT_ALT) {
            return String.valueOf(report);
        }
        return String.format(Locale.US, "%+03d", report);
    }

    /**
     * Format a latitude as the ADIF {@code Location} type ({@code XDDD MM.MMM}), e.g.
     * {@code 40.858333 -> "N040 51.500"}, {@code -40.858333 -> "S040 51.500"}.
     */
    public static String formatLat(double lat) {
        return formatLocation(lat, 'N', 'S');
    }

    /**
     * Format a longitude as the ADIF {@code Location} type ({@code XDDD MM.MMM}), e.g.
     * {@code -73.925 -> "W073 55.500"}, {@code 73.925 -> "E073 55.500"}.
     */
    public static String formatLon(double lon) {
        return formatLocation(lon, 'E', 'W');
    }

    /**
     * The ADIF {@code Location} data type: a direction letter, 3-digit zero-padded degrees
     * (used for both latitude, 0-90, and longitude, 0-180, per the ADIF spec), a space, and
     * minutes to 3 decimal places, e.g. {@code "N040 51.500"}. {@code positive}/{@code negative}
     * are the direction letters for a non-negative/negative {@code value} (N/S for latitude,
     * E/W for longitude).
     */
    private static String formatLocation(double value, char positive, char negative) {
        char dir = value >= 0 ? positive : negative;
        double abs = Math.abs(value);
        int degrees = (int) abs;
        double minutes = (abs - degrees) * 60.0;
        // Rounding minutes to 3 decimals can carry into 60.000 (e.g. 40.999999 degrees);
        // normalize so it never renders as "MM.MMM" == "60.000".
        if (minutes >= 59.9995) {
            minutes = 0;
            degrees++;
        }
        return String.format(Locale.US, "%c%03d %06.3f", dir, degrees, minutes);
    }

    /**
     * Parse an ADIF {@code Location} value ({@code XDDD MM.MMM}) back to signed decimal
     * degrees, the inverse of {@link #formatLat}/{@link #formatLon}. Returns {@code null} for
     * {@code null}, empty, or malformed input rather than throwing, since this is used on
     * import of files this app did not necessarily write.
     */
    public static Double parseLocation(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() < 2) {
            return null;
        }
        char dir = Character.toUpperCase(v.charAt(0));
        int sign;
        switch (dir) {
            case 'N':
            case 'E':
                sign = 1;
                break;
            case 'S':
            case 'W':
                sign = -1;
                break;
            default:
                return null;
        }
        String rest = v.substring(1).trim();
        int space = rest.indexOf(' ');
        if (space < 0) {
            return null;
        }
        try {
            int degrees = Integer.parseInt(rest.substring(0, space));
            double minutes = Double.parseDouble(rest.substring(space + 1).trim());
            return sign * (degrees + minutes / 60.0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The number of UTF-8 <em>bytes</em> in {@code value} — the length an ADIF
     * {@code <field:len>value } declaration must carry, not the UTF-16
     * {@link String#length()} (char count). The two differ for any non-ASCII content
     * (an accented comment, a POTA park name): declaring the shorter char count makes
     * the receiving parser read fewer bytes than were written, truncating the field
     * and mis-aligning everything after it, so LoTW/QRZ/Cloudlog reject or mangle the
     * record. Shared source of truth for every ADIF writer ({@link AdifRecord} and the
     * {@link ThirdPartyService} upload paths). Null counts as 0.
     */
    public static int utf8Length(String value) {
        if (value == null) {
            return 0;
        }
        // Fast path: pure-ASCII values (callsigns, grids, most English comments — the
        // overwhelmingly common case, especially in the syncAllQSOs batch loop) have exactly
        // one UTF-8 byte per char, so the byte count equals String.length() with no
        // allocation. Only non-ASCII content falls through to encode the array.
        final int len = value.length();
        for (int i = 0; i < len; i++) {
            if (value.charAt(i) >= 0x80) {
                return value.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return len;
    }

    /**
     * The longest prefix of {@code raw} whose UTF-8 encoding fits in {@code byteLen}
     * bytes — the reader-side counterpart of {@link #utf8Length}. ADIF's
     * {@code <field:len>} declares a UTF-8 <em>byte</em> count, so an importer that
     * slices {@code len} UTF-16 chars over-reads past any non-ASCII value into the
     * whitespace/text that follows it (e.g. LEN=9 for "Café QSO" keeps a trailing
     * space). Never splits a code point: a LEN that ends mid-character keeps only the
     * complete characters that fit. A LEN larger than the whole string returns the
     * whole string (a truncated record keeps what is there).
     */
    public static String sliceByUtf8Length(String raw, int byteLen) {
        if (raw == null || byteLen <= 0) {
            return "";
        }
        int bytes = 0;
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            int cpBytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + cpBytes > byteLen) {
                break;
            }
            bytes += cpBytes;
            i += Character.charCount(cp);
        }
        return raw.substring(0, i);
    }
}
