package com.k1af.ft8af.voice;

import java.util.Locale;
import java.util.Set;

/**
 * Pure, Android-free decision + dedup logic for spoken announcements (the
 * voice-assistant counterpart of {@code alert/AlertDecisions}). The announcer
 * itself touches TextToSpeech and can't be unit-tested; every branch here can.
 *
 * <p>Priority when one decode qualifies in several categories: a station
 * calling ME beats everything (it needs action now), then new DXCC, then new
 * prefix. New-DXCC / new-prefix announcements apply to CQ broadcasts only —
 * same rule as the needed-DX notification alerts.
 *
 * <p>Dedup keys are namespaced strings collected in a per-session set so a
 * station calling every cycle (and every decode pass within a cycle — early,
 * late, deep passes all funnel through processDecodes) is announced once.
 */
public final class VoiceAnnouncementDecisions {
    private VoiceAnnouncementDecisions() {}

    public enum Kind { CALLING_ME, NEW_DXCC, NEW_PREFIX }

    /** Whether any per-decode announcement toggle is on (cheap early-out). */
    public static boolean anyDecodeAnnounceEnabled(
            boolean announceCalling, boolean announceNewDxcc, boolean announceNewPrefix) {
        return announceCalling || announceNewDxcc || announceNewPrefix;
    }

    /**
     * Decide which announcement (if any) a decoded message earns.
     *
     * @param announceCalling   the voiceAnnounceCalling user toggle
     * @param announceNewDxcc   the voiceAnnounceNewDxcc user toggle
     * @param announceNewPrefix the voiceAnnounceNewPrefix user toggle
     * @param addressedToMe     message's target callsign is mine
     * @param isCq              message is a CQ broadcast
     * @param fromNewDxcc       sender is a new (unworked) DXCC entity
     * @param fromNewPrefix     sender carries a new (unworked) WPX prefix
     * @param blocked           message is filtered by the user's block list
     * @return the announcement to speak, or null for silence
     */
    public static Kind decide(boolean announceCalling, boolean announceNewDxcc,
                              boolean announceNewPrefix, boolean addressedToMe,
                              boolean isCq, boolean fromNewDxcc, boolean fromNewPrefix,
                              boolean blocked) {
        if (blocked) return null;
        if (announceCalling && addressedToMe) return Kind.CALLING_ME;
        if (!isCq) return null;
        if (announceNewDxcc && fromNewDxcc) return Kind.NEW_DXCC;
        if (announceNewPrefix && fromNewPrefix) return Kind.NEW_PREFIX;
        return null;
    }

    /**
     * Claim the right to speak {@code dedupKey}, returning true only when the
     * caller should proceed. The speakability gate ({@code canSpeak} — false
     * while transmitting, when TTS would leak into the rig audio) is evaluated
     * BEFORE the dedup set is touched: burning the key while muted would
     * silence that station for the whole session, so a station first heard
     * during a transmission still gets announced on its next decode.
     */
    public static boolean claim(Set<String> spoken, String dedupKey, boolean canSpeak) {
        if (!canSpeak) return false; // gate FIRST — do not burn the key while muted
        return spoken.add(dedupKey);
    }

    /** One announcement per calling station per session. */
    public static String callingMeKey(String fromCallsign) {
        return "VCALL:" + norm(fromCallsign);
    }

    /** One announcement per new country per session. */
    public static String newDxccKey(String country) {
        return "VDXCC:" + norm(country);
    }

    /** One announcement per new prefix per session. */
    public static String newPrefixKey(String prefix) {
        return "VPREFIX:" + norm(prefix);
    }

    /** One announcement per logged contact (station + completion time). */
    public static String qsoCompleteKey(String toCallsign, String endTime) {
        return "VQSO:" + norm(toCallsign) + "|" + norm(endTime);
    }

    private static String norm(String s) {
        // Locale.ROOT: default-locale casing (e.g. Turkish dotted/dotless I)
        // would make dedup keys differ between devices for the same station.
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
