package com.k1af.ft8af.ft8transmit;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Picks a clear FT8/FT4 TX audio offset for CQ calling (issue #418).
 *
 * <p>Records every kept decode as an occupancy observation, keeps a sliding
 * multi-cycle window of them, and scores candidate offsets by their clearance
 * from occupied spectrum. See {@code docs/clear-cq-slot-selection.md} for the
 * research/design note behind the defaults; every tunable is a constructor
 * parameter so the tests (and future settings) can drive them explicitly.
 *
 * <p>Pure Java, no Android types. Thread-safe: decodes arrive on decoder
 * threads while selection runs from the UI thread.
 */
public final class ClearFrequencyFinder {

    /** An FT8/FT4 signal occupies 8 tones x 6.25 Hz starting at its offset. */
    public static final float SIGNAL_BANDWIDTH_HZ = 50f;

    // ---- default tunables (see the design note for the rationale) -----------
    public static final int DEFAULT_HISTORY_CYCLES = 4;
    public static final float DEFAULT_GUARD_HZ = 10f;
    public static final float DEFAULT_MIN_OFFSET_HZ = 200f;
    public static final float DEFAULT_MAX_OFFSET_HZ = 2800f;
    public static final float DEFAULT_STEP_HZ = 10f;
    public static final float DEFAULT_CLEARANCE_CAP_HZ = 200f;
    public static final float PREFERRED_CENTER_HZ = 1500f;
    public static final long DEFAULT_MOVE_HOLDDOWN_MS = 45_000L;

    /** One observed signal: base offset + the slot UTC it was decoded in. */
    private static final class Observation {
        final float freqHz;
        final long utcMs;

        Observation(float freqHz, long utcMs) {
            this.freqHz = freqHz;
            this.utcMs = utcMs;
        }
    }

    private final int historyCycles;
    private final float guardHz;
    private final float minOffsetHz;
    private final float maxOffsetHz;
    private final float stepHz;
    private final float clearanceCapHz;
    private final long moveHolddownMs;

    private final ArrayDeque<Observation> history = new ArrayDeque<>();
    private long newestUtcMs = Long.MIN_VALUE;
    private long lastMoveUtcMs = Long.MIN_VALUE;

    public ClearFrequencyFinder() {
        this(DEFAULT_HISTORY_CYCLES, DEFAULT_GUARD_HZ, DEFAULT_MIN_OFFSET_HZ,
                DEFAULT_MAX_OFFSET_HZ, DEFAULT_STEP_HZ, DEFAULT_CLEARANCE_CAP_HZ,
                DEFAULT_MOVE_HOLDDOWN_MS);
    }

    public ClearFrequencyFinder(int historyCycles, float guardHz, float minOffsetHz,
                                float maxOffsetHz, float stepHz, float clearanceCapHz,
                                long moveHolddownMs) {
        this.historyCycles = Math.max(1, historyCycles);
        this.guardHz = Math.max(0f, guardHz);
        this.minOffsetHz = minOffsetHz;
        this.maxOffsetHz = Math.max(minOffsetHz + SIGNAL_BANDWIDTH_HZ, maxOffsetHz);
        this.stepHz = Math.max(1f, stepHz);
        this.clearanceCapHz = Math.max(0f, clearanceCapHz);
        this.moveHolddownMs = Math.max(0L, moveHolddownMs);
    }

    /**
     * Record one kept decode as an occupancy observation and evict everything
     * older than the history window (measured against the newest UTC seen, so
     * the window length follows the mode's slot length).
     */
    public synchronized void record(float freqHz, long utcMs, long slotMillis) {
        history.addLast(new Observation(freqHz, utcMs));
        if (utcMs > newestUtcMs) {
            newestUtcMs = utcMs;
        }
        evict(slotMillis);
    }

    /** Drop the whole history (band/mode change: old occupancy is meaningless). */
    public synchronized void clear() {
        history.clear();
        newestUtcMs = Long.MIN_VALUE;
    }

    /** Observations currently inside the window (test/diagnostic visibility). */
    synchronized int observationCount() {
        return history.size();
    }

    private void evict(long slotMillis) {
        long cutoff = newestUtcMs - (long) historyCycles * Math.max(1L, slotMillis);
        Iterator<Observation> it = history.iterator();
        while (it.hasNext()) {
            if (it.next().utcMs < cutoff) {
                it.remove();
            }
        }
    }

    /**
     * Clearance of the candidate's 50 Hz interval from the nearest occupied
     * interval in the window: 0 when they intersect (occupied), otherwise the
     * gap in Hz, capped at {@link #DEFAULT_CLEARANCE_CAP_HZ} (constructor
     * value) — beyond the cap more empty space buys nothing, and the cap stops
     * the ranking from always racing to the extreme band edges.
     */
    public synchronized float clearanceAt(float offsetHz) {
        float lo = offsetHz;
        float hi = offsetHz + SIGNAL_BANDWIDTH_HZ;
        float best = clearanceCapHz;
        for (Observation o : history) {
            float occLo = o.freqHz - guardHz;
            float occHi = o.freqHz + SIGNAL_BANDWIDTH_HZ + guardHz;
            if (hi >= occLo && lo <= occHi) {
                return 0f; // intersects an occupied interval
            }
            float gap = (lo > occHi) ? lo - occHi : occLo - hi;
            if (gap < best) {
                best = gap;
            }
        }
        return best;
    }

    /** True when the offset's signal interval touches occupied spectrum. */
    public synchronized boolean isOccupied(float offsetHz) {
        return !history.isEmpty() && clearanceAt(offsetHz) <= 0f;
    }

    /**
     * Pick the offset a fresh CQ should transmit on.
     *
     * @return the chosen offset, or {@code null} when no move is warranted:
     *     the current offset is already clear, there is no history to judge by
     *     (no information is not "occupied"), or nothing on the grid beats the
     *     current offset's clearance.
     */
    public synchronized Float selectClearOffset(float currentOffsetHz) {
        if (history.isEmpty()) {
            return null;
        }
        float currentClearance = clearanceAt(currentOffsetHz);
        if (currentClearance > 0f) {
            return null; // already clear: a gratuitous QSY loses spotted callers
        }
        Candidate best = bestCandidate();
        if (best == null || best.clearance <= currentClearance) {
            return null; // whole band as bad or worse: stay put
        }
        return best.offsetHz;
    }

    private static final class Candidate {
        final float offsetHz;
        final float clearance;

        Candidate(float offsetHz, float clearance) {
            this.offsetHz = offsetHz;
            this.clearance = clearance;
        }
    }

    /**
     * Best candidate on the grid: max clearance, ties broken toward the band
     * centre (1500 Hz, flat in every rig's passband), then toward the lower
     * offset for determinism.
     */
    private Candidate bestCandidate() {
        Candidate best = null;
        for (float f = minOffsetHz; f + SIGNAL_BANDWIDTH_HZ <= maxOffsetHz; f += stepHz) {
            float clearance = clearanceAt(f);
            if (best == null
                    || clearance > best.clearance
                    || (clearance == best.clearance
                        && Math.abs(f - PREFERRED_CENTER_HZ)
                            < Math.abs(best.offsetHz - PREFERRED_CENTER_HZ))) {
                best = new Candidate(f, clearance);
            }
        }
        return best;
    }

    /**
     * Retry/backoff gate for a CQ already in progress: relocate only when the
     * current offset has become occupied AND the last move is at least the
     * hold-down ago — hopping every cycle orphans answers already in flight.
     */
    public synchronized boolean shouldRelocate(float currentOffsetHz, long nowUtcMs) {
        if (!isOccupied(currentOffsetHz)) {
            return false;
        }
        return lastMoveUtcMs == Long.MIN_VALUE
                || nowUtcMs - lastMoveUtcMs >= moveHolddownMs;
    }

    /** Record that a move happened (starts the hold-down window). */
    public synchronized void noteMoved(long utcMs) {
        lastMoveUtcMs = utcMs;
    }
}
