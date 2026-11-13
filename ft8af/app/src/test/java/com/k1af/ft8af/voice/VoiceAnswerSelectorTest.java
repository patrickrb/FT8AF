package com.k1af.ft8af.voice;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM tests for {@link VoiceAnswerSelector}. The selector is generic, so
 * these use a tiny stand-in message type instead of the Android-tied
 * {@code Ft8Message}.
 */
public class VoiceAnswerSelectorTest {

    /** Minimal decode stand-in: sender + whether it's addressed to me. */
    private static final class Msg {
        final String from;
        final boolean toMe;

        Msg(String from, boolean toMe) {
            this.from = from;
            this.toMe = toMe;
        }
    }

    private static Msg pick(List<Msg> decodes, String queueHead) {
        return VoiceAnswerSelector.pick(decodes, m -> m.toMe, m -> m.from, queueHead);
    }

    @Test
    public void callingMeBeatsQueueHead() {
        Msg queued = new Msg("W9XYZ", false);
        Msg callingMe = new Msg("K1ABC", true);
        assertThat(pick(Arrays.asList(queued, callingMe), "W9XYZ")).isSameInstanceAs(callingMe);
    }

    @Test
    public void newestCallingMeWins() {
        Msg older = new Msg("K1ABC", true);
        Msg newer = new Msg("W9XYZ", true);
        // List is oldest-first; the selector must take the newest (last).
        assertThat(pick(Arrays.asList(older, newer), null)).isSameInstanceAs(newer);
    }

    @Test
    public void fallsBackToQueueHeadNewestDecode() {
        Msg other = new Msg("N0CAL", false);
        Msg queuedOld = new Msg("W9XYZ", false);
        Msg queuedNew = new Msg("W9XYZ", false);
        assertThat(pick(Arrays.asList(queuedOld, other, queuedNew), "W9XYZ"))
                .isSameInstanceAs(queuedNew);
    }

    @Test
    public void queueHeadMatchIsCaseInsensitiveAndTrimmed() {
        Msg queued = new Msg("w9xyz", false);
        assertThat(pick(Collections.singletonList(queued), " W9XYZ ")).isSameInstanceAs(queued);
    }

    @Test
    public void emptyListYieldsNull() {
        assertThat(pick(new ArrayList<>(), "W9XYZ")).isNull();
        assertThat(pick(null, "W9XYZ")).isNull();
    }

    @Test
    public void noCandidateYieldsNull() {
        Msg other = new Msg("N0CAL", false);
        assertThat(pick(Collections.singletonList(other), null)).isNull();
        // Queue head not present in the decode list either.
        assertThat(pick(Collections.singletonList(other), "W9XYZ")).isNull();
    }

    @Test
    public void nullAndSenderlessRowsAreSkipped() {
        Msg callingMe = new Msg("K1ABC", true);
        Msg blankSender = new Msg("  ", true);
        Msg nullSender = new Msg(null, true);
        List<Msg> decodes = Arrays.asList(callingMe, null, blankSender, nullSender);
        assertThat(pick(decodes, null)).isSameInstanceAs(callingMe);
    }
}
