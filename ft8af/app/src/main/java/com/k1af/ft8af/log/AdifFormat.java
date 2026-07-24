package com.k1af.ft8af.log;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Format a coordinate in ADIF's Location datatype: {@code XDDD MM.MMM}, i.e. a
     * hemisphere letter, three zero-padded degrees, a space, then decimal minutes —
     * {@code N039 44.352}, {@code W104 59.418}.
     *
     * <p>Degrees are always three digits even for a latitude that can never exceed 90,
     * because the spec fixes the width and importers parse by position. The awkward
     * carry case is real and handled below: 59.9996 minutes rounds to 60.000, which is
     * not a valid minute value, so the degree is incremented and the minutes wrap to
     * zero rather than emitting {@code N039 60.000}.
     *
     * @param value    degrees, positive north/east
     * @param latitude true for a latitude (N/S), false for a longitude (E/W)
     * @return the formatted value, or null when {@code value} is absent or not finite
     */
    public static String location(Double value, boolean latitude) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        double limit = latitude ? 90.0 : 180.0;
        if (Math.abs(value) > limit) {
            return null;
        }
        char hemisphere = value < 0 ? (latitude ? 'S' : 'W') : (latitude ? 'N' : 'E');
        double magnitude = Math.abs(value);
        int degrees = (int) magnitude;
        double minutes = (magnitude - degrees) * 60.0;
        // Round to the emitted precision *before* formatting so the carry is visible.
        double rounded = Math.round(minutes * 1000.0) / 1000.0;
        if (rounded >= 60.0) {
            rounded -= 60.0;
            degrees += 1;
        }
        return String.format(Locale.US, "%c%03d %06.3f", hemisphere, degrees, rounded);
    }

    /**
     * Parse ADIF's Location datatype ({@code N039 44.352}) back to decimal degrees.
     *
     * <p>Lenient about the separator and about a missing leading zero, because exporters
     * vary; strict about the hemisphere letter, which is the only thing carrying the
     * sign. Returns null rather than guessing when the shape doesn't match — a
     * mis-parsed coordinate puts a QSO in the wrong hemisphere, which is worse than
     * having none.
     */
    public static Double parseLocation(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.US);
        if (s.length() < 2) {
            return null;
        }
        char hemisphere = s.charAt(0);
        if (hemisphere != 'N' && hemisphere != 'S' && hemisphere != 'E' && hemisphere != 'W') {
            return null;
        }
        String[] parts = s.substring(1).trim().split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        try {
            int degrees = Integer.parseInt(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            if (degrees < 0 || minutes < 0 || minutes >= 60.0) {
                return null;
            }
            double value = degrees + minutes / 60.0;
            return (hemisphere == 'S' || hemisphere == 'W') ? -value : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Format a coordinate as plain decimal degrees for an {@code APP_}-prefixed field.
     *
     * <p>Six decimal places is about 11 cm — far beyond any consumer GPS, and enough
     * that the value round-trips without the loss the degrees-and-minutes form imposes.
     * Trailing zeros are kept so the field width is stable across records.
     */
    public static String decimalDegrees(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return String.format(Locale.US, "%.6f", value);
    }

    /** The ADIF header terminator {@code <eoh>}, matched case-insensitively. */
    private static final Pattern EOH = Pattern.compile("<[Ee][Oo][Hh]>");

    /**
     * The data-record body of a full {@code .adi} file — everything after the optional
     * header — ready to be split on {@code <eor>}.
     *
     * <p>Per the ADI file format a Header is <em>optional</em>: it is present only when the
     * file does <b>not</b> begin with {@code '<'}. When present it is arbitrary text
     * terminated by an {@code <eoh>} tag, and the data records follow that tag. A file that
     * begins with {@code '<'} is headerless and consists entirely of data records.
     *
     * <p>Both importers previously split on {@code <eoh>} and returned {@code ""} whenever no
     * marker was found. That silently dropped every QSO of a valid <em>headerless</em> ADIF
     * log on import (0 records, and 0 errors surfaced to the user) — a common export shape,
     * and exactly what a WSJT-X {@code wsjtx_log.adi} whose header was written with the
     * v2.2.2 {@code <eh>} bug looks like once opened. This helper returns:
     * <ul>
     *   <li>the whole content when the file is headerless (first non-blank char is {@code '<'});</li>
     *   <li>the text after the first {@code <eoh>} when a header is present and terminated;</li>
     *   <li>{@code ""} only when a header is present but unterminated (no {@code <eoh>}) or the
     *       input is null — there is genuinely no parseable record body.</li>
     * </ul>
     *
     * @param fileContext the full {@code .adi} file text (null → {@code ""})
     * @return the record-body text to split on {@code <eor>}
     */
    public static String stripHeader(String fileContext) {
        if (fileContext == null) {
            return "";
        }
        if (beginsWithField(fileContext)) {
            // Headerless file: the whole content is data records.
            return fileContext;
        }
        // Header present (does not begin with '<'); records start after its <eoh> terminator.
        Matcher m = EOH.matcher(fileContext);
        return m.find() ? fileContext.substring(m.end()) : "";
    }

    /**
     * True when the first non-whitespace character of {@code content} (after an optional
     * leading UTF-8 BOM) is {@code '<'} — i.e. the file opens with an ADIF field and so,
     * per the spec, carries no header.
     */
    private static boolean beginsWithField(String content) {
        int i = 0;
        int n = content.length();
        if (n > 0 && content.charAt(0) == '\uFEFF') { // skip a UTF-8 BOM if present
            i = 1;
        }
        while (i < n && Character.isWhitespace(content.charAt(i))) {
            i++;
        }
        return i < n && content.charAt(i) == '<';
    }
}
