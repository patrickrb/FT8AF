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
        return new QueuedCaller(call, 1000f, 0, snr, 0, 0, "");
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
}
