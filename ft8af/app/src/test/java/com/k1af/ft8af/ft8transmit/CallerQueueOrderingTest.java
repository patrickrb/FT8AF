package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link CallerQueueOrdering#pickNextIndex} — the pileup
 * caller-selection policy (first-heard FIFO vs. strongest-SNR-first).
 */
public class CallerQueueOrderingTest {

    private static QueuedCaller caller(String call, int snr) {
        return new QueuedCaller(call, 1000f, 0, snr, 0, 0, "", 0L);
    }

    @Test
    public void emptyQueue_returnsMinusOne() {
        assertThat(CallerQueueOrdering.pickNextIndex(new ArrayList<>(), false)).isEqualTo(-1);
        assertThat(CallerQueueOrdering.pickNextIndex(new ArrayList<>(), true)).isEqualTo(-1);
    }

    @Test
    public void nullQueue_returnsMinusOne() {
        assertThat(CallerQueueOrdering.pickNextIndex(null, true)).isEqualTo(-1);
    }

    @Test
    public void fifo_alwaysPicksHead() {
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", -5));
        q.add(caller("B", +12));   // stronger, but FIFO ignores SNR
        q.add(caller("C", 0));
        assertThat(CallerQueueOrdering.pickNextIndex(q, false)).isEqualTo(0);
    }

    @Test
    public void strongestFirst_picksHighestSnr() {
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", -5));
        q.add(caller("B", +12));
        q.add(caller("C", +3));
        assertThat(CallerQueueOrdering.pickNextIndex(q, true)).isEqualTo(1);
    }

    @Test
    public void strongestFirst_negativeSnrs_picksLeastNegative() {
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", -20));
        q.add(caller("B", -3));
        q.add(caller("C", -15));
        assertThat(CallerQueueOrdering.pickNextIndex(q, true)).isEqualTo(1);
    }

    @Test
    public void strongestFirst_tieBreaksToEarliest() {
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", +7));
        q.add(caller("B", +7));   // equal SNR, queued later
        assertThat(CallerQueueOrdering.pickNextIndex(q, true)).isEqualTo(0);
    }

    @Test
    public void strongestFirst_singleCaller() {
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", -30));
        assertThat(CallerQueueOrdering.pickNextIndex(q, true)).isEqualTo(0);
    }

    @Test
    public void strongestFirst_headIsAlreadyStrongest() {
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", +15));
        q.add(caller("B", +2));
        assertThat(CallerQueueOrdering.pickNextIndex(q, true)).isEqualTo(0);
    }

    @Test
    public void draining_strongestFirst_yieldsDescendingOrder() {
        // Simulate repeatedly picking-and-removing to confirm the whole pileup
        // drains strongest-first.
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", -5));
        q.add(caller("B", +12));
        q.add(caller("C", +3));
        q.add(caller("D", -18));

        List<String> worked = new ArrayList<>();
        while (!q.isEmpty()) {
            int idx = CallerQueueOrdering.pickNextIndex(q, true);
            worked.add(q.remove(idx).callsign);
        }
        assertThat(worked).containsExactly("B", "C", "A", "D").inOrder();
    }

    @Test
    public void draining_fifo_yieldsQueueOrder() {
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", -5));
        q.add(caller("B", +12));
        q.add(caller("C", +3));

        List<String> worked = new ArrayList<>();
        while (!q.isEmpty()) {
            int idx = CallerQueueOrdering.pickNextIndex(q, false);
            worked.add(q.remove(idx).callsign);
        }
        assertThat(worked).containsExactly("A", "B", "C").inOrder();
    }

    @Test
    public void pickNextIndex_doesNotMutateQueue() {
        List<QueuedCaller> q = new ArrayList<>();
        q.add(caller("A", -5));
        q.add(caller("B", +12));
        List<QueuedCaller> snapshot = Collections.unmodifiableList(new ArrayList<>(q));
        CallerQueueOrdering.pickNextIndex(q, true);
        assertThat(q).containsExactlyElementsIn(snapshot).inOrder();
    }

    // ---- given-up pruning --------------------------------------------------

    private static final int FT8_SLOT_MS = 15_000;
    private static final int FT4_SLOT_MS = 7_500;

    private static QueuedCaller heardAt(String call, long lastHeardUtc) {
        return new QueuedCaller(call, 1000f, 0, -10, 0, 0, "", lastHeardUtc);
    }

    @Test
    public void maxIdle_isOneFullCycle() {
        assertThat(CallerQueueOrdering.maxIdleMs(FT8_SLOT_MS)).isEqualTo(30_000L);
        assertThat(CallerQueueOrdering.maxIdleMs(FT4_SLOT_MS)).isEqualTo(15_000L);
    }

    @Test
    public void callerFromTheSlotJustDecoded_isFresh() {
        // Fast pass delivers ~13-15 s into the slot; the slot's callers are that old.
        long slot = 1_000_000L;
        assertThat(CallerQueueOrdering.hasGivenUp(slot, slot + 13_500, FT8_SLOT_MS)).isFalse();
        assertThat(CallerQueueOrdering.hasGivenUp(slot, slot + 15_500, FT8_SLOT_MS)).isFalse();
        // A late/evidence pass a few seconds into our own slot still sees them fresh.
        assertThat(CallerQueueOrdering.hasGivenUp(slot, slot + 18_500, FT8_SLOT_MS)).isFalse();
    }

    @Test
    public void callerFromOneCycleEarlier_hasGivenUp() {
        // Their last call was the receive slot BEFORE the one just decoded.
        long slot = 1_000_000L;
        long previousRxSlot = slot - 30_000;
        assertThat(CallerQueueOrdering.hasGivenUp(previousRxSlot, slot + 13_500, FT8_SLOT_MS)).isTrue();
        assertThat(CallerQueueOrdering.hasGivenUp(previousRxSlot, slot + 18_500, FT8_SLOT_MS)).isTrue();
    }

    @Test
    public void idleBoundary_isInclusive() {
        assertThat(CallerQueueOrdering.hasGivenUp(0, 29_999, FT8_SLOT_MS)).isFalse();
        assertThat(CallerQueueOrdering.hasGivenUp(0, 30_000, FT8_SLOT_MS)).isTrue();
    }

    @Test
    public void idleScalesWithSlotLength() {
        // FT4: 7.5 s slots, so a cycle is 15 s.
        assertThat(CallerQueueOrdering.hasGivenUp(0, 14_999, FT4_SLOT_MS)).isFalse();
        assertThat(CallerQueueOrdering.hasGivenUp(0, 15_000, FT4_SLOT_MS)).isTrue();
    }

    @Test
    public void prune_removesOnlyGivenUpCallers_andReturnsThemInOrder() {
        long now = 1_000_000L;
        List<QueuedCaller> q = new ArrayList<>();
        q.add(heardAt("OLD1", now - 45_000));   // two cycles back -> gone
        q.add(heardAt("FRESH", now - 14_000));  // this slot -> stays
        q.add(heardAt("OLD2", now - 30_000));   // exactly one cycle -> gone
        List<QueuedCaller> removed = CallerQueueOrdering.pruneGivenUp(q, now, FT8_SLOT_MS);
        assertThat(q).hasSize(1);
        assertThat(q.get(0).callsign).isEqualTo("FRESH");
        assertThat(removed).hasSize(2);
        assertThat(removed.get(0).callsign).isEqualTo("OLD1");
        assertThat(removed.get(1).callsign).isEqualTo("OLD2");
    }

    @Test
    public void prune_leavesAllFreshCallersUntouched() {
        long now = 1_000_000L;
        List<QueuedCaller> q = new ArrayList<>();
        q.add(heardAt("A", now - 14_000));
        q.add(heardAt("B", now - 13_000));
        assertThat(CallerQueueOrdering.pruneGivenUp(q, now, FT8_SLOT_MS)).isEmpty();
        assertThat(q).hasSize(2);
    }

    @Test
    public void prune_emptiesAQueueNobodyIsCallingFrom() {
        // The "just go back to CQ" case: everyone queued stopped calling.
        long now = 1_000_000L;
        List<QueuedCaller> q = new ArrayList<>();
        q.add(heardAt("A", now - 44_000));
        q.add(heardAt("B", now - 74_000));
        assertThat(CallerQueueOrdering.pruneGivenUp(q, now, FT8_SLOT_MS)).hasSize(2);
        assertThat(q).isEmpty();
        assertThat(CallerQueueOrdering.pickNextIndex(q, false)).isEqualTo(-1);
    }

    @Test
    public void prune_nullQueue_returnsEmpty() {
        assertThat(CallerQueueOrdering.pruneGivenUp(null, 0, FT8_SLOT_MS)).isEmpty();
    }

    @Test
    public void strongestFirst_picksAmongFreshCallersOnly() {
        // A strong caller that gave up must not win the pick over a weaker one
        // still calling.
        long now = 1_000_000L;
        List<QueuedCaller> q = new ArrayList<>();
        QueuedCaller loud = heardAt("LOUD", now - 44_000);
        loud.snr = +10;
        QueuedCaller quiet = heardAt("QUIET", now - 14_000);
        quiet.snr = -15;
        q.add(loud);
        q.add(quiet);
        CallerQueueOrdering.pruneGivenUp(q, now, FT8_SLOT_MS);
        assertThat(q.get(CallerQueueOrdering.pickNextIndex(q, true)).callsign).isEqualTo("QUIET");
    }
}
