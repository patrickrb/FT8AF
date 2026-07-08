package com.k1af.ft8af.log;

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
}
