package com.k1af.ft8af.callsign;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Extract the <b>CQ WPX prefix</b> from a callsign.
 *
 * <p>WPX (Worked All Prefixes) is one of the most-chased amateur-radio award
 * programs: each distinct callsign prefix (e.g. {@code W1}, {@code VE3},
 * {@code 9A1}, {@code DL0}) counts once, so operators hunt for "new prefixes"
 * the same way they hunt new DXCC entities or grids. This helper turns a
 * decoded callsign into its prefix so the decode list can flag unworked ones.
 *
 * <p>Rules implemented (per the CQ WPX definition), scoped to what actually
 * appears in FT8 traffic:
 * <ul>
 *   <li>Simple call — the letters/numerals up to and including the last numeral
 *       of the prefix. {@code W1AW}&rarr;{@code W1}, {@code VE3ABC}&rarr;{@code VE3},
 *       {@code 9A1AA}&rarr;{@code 9A1}, {@code 3DA0RS}&rarr;{@code 3DA0}.</li>
 *   <li>Call with no numeral (rare/historic, e.g. {@code RAEM}) — first two
 *       letters plus {@code 0} &rarr; {@code RA0}.</li>
 *   <li>Portable number {@code CALL/n} — the base call's own prefix with its
 *       numeral swapped for the new one. {@code W1AW/4}&rarr;{@code W4},
 *       {@code VE3ABC/7}&rarr;{@code VE7}, {@code 9A1AA/7}&rarr;{@code 9A7},
 *       {@code RAEM/4}&rarr;{@code RA4}. Deriving the base with the same
 *       simple-call rule (rather than lifting leading letters off the raw token)
 *       is what makes digit-leading and no-numeral calls behave; it also means a
 *       token that isn't a whole callsign — {@code W1/7}, {@code FN42/7} — stays
 *       {@code null} instead of inventing a prefix.</li>
 *   <li>Portable prefix {@code pfx/CALL} — the designator prefix, adding a
 *       {@code 0} when it carries no numeral. {@code DL/W1AW}&rarr;{@code DL0},
 *       {@code PJ4/K1ABC}&rarr;{@code PJ4}, {@code W1AW/KH6}&rarr;{@code KH6}.</li>
 *   <li>Operational suffixes {@code /P /M /MM /AM /QRP} are ignored (they don't
 *       change the prefix). {@code G4XYZ/P}&rarr;{@code G4}.</li>
 * </ul>
 *
 * <p>Anything that isn't a plausible callsign (grids, signal reports, hashed
 * {@code <...>} calls, empty/garbled tokens) returns {@code null} so callers
 * simply don't flag it — a conservative choice that never invents a prefix.
 * Pure and dependency-free so it can be unit-tested on the host and shared by
 * both the DB worked-prefix loader (DatabaseOpr.GetAllQSLCallsign) and the
 * decode-list "New Prefix" predicate (DecodeRow.isNewPrefixStation).
 */
public final class WpxPrefix {

    private WpxPrefix() {
    }

    /**
     * @param raw a callsign (case/whitespace-insensitive), possibly compound
     * @return the WPX prefix in upper case, or {@code null} if none can be
     * determined
     */
    public static String of(String raw) {
        if (raw == null) {
            return null;
        }
        String call = raw.trim().toUpperCase(Locale.ROOT);
        if (call.isEmpty()) {
            return null;
        }
        if (call.indexOf('/') < 0) {
            return simple(call);
        }
        return compound(call);
    }

    /** Prefix of a plain (non-slashed) callsign, or null if implausible. */
    private static String simple(String call) {
        if (!isAlnum(call)) {
            return null;
        }
        int lastDigit = -1;
        boolean hasLetter = false;
        for (int i = 0; i < call.length(); i++) {
            char c = call.charAt(i);
            if (c >= '0' && c <= '9') {
                lastDigit = i;
            } else {
                hasLetter = true;
            }
        }
        if (!hasLetter) {
            return null; // all-digit token (signal report, "73", …) — not a call
        }
        if (lastDigit < 0) {
            // No numeral at all (historic call like RAEM): first two letters + 0.
            return call.length() >= 2 ? call.substring(0, 2) + "0" : null;
        }
        // A full callsign carries a letter suffix after its prefix numeral.
        // Requiring one keeps bare prefixes / partial tokens ("W1") and grids
        // ("FN42") from being mistaken for a whole callsign.
        if (lastDigit == call.length() - 1) {
            return null;
        }
        String prefix = call.substring(0, lastDigit + 1);
        return containsLetter(prefix) ? prefix : null;
    }

    /** Prefix of a compound (slashed) callsign, or null if indeterminate. */
    private static String compound(String call) {
        ArrayList<String> parts = new ArrayList<>();
        for (String p : call.split("/")) {
            if (!p.isEmpty() && !isIgnoredSuffix(p)) {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return simple(parts.get(0));
        }

        // Portable number: CALL/n → the base call's own prefix with its numeral
        // swapped for the new one.
        String numeric = null;
        for (String p : parts) {
            if (isAllDigits(p) && p.length() <= 2) {
                numeric = p;
            }
        }
        if (numeric != null) {
            for (String p : parts) {
                if (!isAllDigits(p)) {
                    String base = simple(p);
                    return base == null ? null : withPortableNumber(base, numeric);
                }
            }
            return null;
        }

        // Portable prefix: pfx/CALL. The designator is conventionally the shorter
        // token (e.g. "DL" in DL/W1AW, "KH6" in W1AW/KH6).
        String a = parts.get(0);
        String b = parts.get(1);
        String designator = a.length() <= b.length() ? a : b;
        if (!isAlnum(designator) || !containsLetter(designator)) {
            return null;
        }
        int lastDigit = -1;
        for (int i = 0; i < designator.length(); i++) {
            char c = designator.charAt(i);
            if (c >= '0' && c <= '9') {
                lastDigit = i;
            }
        }
        if (lastDigit >= 0) {
            return designator.substring(0, lastDigit + 1);
        }
        // Designator without a numeral (bare prefix like "DL"): first two letters + 0.
        return designator.length() >= 2
                ? designator.substring(0, 2) + "0"
                : designator + "0";
    }

    private static boolean isIgnoredSuffix(String s) {
        switch (s) {
            case "P":
            case "M":
            case "MM":
            case "AM":
            case "QRP":
            case "QRPP":
                return true;
            default:
                return false;
        }
    }

    private static boolean isAlnum(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean containsLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                return true;
            }
        }
        return false;
    }

    /**
     * Swap the trailing numerals of an already-derived prefix for the portable
     * number, e.g. {@code ("9A1","7")}&rarr;{@code 9A7}, {@code ("RA0","4")}&rarr;
     * {@code RA4}, {@code ("W1","4")}&rarr;{@code W4}.
     *
     * <p>Working from {@link #simple(String)}'s output rather than the raw token's
     * leading letters is what makes the digit-leading and no-numeral cases come
     * out right: {@code 9A1AA} has no leading letters at all, and {@code RAEM}
     * is all letters, so lifting letters off the raw call yielded nothing or the
     * whole token. {@code simple} has already resolved both to a real prefix
     * ({@code 9A1}, {@code RA0}), and every prefix it returns ends in a numeral,
     * so replacing that numeral is well defined.
     *
     * @return the adjusted prefix, or {@code null} if nothing but digits remains
     */
    private static String withPortableNumber(String base, String number) {
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) >= '0' && base.charAt(end - 1) <= '9') {
            end--;
        }
        String letters = base.substring(0, end);
        return letters.isEmpty() ? null : letters + number;
    }
}
