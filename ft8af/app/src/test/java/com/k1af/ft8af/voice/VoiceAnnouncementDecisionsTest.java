package com.k1af.ft8af.voice;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.voice.VoiceAnnouncementDecisions.Kind;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Pure-JVM tests for {@link VoiceAnnouncementDecisions} (no Android runtime needed). */
public class VoiceAnnouncementDecisionsTest {

    private static Set<String> newSpokenSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    // ---- decide: toggle gating ------------------------------------------

    @Test
    public void decide_callingMe_firesOnlyWhenToggleOn() {
        assertThat(VoiceAnnouncementDecisions.decide(
                true, false, false, true, false, false, false, false))
                .isEqualTo(Kind.CALLING_ME);
        assertThat(VoiceAnnouncementDecisions.decide(
                false, false, false, true, false, false, false, false))
                .isNull();
    }

    @Test
    public void decide_newDxcc_firesOnlyWhenToggleOnAndCq() {
        assertThat(VoiceAnnouncementDecisions.decide(
                false, true, false, false, true, true, false, false))
                .isEqualTo(Kind.NEW_DXCC);
        // Toggle off
        assertThat(VoiceAnnouncementDecisions.decide(
                false, false, false, false, true, true, false, false))
                .isNull();
        // Not a CQ — needed-DX announcements are CQ-gated like the alerts.
        assertThat(VoiceAnnouncementDecisions.decide(
                false, true, false, false, false, true, false, false))
                .isNull();
    }

    @Test
    public void decide_newPrefix_firesOnlyWhenToggleOnAndCq() {
        assertThat(VoiceAnnouncementDecisions.decide(
                false, false, true, false, true, false, true, false))
                .isEqualTo(Kind.NEW_PREFIX);
        assertThat(VoiceAnnouncementDecisions.decide(
                false, false, false, false, true, false, true, false))
                .isNull();
        assertThat(VoiceAnnouncementDecisions.decide(
                false, false, true, false, false, false, true, false))
                .isNull();
    }

    // ---- decide: priority ------------------------------------------------

    @Test
    public void decide_addressedToMeBeatsEverything() {
        // All toggles on, message qualifies in every category: calling-me wins.
        assertThat(VoiceAnnouncementDecisions.decide(
                true, true, true, true, true, true, true, false))
                .isEqualTo(Kind.CALLING_ME);
    }

    @Test
    public void decide_newDxccBeatsNewPrefix() {
        assertThat(VoiceAnnouncementDecisions.decide(
                true, true, true, false, true, true, true, false))
                .isEqualTo(Kind.NEW_DXCC);
    }

    @Test
    public void decide_blockedIsAlwaysSilent() {
        assertThat(VoiceAnnouncementDecisions.decide(
                true, true, true, true, true, true, true, true))
                .isNull();
    }

    @Test
    public void decide_nothingQualifies() {
        assertThat(VoiceAnnouncementDecisions.decide(
                true, true, true, false, true, false, false, false))
                .isNull();
    }

    // ---- anyDecodeAnnounceEnabled ----------------------------------------

    @Test
    public void anyDecodeAnnounceEnabled_coversEachToggle() {
        assertThat(VoiceAnnouncementDecisions.anyDecodeAnnounceEnabled(false, false, false))
                .isFalse();
        assertThat(VoiceAnnouncementDecisions.anyDecodeAnnounceEnabled(true, false, false))
                .isTrue();
        assertThat(VoiceAnnouncementDecisions.anyDecodeAnnounceEnabled(false, true, false))
                .isTrue();
        assertThat(VoiceAnnouncementDecisions.anyDecodeAnnounceEnabled(false, false, true))
                .isTrue();
    }

    // ---- claim: dedup + mute gate ----------------------------------------

    @Test
    public void claim_sameStationAnnouncedOncePerSession() {
        Set<String> spoken = newSpokenSet();
        String key = VoiceAnnouncementDecisions.callingMeKey("K1ABC");
        assertThat(VoiceAnnouncementDecisions.claim(spoken, key, true)).isTrue();
        // Same station on the next decode pass / cycle: silent.
        assertThat(VoiceAnnouncementDecisions.claim(spoken, key, true)).isFalse();
    }

    @Test
    public void claim_doesNotBurnKeyWhileMuted() {
        Set<String> spoken = newSpokenSet();
        String key = VoiceAnnouncementDecisions.callingMeKey("K1ABC");
        // TX active — no speech, and the key must survive for the next cycle.
        assertThat(VoiceAnnouncementDecisions.claim(spoken, key, false)).isFalse();
        assertThat(spoken).isEmpty();
        // TX over, station still calling: announce now.
        assertThat(VoiceAnnouncementDecisions.claim(spoken, key, true)).isTrue();
    }

    @Test
    public void claim_differentStationsAreIndependent() {
        Set<String> spoken = newSpokenSet();
        assertThat(VoiceAnnouncementDecisions.claim(
                spoken, VoiceAnnouncementDecisions.callingMeKey("K1ABC"), true)).isTrue();
        assertThat(VoiceAnnouncementDecisions.claim(
                spoken, VoiceAnnouncementDecisions.callingMeKey("W9XYZ"), true)).isTrue();
    }

    // ---- key formats -------------------------------------------------------

    @Test
    public void keys_areNamespacedAndNormalized() {
        assertThat(VoiceAnnouncementDecisions.callingMeKey(" k1abc "))
                .isEqualTo("VCALL:K1ABC");
        assertThat(VoiceAnnouncementDecisions.newDxccKey("Japan"))
                .isEqualTo("VDXCC:JAPAN");
        assertThat(VoiceAnnouncementDecisions.newPrefixKey("w1"))
                .isEqualTo("VPREFIX:W1");
        assertThat(VoiceAnnouncementDecisions.qsoCompleteKey("K1ABC", "2026-08-02 12:00"))
                .isEqualTo("VQSO:K1ABC|2026-08-02 12:00");
    }

    @Test
    public void keys_nullSafe() {
        assertThat(VoiceAnnouncementDecisions.callingMeKey(null)).isEqualTo("VCALL:");
        assertThat(VoiceAnnouncementDecisions.qsoCompleteKey(null, null)).isEqualTo("VQSO:|");
    }
}
