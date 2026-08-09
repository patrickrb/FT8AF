package com.k1af.ft8af.ft8transmit;

import java.util.List;

/**
 * Pileup caller-selection policy: given the queue of stations waiting to be
 * worked after the current QSO, decide which one to pick next.
 *
 * <p>Two policies:
 * <ul>
 *   <li><b>First-heard (FIFO)</b> — the default. The station that has been
 *       waiting longest (index 0, the head of the queue) is worked next. This
 *       is the fairest order and matches the historic behavior.</li>
 *   <li><b>Strongest-first</b> — the waiting caller with the highest SNR is
 *       worked next, so a busy DXpedition/POTA/contest run completes the
 *       best-copy exchanges first and wastes fewer cycles on marginal signals.
 *       Ties break toward the earliest-queued caller (lowest index), so equal
 *       signals are still worked in the order they called.</li>
 * </ul>
 *
 * <p>Pure and side-effect free so the policy is unit-testable without the
 * transmit engine or any Android types.
 */
public final class CallerQueueOrdering {

    private CallerQueueOrdering() {
    }

    /**
     * Index into {@code queue} of the caller to work next, or {@code -1} when the
     * queue is empty/null.
     *
     * @param queue          the pending callers, head-first (index 0 = queued earliest)
     * @param strongestFirst when true, pick the highest-SNR caller; otherwise FIFO (index 0)
     */
    public static int pickNextIndex(List<QueuedCaller> queue, boolean strongestFirst) {
        if (queue == null || queue.isEmpty()) {
            return -1;
        }
        if (!strongestFirst) {
            return 0;
        }
        int bestIndex = 0;
        int bestSnr = queue.get(0).snr;
        for (int i = 1; i < queue.size(); i++) {
            int snr = queue.get(i).snr;
            // Strictly greater keeps ties on the earliest-queued caller.
            if (snr > bestSnr) {
                bestSnr = snr;
                bestIndex = i;
            }
        }
        return bestIndex;
    }
}
