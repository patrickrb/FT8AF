package com.k1af.ft8af.ft8transmit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Pileup caller-selection policy: given the queue of stations waiting to be
 * worked after the current QSO, decide which one to pick next — and which ones
 * have given up and should not be called at all.
 *
 * <p>Two pick policies:
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
 * <p><b>Given-up pruning.</b> A station that called us during a QSO and then
 * stopped (tuned away, worked someone else, gave up) used to stay queued until
 * we called it — several cycles of "W1ABC K1AF -10" at nobody, while a station
 * that was still calling waited. A queued caller is only worth calling if it
 * called in the most recent receive slot, i.e. within the last full cycle
 * (two slots: theirs and ours). Anything older is pruned before the queue is
 * worked; if nobody fresh remains we go back to CQ, and a station that returns
 * is answered directly like any new caller.
 *
 * <p>Pure and side-effect free (apart from {@link #pruneGivenUp} mutating the
 * list it is handed) so the policy is unit-testable without the transmit engine
 * or any Android types.
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

    /**
     * How long (ms) a queued caller may go without calling us before it counts as
     * having given up: one full cycle, i.e. two slots. Their most recent chance to
     * call was the receive slot that just ended; a call from the receive slot
     * before that is already one cycle old.
     */
    public static long maxIdleMs(int slotMillis) {
        return 2L * slotMillis;
    }

    /**
     * Whether a caller last heard at {@code lastHeardUtc} (slot time) has given up
     * as of {@code nowMs} — both in the {@code UtcTimer.getSystemTime()} base.
     *
     * <p>With the fast pass delivering ~13-15 s into a 15 s slot, a station that
     * called in the slot being processed is 13-15 s old (kept) and one that last
     * called a cycle earlier is 43-45 s old (given up). Evidence-only passes that
     * land a few seconds into our own slot see the same split.
     *
     * @param lastHeardUtc slot time of the caller's most recent message to us
     * @param nowMs        the current time
     * @param slotMillis   the operating mode's slot length ({@code ModeProfile.slotMillis})
     */
    public static boolean hasGivenUp(long lastHeardUtc, long nowMs, int slotMillis) {
        return nowMs - lastHeardUtc >= maxIdleMs(slotMillis);
    }

    /**
     * Remove every caller that {@link #hasGivenUp} from {@code queue}, in place, and
     * return the removed callers (in queue order) so the caller can log them.
     * A null queue yields an empty list.
     */
    public static List<QueuedCaller> pruneGivenUp(List<QueuedCaller> queue, long nowMs,
                                                  int slotMillis) {
        List<QueuedCaller> removed = new ArrayList<>();
        if (queue == null) {
            return removed;
        }
        Iterator<QueuedCaller> it = queue.iterator();
        while (it.hasNext()) {
            QueuedCaller caller = it.next();
            if (hasGivenUp(caller.lastHeardUtc, nowMs, slotMillis)) {
                removed.add(caller);
                it.remove();
            }
        }
        return removed;
    }
}
