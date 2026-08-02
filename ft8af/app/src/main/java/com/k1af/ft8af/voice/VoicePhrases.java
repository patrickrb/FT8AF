package com.k1af.ft8af.voice;

/**
 * Pure, Android-free builders for the exact spoken strings the voice
 * assistant utters. English-only for v1 (speech only — UI strings still live
 * in resources). All phrases are deliberately short (well under ~3 s of
 * speech) so a stop-on-TX never has much to cut off.
 *
 * <p>Callsigns are spelled letter-by-letter ("K1ABC" → "K 1 A B C") so the
 * TTS engine reads them as call signs instead of trying to pronounce them as
 * words.
 */
public final class VoicePhrases {
    private VoicePhrases() {}

    /** Mirrors {@code Ft8Message.SNR_UNKNOWN} without dragging that class in. */
    public static final int SNR_UNKNOWN = Integer.MIN_VALUE;

    /**
     * Spell a callsign for TTS: one space between characters, '/' spoken as
     * "stroke" (ham convention for portable/compound calls).
     */
    public static String spellCallsign(String callsign) {
        if (callsign == null) return "";
        String trimmed = callsign.trim();
        StringBuilder sb = new StringBuilder(trimmed.length() * 2);
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (sb.length() > 0) sb.append(' ');
            if (c == '/') {
                sb.append("stroke");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * SNR as speech: "minus 5", "plus 3", "zero". Empty string for the
     * {@link #SNR_UNKNOWN} sentinel (the decoder can emit a valid message
     * with no SNR — mirror DxAlertNotifier's body formatters and drop it).
     */
    public static String snrPhrase(int snr) {
        if (snr == SNR_UNKNOWN) return "";
        if (snr < 0) return "minus " + (-snr);
        if (snr == 0) return "zero";
        return "plus " + snr;
    }

    /** "K 1 A B C calling you, minus 5" (SNR clause dropped when unknown). */
    public static String callingYou(String callsign, int snr) {
        String base = spellCallsign(callsign) + " calling you";
        String snrPart = snrPhrase(snr);
        return snrPart.isEmpty() ? base : base + ", " + snrPart;
    }

    /** "QSO with K 1 A B C logged" */
    public static String qsoLogged(String callsign) {
        return "QSO with " + spellCallsign(callsign) + " logged";
    }

    /**
     * "New country: Japan". The caller passes the resolved country name, or a
     * pre-spelled callsign (via {@link #spellCallsign}) when no name resolved.
     */
    public static String newCountry(String spokenCountry) {
        return "New country: " + spokenCountry;
    }

    /** "New prefix: W 1" — prefixes are call fragments, so always spelled. */
    public static String newPrefix(String prefix) {
        return "New prefix: " + spellCallsign(prefix);
    }

    // ---- Command echo confirmations (spoken after a voice command runs) ----

    public static String echoCallingCq() {
        return "Calling CQ";
    }

    public static String echoStopping() {
        return "Stopping";
    }

    public static String echoSkipping() {
        return "Back to CQ";
    }

    public static String echoLogged() {
        return "Logged";
    }

    /** "Answering K 1 A B C" */
    public static String echoAnswering(String callsign) {
        return "Answering " + spellCallsign(callsign);
    }
}
