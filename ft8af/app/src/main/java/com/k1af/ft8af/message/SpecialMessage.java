package com.k1af.ft8af.message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, Android-free templating + recognition logic for WSJT-X-style
 * <em>special messages</em> (Message Creator, QSY Monitor, message popups).
 *
 * <p>WSJT-X lets an operator invite a QSO partner to move to another band /
 * frequency (a <b>QSY request</b>) or send a short <b>canned</b> message, all
 * riding inside an ordinary FT8/FT4 <em>free-text</em> frame ({@code i3=0,
 * n3=0}, 13 characters max — see {@code GenerateFT8.packFreeTextTo77}). There
 * is no new on-air protocol; the whole feature is UI + string templating +
 * decode-side pattern matching. This class owns exactly that string logic so it
 * can be unit-tested off-device; the Compose/dialog UI, the transmit queue hook
 * and the notification/toast layer are thin wrappers that call in here (mirrors
 * {@link com.k1af.ft8af.alert.AlertDecisions}).
 *
 * <h3>On-air formats</h3>
 * Every special message built by the creator leads with the addressee callsign
 * so the receiver's monitor/popup can tell it is aimed at them:
 * <ul>
 *   <li><b>QSY request</b> — {@code "<CALL>.<BAND> <FFF>"}, e.g.
 *       {@code "K1ABC.20 074"} meaning "K1ABC, QSY to the 20 m band,
 *       14.074 MHz". {@code <BAND>} is the metre designation without the
 *       {@code m}; {@code <FFF>} is the kHz offset (000-999) above the band's
 *       base frequency. This mirrors the compact structure WSJT-X uses in its
 *       QSY shorthand (band code + 3-digit kHz) while staying within the 13-char
 *       free-text budget.</li>
 *   <li><b>Canned message</b> — {@code "<CALL> <TEXT>"}, e.g.
 *       {@code "K1ABC 73 GL"}, where {@code <TEXT>} is one of
 *       {@link #CANNED_MESSAGES}.</li>
 * </ul>
 * Because the leading {@code <CALL>} field is not a hashed / structured
 * callsign field but plain text, these frames still decode as ordinary free
 * text on any WSJT-X-family receiver; only the recognition here is FT8AF-added.
 *
 * <p>QSY frequency options are gated on the operator's {@link IaruRegion}: the
 * three IARU regions have different band edges (e.g. 40 m stops at 7.200 MHz in
 * Region 1 but runs to 7.300 in Regions 2/3), so a QSY the sender could make may
 * be illegal for the recipient. {@link #buildQsyRequest} rejects a band /
 * frequency not allocated in the given region.
 */
public final class SpecialMessage {

    private SpecialMessage() {}

    /** Maximum length of an FT8/FT4 free-text frame, in characters. */
    public static final int FREE_TEXT_LIMIT = 13;

    /** The three IARU regions; band plans differ between them. */
    public enum IaruRegion {
        /** Europe, Africa, Middle East, northern Asia. */
        REGION_1(1),
        /** The Americas. */
        REGION_2(2),
        /** Asia-Pacific. */
        REGION_3(3);

        public final int number;

        IaruRegion(int number) {
            this.number = number;
        }
    }

    /**
     * Map a persisted region number (1/2/3) to an {@link IaruRegion}, defaulting
     * to {@link IaruRegion#REGION_2} (the Americas) for any out-of-range value —
     * matching {@code GeneralVariables.iaruRegion}'s default.
     */
    public static IaruRegion regionFromNumber(int number) {
        switch (number) {
            case 1:
                return IaruRegion.REGION_1;
            case 3:
                return IaruRegion.REGION_3;
            case 2:
            default:
                return IaruRegion.REGION_2;
        }
    }

    /**
     * Curated, mobile-friendly set of canned "General"-tab messages. Each is
     * &le; a length that still leaves room for the addressee callsign inside the
     * 13-char frame. Order is display order for the picker.
     */
    public static final List<String> CANNED_MESSAGES = Collections.unmodifiableList(Arrays.asList(
            "73",
            "RR73",
            "73 GL",
            "TNX 73",
            "GL",
            "QSL?",
            "QSL",
            "AGN?",
            "PSE AGN",
            "SRI QRM",
            "SK",
            "5NN"
    ));

    /**
     * A band that a QSY request may target. {@link #baseKhz} is the frequency the
     * 3-digit offset is added to (the band's low edge, rounded to the MHz the
     * digital sub-bands live in); {@link #maxOffsetForRegion} bounds the offset by
     * the region's upper band edge.
     */
    public static final class QsyBand {
        /** On-air code, e.g. {@code "20"} — the metre band without the {@code m}. */
        public final String code;
        /** Human label, e.g. {@code "20m"}. */
        public final String meters;
        /** Base frequency in kHz that the offset is added to, e.g. {@code 14000}. */
        public final long baseKhz;
        /** Per-region upper offset (kHz above {@link #baseKhz}); absent => not allocated. */
        private final Map<IaruRegion, Integer> maxOffset;

        QsyBand(String code, long baseKhz, Map<IaruRegion, Integer> maxOffset) {
            this.code = code;
            this.meters = code + "m";
            this.baseKhz = baseKhz;
            this.maxOffset = maxOffset;
        }

        /** True when this band is allocated in {@code region}. */
        public boolean availableIn(IaruRegion region) {
            return maxOffset.containsKey(region);
        }

        /**
         * Largest legal offset (kHz above {@link #baseKhz}) in {@code region}, or
         * -1 when the band is not allocated there. The offset is additionally
         * capped at 999 by the 3-digit field width.
         */
        public int maxOffsetForRegion(IaruRegion region) {
            Integer m = maxOffset.get(region);
            if (m == null) {
                return -1;
            }
            return Math.min(m, 999);
        }
    }

    // Region -> upper offset (kHz above base) helpers, kept terse for the table below.
    private static Map<IaruRegion, Integer> allRegions(int r1, int r2, int r3) {
        Map<IaruRegion, Integer> m = new LinkedHashMap<>();
        m.put(IaruRegion.REGION_1, r1);
        m.put(IaruRegion.REGION_2, r2);
        m.put(IaruRegion.REGION_3, r3);
        return m;
    }

    /**
     * Band table, in picker order. Bases sit at the MHz the FT8/FT4 digital
     * sub-bands occupy, so a 3-digit offset (0-999) covers every digital dial
     * frequency on the band even where the full allocation is wider (10 m, 6 m,
     * 2 m). Upper offsets encode the region-specific band edges used for gating.
     */
    private static final List<QsyBand> BANDS = buildBands();

    private static List<QsyBand> buildBands() {
        List<QsyBand> b = new ArrayList<>();
        //                code   baseKhz   R1   R2   R3   (upper offset, kHz above base)
        b.add(new QsyBand("160", 1800L,  allRegions(200,  200,  200)));
        b.add(new QsyBand("80",  3500L,  allRegions(300,  500,  400)));
        b.add(new QsyBand("40",  7000L,  allRegions(200,  300,  300)));
        b.add(new QsyBand("30",  10100L, allRegions(50,   50,   50)));
        b.add(new QsyBand("20",  14000L, allRegions(350,  350,  350)));
        b.add(new QsyBand("17",  18068L, allRegions(100,  100,  100)));
        b.add(new QsyBand("15",  21000L, allRegions(450,  450,  450)));
        b.add(new QsyBand("12",  24890L, allRegions(100,  100,  100)));
        b.add(new QsyBand("10",  28000L, allRegions(999,  999,  999)));
        b.add(new QsyBand("6",   50000L, allRegions(999,  999,  999)));
        b.add(new QsyBand("2",   144000L, allRegions(999,  999,  999)));
        return Collections.unmodifiableList(b);
    }

    /** All known QSY bands, in picker order (regardless of region). */
    public static List<QsyBand> allBands() {
        return BANDS;
    }

    /** Bands the picker should offer for {@code region}, in display order. */
    public static List<QsyBand> bandsForRegion(IaruRegion region) {
        List<QsyBand> out = new ArrayList<>();
        for (QsyBand band : BANDS) {
            if (band.availableIn(region)) {
                out.add(band);
            }
        }
        return out;
    }

    /** Look up a band by its on-air code (e.g. {@code "20"}); null if unknown. */
    public static QsyBand bandByCode(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        for (QsyBand band : BANDS) {
            if (band.code.equals(c)) {
                return band;
            }
        }
        return null;
    }

    /**
     * Build the on-air free text for a QSY request.
     *
     * @param dxCall    the addressee (DX Call field); normalised to upper case
     * @param bandCode  band code, e.g. {@code "20"} (see {@link QsyBand#code})
     * @param offsetKhz kHz above the band base, 0-999 and within the region edge
     * @param region    the operator's IARU region, used to gate the frequency
     * @return the free-text string, e.g. {@code "K1ABC.20 074"}
     * @throws IllegalArgumentException if the call is missing, the band is unknown
     *         or not allocated in {@code region}, the offset is out of range, or
     *         the result would exceed {@link #FREE_TEXT_LIMIT} characters
     */
    public static String buildQsyRequest(String dxCall, String bandCode, int offsetKhz, IaruRegion region) {
        String call = normCall(dxCall);
        if (call.isEmpty()) {
            throw new IllegalArgumentException("dxCall is required");
        }
        if (region == null) {
            throw new IllegalArgumentException("region is required");
        }
        QsyBand band = bandByCode(bandCode);
        if (band == null) {
            throw new IllegalArgumentException("unknown band code: " + bandCode);
        }
        if (!band.availableIn(region)) {
            throw new IllegalArgumentException(
                    band.meters + " is not allocated in IARU Region " + region.number);
        }
        int max = band.maxOffsetForRegion(region);
        if (offsetKhz < 0 || offsetKhz > max) {
            throw new IllegalArgumentException(
                    "offset " + offsetKhz + " kHz outside 0.." + max + " for "
                            + band.meters + " in Region " + region.number);
        }
        String text = String.format(Locale.US, "%s.%s %03d", call, band.code, offsetKhz);
        if (text.length() > FREE_TEXT_LIMIT) {
            throw new IllegalArgumentException(
                    "message \"" + text + "\" exceeds " + FREE_TEXT_LIMIT + " chars");
        }
        return text;
    }

    /**
     * Build a QSY request from an absolute frequency by resolving which band it
     * lands in. Convenience over {@link #buildQsyRequest(String, String, int,
     * IaruRegion)} for callers that already hold a dial frequency in kHz.
     *
     * @throws IllegalArgumentException if no allocated band in {@code region}
     *         contains {@code freqKhz}
     */
    public static String buildQsyRequestForFreqKhz(String dxCall, long freqKhz, IaruRegion region) {
        if (region == null) {
            throw new IllegalArgumentException("region is required");
        }
        for (QsyBand band : BANDS) {
            if (!band.availableIn(region)) {
                continue;
            }
            long offset = freqKhz - band.baseKhz;
            if (offset >= 0 && offset <= band.maxOffsetForRegion(region)) {
                return buildQsyRequest(dxCall, band.code, (int) offset, region);
            }
        }
        throw new IllegalArgumentException(
                freqKhz + " kHz is not in any band allocated in Region " + region.number);
    }

    /**
     * Build the on-air free text for a canned message.
     *
     * @param dxCall the addressee; normalised to upper case
     * @param canned the canned payload (should be one of {@link #CANNED_MESSAGES})
     * @return e.g. {@code "K1ABC 73 GL"}
     * @throws IllegalArgumentException if the call/text is missing or the result
     *         exceeds {@link #FREE_TEXT_LIMIT} characters
     */
    public static String buildCannedMessage(String dxCall, String canned) {
        String call = normCall(dxCall);
        if (call.isEmpty()) {
            throw new IllegalArgumentException("dxCall is required");
        }
        String payload = canned == null ? "" : canned.trim().toUpperCase(Locale.US);
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("canned text is required");
        }
        String text = call + " " + payload;
        if (text.length() > FREE_TEXT_LIMIT) {
            throw new IllegalArgumentException(
                    "message \"" + text + "\" exceeds " + FREE_TEXT_LIMIT + " chars");
        }
        return text;
    }

    /**
     * Whether {@code call} would fit an addressed canned/QSY message of a given
     * payload width — a UI convenience so the picker can disable options that
     * can't be sent to the current DX call. {@code payloadLen} is the length of
     * the part after the leading {@code "<CALL> "} / {@code "<CALL>."}.
     */
    public static boolean fitsAddressed(String dxCall, int payloadLen) {
        String call = normCall(dxCall);
        // +1 for the separator (space or period) between call and payload.
        return !call.isEmpty() && call.length() + 1 + payloadLen <= FREE_TEXT_LIMIT;
    }

    /** The kind of special message a decoded free-text frame carries. */
    public enum Kind { QSY, CANNED }

    /**
     * A recognised special message. For {@link Kind#QSY}, {@link #bandCode},
     * {@link #meters} and {@link #freqKhz} describe the requested frequency; for
     * {@link Kind#CANNED}, {@link #text} is the canned payload.
     */
    public static final class Parsed {
        public final Kind kind;
        /** Addressee callsign (upper case). */
        public final String toCall;
        /** QSY only: band code, e.g. {@code "20"}; null for canned. */
        public final String bandCode;
        /** QSY only: human band label, e.g. {@code "20m"}; null for canned. */
        public final String meters;
        /** QSY only: requested dial frequency in kHz; 0 for canned. */
        public final long freqKhz;
        /** Canned only: the payload text; null for QSY. */
        public final String text;

        private Parsed(Kind kind, String toCall, String bandCode, String meters, long freqKhz, String text) {
            this.kind = kind;
            this.toCall = toCall;
            this.bandCode = bandCode;
            this.meters = meters;
            this.freqKhz = freqKhz;
            this.text = text;
        }

        static Parsed qsy(String toCall, QsyBand band, long freqKhz) {
            return new Parsed(Kind.QSY, toCall, band.code, band.meters, freqKhz, null);
        }

        static Parsed canned(String toCall, String text) {
            return new Parsed(Kind.CANNED, toCall, null, null, 0, text);
        }

        /** Frequency as MHz string, e.g. {@code "14.074"} (QSY only). */
        public String freqMhz() {
            return String.format(Locale.US, "%d.%03d", freqKhz / 1000, freqKhz % 1000);
        }

        /** Short one-line summary for the QSY Monitor / popup. */
        public String summary() {
            if (kind == Kind.QSY) {
                return meters + " " + freqMhz();
            }
            return text;
        }
    }

    /**
     * Recognise a special message in a decoded free-text frame.
     *
     * <p>Recognition is intentionally strict to keep false positives off the QSY
     * Monitor: a QSY request must match {@code <CALL>.<BAND> <FFF>} with a known
     * band code and an in-range offset, and a canned message must be a known
     * callsign followed by exactly one of {@link #CANNED_MESSAGES}. Ordinary
     * structured FT8 exchanges never reach here — they decode as {@code i3!=0}
     * frames, not free text.
     *
     * @param freeText the frame's free-text content (the raw 13-char field)
     * @return the parsed message, or null if it is not a recognised special one
     */
    public static Parsed parse(String freeText) {
        if (freeText == null) {
            return null;
        }
        String s = freeText.trim().toUpperCase(Locale.US);
        if (s.isEmpty()) {
            return null;
        }
        // Collapse the run of trailing pad spaces getMessageText() adds, and split.
        int space = s.indexOf(' ');
        if (space < 0) {
            return null;
        }
        String head = s.substring(0, space);
        String tail = s.substring(space + 1).trim();
        if (tail.isEmpty()) {
            return null;
        }

        // QSY: head is "<CALL>.<BAND>", tail is a 1-3 digit offset.
        int dot = head.indexOf('.');
        if (dot > 0 && dot < head.length() - 1) {
            String call = head.substring(0, dot);
            String bandCode = head.substring(dot + 1);
            QsyBand band = bandByCode(bandCode);
            if (band != null && isPlausibleCall(call) && isDigits(tail) && tail.length() <= 3) {
                int offset = Integer.parseInt(tail);
                // Accept any offset the 3-digit field can hold that lands in the
                // band; region-gating is a send-side concern, a received request
                // is shown as-is (the operator decides whether to honour it).
                if (offset >= 0 && offset <= 999) {
                    return Parsed.qsy(call, band, band.baseKhz + offset);
                }
            }
        }

        // Canned: head is a plausible callsign, tail is a known canned payload.
        if (isPlausibleCall(head) && isCanned(tail)) {
            return Parsed.canned(head, tail);
        }
        return null;
    }

    /**
     * Convenience: parse and, if it is a special message addressed to
     * {@code myCall}, return it; otherwise null. Used by the QSY Monitor / popup
     * layer, which only cares about messages aimed at the operator.
     */
    public static Parsed parseFor(String freeText, String myCall) {
        Parsed p = parse(freeText);
        if (p == null) {
            return null;
        }
        String mine = normCall(myCall);
        if (!mine.isEmpty() && p.toCall.equals(mine)) {
            return p;
        }
        return null;
    }

    /** Whether {@code text} exactly matches one of the canned messages (case-insensitive). */
    public static boolean isCanned(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim().toUpperCase(Locale.US);
        for (String canned : CANNED_MESSAGES) {
            if (canned.equals(t)) {
                return true;
            }
        }
        return false;
    }

    // ---- small helpers ----

    private static String normCall(String call) {
        return call == null ? "" : call.trim().toUpperCase(Locale.US);
    }

    /** A conservative callsign shape: 3-11 chars of the call alphabet {@code [A-Z0-9/]}. */
    private static boolean isPlausibleCall(String s) {
        if (s == null || s.length() < 3 || s.length() > 11) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (c != '/') {
                return false;
            }
        }
        // Real callsigns carry at least one letter and one digit.
        return hasLetter && hasDigit;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
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
}
