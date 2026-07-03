package com.k1af.ft8af.ft8listener;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

/**
 * The late full-slot pass (issue #363) merges its decodes into the slot's {@code allMsg}
 * dedup scope via {@code FT8SignalListener.addMsgToList}, and delivers ONLY what survives
 * the merge — otherwise every message the early passes already reported would be
 * duplicated into the UI list, SWL database, and PSKReporter uploads. These tests pin the
 * merge semantics that delivery decision rests on.
 */
@RunWith(RobolectricTestRunner.class)
public class LateMessageMergeTest {

    /** A message whose text is unique per {@code text} (free-text path of getMessageText). */
    private static Ft8Message msg(String text) {
        return new Ft8Message("CQ", "K1ABC", text);
    }

    private static Ft8Message msg(String text, int snr) {
        Ft8Message m = msg(text);
        m.snr = snr;
        return m;
    }

    private static ArrayList<Ft8Message> listOf(Ft8Message... msgs) {
        ArrayList<Ft8Message> list = new ArrayList<>();
        for (Ft8Message m : msgs) {
            list.add(m);
        }
        return list;
    }

    @Test
    public void merge_dropsMessagesTheEarlyPassAlreadyDelivered() {
        ArrayList<Ft8Message> allMsg = listOf(msg("EARLY1"), msg("EARLY2"));
        // Late pass re-decodes both early messages plus one genuinely new late signal.
        ArrayList<Ft8Message> late = listOf(msg("EARLY1"), msg("LATE1"), msg("EARLY2"));

        FT8SignalListener.addMsgToList(allMsg, late);

        // Only the new message survives in the delivery list...
        assertThat(late).hasSize(1);
        assertThat(late.get(0).getMessageText()).isEqualTo(msg("LATE1").getMessageText());
        // ...and the dedup scope now also contains it (so a later deep pass can't
        // re-deliver it either).
        assertThat(allMsg).hasSize(3);
    }

    @Test
    public void merge_allDuplicates_leavesNothingToDeliver() {
        // The common case: the full-slot buffer re-decodes exactly what the early window
        // held. The delivery list must end up empty — the late pass then skips
        // afterDecode entirely.
        ArrayList<Ft8Message> allMsg = listOf(msg("EARLY1"), msg("EARLY2"));
        ArrayList<Ft8Message> late = listOf(msg("EARLY2"), msg("EARLY1"));

        FT8SignalListener.addMsgToList(allMsg, late);

        assertThat(late).isEmpty();
        assertThat(allMsg).hasSize(2);
    }

    @Test
    public void merge_intoEmptyScope_keepsEverything() {
        ArrayList<Ft8Message> allMsg = new ArrayList<>();
        ArrayList<Ft8Message> late = listOf(msg("LATE1"), msg("LATE2"));

        FT8SignalListener.addMsgToList(allMsg, late);

        assertThat(late).hasSize(2);
        assertThat(allMsg).hasSize(2);
    }

    @Test
    public void merge_duplicateWithBetterSnr_upgradesTheKeptCopy() {
        // The full 15s buffer can yield a better SNR estimate for the same signal; the
        // duplicate is still dropped, but its stronger SNR is folded into the copy the UI
        // already shows.
        Ft8Message early = msg("EARLY1", -18);
        ArrayList<Ft8Message> allMsg = listOf(early);
        ArrayList<Ft8Message> late = listOf(msg("EARLY1", -12));

        FT8SignalListener.addMsgToList(allMsg, late);

        assertThat(late).isEmpty();
        assertThat(early.snr).isEqualTo(-12);
    }

    @Test
    public void merge_duplicateWithUnknownSnr_doesNotDowngradeKnownSnr() {
        Ft8Message early = msg("EARLY1", -18);
        ArrayList<Ft8Message> allMsg = listOf(early);
        ArrayList<Ft8Message> late = listOf(msg("EARLY1"));// SNR_UNKNOWN

        FT8SignalListener.addMsgToList(allMsg, late);

        assertThat(late).isEmpty();
        assertThat(early.snr).isEqualTo(-18);
    }

    @Test
    public void checkMessageSame_matchesOnTextOnly() {
        Ft8Message a = msg("SAME TEXT");
        Ft8Message b = msg("SAME TEXT");
        b.freq_hz = 1500f;// different frequency, same content -> still the same message
        assertThat(FT8SignalListener.checkMessageSame(listOf(a), b)).isTrue();
        assertThat(FT8SignalListener.checkMessageSame(listOf(a), msg("OTHER"))).isFalse();
    }
}
