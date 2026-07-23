package com.k1af.ft8af;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Holds deep/late-pass decodes that arrived while a transmission was in
 * progress, so the QSO auto-sequencer can replay them as positive evidence at
 * the first opportunity after key-up.
 *
 * <p>Deep and subtraction-loop passes typically finish 0.5–3&nbsp;s after the
 * slot's fast pass — i.e. right around or after TX keys up. Before this class
 * existed those decodes reached the UI but never the sequencer, so a reply the
 * fast pass missed (found only by a deep pass) left the QSO stalled repeating
 * the previous message. Results that land while transmitting are stashed here
 * and drained by {@code MainViewModel.afterDecode} on the next delivery with TX
 * idle (typically the own-slot fast pass ~1&nbsp;s after key-up), then handed to
 * {@code FT8TransmitSignal.parseMessageToFunction(list, true)}.
 *
 * <p>Entries older than {@link #MAX_AGE_MS} are evicted on every stash/drain:
 * a reply is only evidence for the QSO it belongs to, and after two full
 * cycles the fast pass has since made its own calls — acting on an older
 * stashed report could advance a state it no longer describes. Matching is
 * additionally guarded by the sequencer itself (from/to callsign and slot
 * parity checks), so the age cap is a backstop, not the primary filter.
 *
 * <p>Thread-safe: a late full-slot pass delivers from the previous slot's
 * decode thread while the current slot's thread may be delivering its own
 * passes.
 */
public final class PendingSequencerDecodes {
    /**
     * Maximum age of a stashed decode, measured from the message's slot time
     * ({@code Ft8Message.utcTime}). One full FT8 cycle (2 slots) — long enough to
     * survive the transmission it was stashed behind and be replayed at the next
     * key-down gap, short enough that it cannot describe a QSO state the fast
     * pass has already moved past.
     *
     * <p>This was two cycles (60s). At that age a decode outlives a whole
     * exchange: the partner's opening grid, stashed behind our report, was still
     * "fresh" a cycle later when the fast pass had already advanced us to RR73 on
     * their R-report. Replaying it rewound the QSO and re-sent the report. The
     * sequencer refuses to rewind now
     * ({@link com.k1af.ft8af.ft8transmit.FT8TransmitSignal#isStaleEvidence}); this
     * cap keeps such decodes from reaching it at all.
     */
    static final long MAX_AGE_MS = 30_000;

    private final ArrayList<Ft8Message> pending = new ArrayList<>();

    /**
     * Stash one pass's decodes for replay after TX ends.
     *
     * @param nowMs current time in the same base as {@code Ft8Message.utcTime},
     *              i.e. {@code UtcTimer.getSystemTime()} (NTP/GPS-corrected) —
     *              not the raw system clock, whose correction offset would skew
     *              the age comparison
     */
    public synchronized void stash(List<Ft8Message> messages, long nowMs) {
        evictStale(nowMs);
        pending.addAll(messages);
    }

    /**
     * Remove and return all stashed decodes still fresh at {@code nowMs}.
     * Returns an empty list when nothing is pending.
     *
     * @param nowMs current time in the {@code UtcTimer.getSystemTime()} base
     *              (see {@link #stash})
     */
    public synchronized ArrayList<Ft8Message> drain(long nowMs) {
        evictStale(nowMs);
        ArrayList<Ft8Message> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /** True when nothing is stashed (stale entries are not counted out). */
    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }

    private void evictStale(long nowMs) {
        Iterator<Ft8Message> it = pending.iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().utcTime > MAX_AGE_MS) {
                it.remove();
            }
        }
    }
}
