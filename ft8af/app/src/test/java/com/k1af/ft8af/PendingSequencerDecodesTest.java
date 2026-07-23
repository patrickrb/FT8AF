package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;

/**
 * Coverage for {@link PendingSequencerDecodes}, the stash that holds deep-pass
 * decodes arriving mid-TX until the sequencer can replay them after key-up.
 *
 * Plain JUnit: the class is pure Java and Ft8Message construction here touches
 * no Android framework types.
 */
public class PendingSequencerDecodesTest {

    private static Ft8Message msgAt(long utcTime) {
        Ft8Message msg = new Ft8Message("K1AF", "N2JFD", "R-18");
        msg.utcTime = utcTime;
        return msg;
    }

    private static ArrayList<Ft8Message> list(Ft8Message... msgs) {
        ArrayList<Ft8Message> out = new ArrayList<>();
        for (Ft8Message m : msgs) out.add(m);
        return out;
    }

    @Test
    public void startsEmpty() {
        PendingSequencerDecodes pending = new PendingSequencerDecodes();
        assertThat(pending.isEmpty()).isTrue();
        assertThat(pending.drain(0)).isEmpty();
    }

    @Test
    public void drainReturnsStashedMessagesAndClears() {
        PendingSequencerDecodes pending = new PendingSequencerDecodes();
        Ft8Message a = msgAt(1_000);
        Ft8Message b = msgAt(2_000);
        pending.stash(list(a, b), 3_000);

        assertThat(pending.isEmpty()).isFalse();
        assertThat(pending.drain(3_000)).containsExactly(a, b).inOrder();
        assertThat(pending.isEmpty()).isTrue();
        assertThat(pending.drain(3_000)).isEmpty();
    }

    @Test
    public void multipleStashesAccumulateInOrder() {
        // A slot can deliver several deep passes (first deep + subtraction
        // loop + late pass) before TX ends; all of them must survive.
        PendingSequencerDecodes pending = new PendingSequencerDecodes();
        Ft8Message a = msgAt(1_000);
        Ft8Message b = msgAt(1_000);
        pending.stash(list(a), 2_000);
        pending.stash(list(b), 3_000);

        assertThat(pending.drain(4_000)).containsExactly(a, b).inOrder();
    }

    @Test
    public void drainEvictsMessagesOlderThanMaxAge() {
        PendingSequencerDecodes pending = new PendingSequencerDecodes();
        Ft8Message stale = msgAt(0);
        Ft8Message fresh = msgAt(PendingSequencerDecodes.MAX_AGE_MS);
        pending.stash(list(stale, fresh), PendingSequencerDecodes.MAX_AGE_MS);

        // One tick past the stale message's allowed age: only fresh survives.
        assertThat(pending.drain(PendingSequencerDecodes.MAX_AGE_MS + 1))
                .containsExactly(fresh);
    }

    @Test
    public void messageExactlyAtMaxAgeIsKept() {
        PendingSequencerDecodes pending = new PendingSequencerDecodes();
        Ft8Message edge = msgAt(0);
        pending.stash(list(edge), 0);

        assertThat(pending.drain(PendingSequencerDecodes.MAX_AGE_MS))
                .containsExactly(edge);
    }

    @Test
    public void stashEvictsStaleEntriesBeforeAdding() {
        PendingSequencerDecodes pending = new PendingSequencerDecodes();
        Ft8Message stale = msgAt(0);
        pending.stash(list(stale), 0);

        Ft8Message fresh = msgAt(PendingSequencerDecodes.MAX_AGE_MS + 5_000);
        long now = PendingSequencerDecodes.MAX_AGE_MS + 10_000;
        pending.stash(list(fresh), now);

        assertThat(pending.drain(now)).containsExactly(fresh);
    }

    @Test
    public void maxAgeIsAtMostOneFt8Cycle() {
        // The cap must not outlive the exchange the decode belongs to. At the
        // old two-cycle (60s) setting, a partner's opening grid stashed behind
        // our report was still "fresh" a cycle later, when the fast pass had
        // already advanced us to RR73 on their R-report -- replaying it rewound
        // the QSO (POTA field report 2026-07-23). One 15s slot must survive so
        // a decode stashed mid-TX still replays after key-up.
        assertThat(PendingSequencerDecodes.MAX_AGE_MS).isAtLeast(15_000L);
        assertThat(PendingSequencerDecodes.MAX_AGE_MS).isAtMost(30_000L);
    }

    @Test
    public void decodeOlderThanOneCycleIsEvicted() {
        PendingSequencerDecodes pending = new PendingSequencerDecodes();
        Ft8Message oldDecode = msgAt(0);
        pending.stash(list(oldDecode), 0);

        // 30_001 ms == one full FT8 cycle (two 15s slots) past MAX_AGE_MS, so the
        // decode is evicted: the exchange it belonged to has moved on.
        assertThat(pending.drain(30_001)).isEmpty();
    }
}
