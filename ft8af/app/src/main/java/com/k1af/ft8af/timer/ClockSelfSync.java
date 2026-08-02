package com.k1af.ft8af.timer;

import com.k1af.ft8af.GeneralVariables;

import java.util.Arrays;

/**
 * Self-syncing clock: estimates the local clock error from the per-decode time
 * offsets (WSJT-style DT, seconds) of the stations we hear each slot, and decides
 * when — and by how much — to trim the app's global clock offset
 * ({@link UtcTimer#delay}). The band itself becomes the time source: no NTP or
 * GPS needed.
 *
 * <p><b>Sign convention</b> (matches {@code suggestedCorrectionMs} in
 * {@code TimeCorrection.kt}): a positive median DT means decodes land late in our
 * RX window — our clock is fast — so the correction <em>subtracts</em> from the
 * current delay. Negative DT adds. The goal is to drive the measured DT toward 0.
 *
 * <p><b>Damping, not step-limiting.</b> Each applied correction shifts the next
 * slot's measured DT, so a full-step correction would ring. A proportional gain
 * ({@link #GAIN}) halves the error per step instead. There is deliberately NO
 * hard cap on step size: see the long comment in {@code GpsClockUpdater} (a step
 * limiter shipped there once and locked in a bad baseline for a whole activation
 * — proportional gain always converges, a step cap can refuse the truth forever).
 *
 * <p>Robustness per slot: a median (not mean) over the slot's DTs, MAD-based
 * outlier rejection on top of it, a minimum surviving-sample count, a deadband
 * matching the UI's "good clock" threshold, and two-consecutive-slot same-sign
 * confirmation before any correction is applied.
 *
 * <p>Pure Java, no Android imports (the {@link GeneralVariables} references are
 * {@code static final int} constant expressions, inlined at compile time), so
 * this is unit-testable without Robolectric. Public entry points are
 * synchronized: decode passes for adjacent slots can deliver concurrently.
 */
public class ClockSelfSync {

    /** Minimum surviving (post-outlier-rejection) decodes for a slot to count. */
    public static final int MIN_SLOT_SAMPLES = 4;

    /**
     * |median DT| at/below this (seconds) is left alone. Matches
     * {@code CLOCK_SYNC_GOOD_SEC} in {@code ui/components/ClockSync.kt} — the
     * threshold the clock-health indicator already calls "good".
     */
    public static final float DEADBAND_SEC = 0.30f;

    /** Proportional gain applied to the measured error (damps the feedback loop). */
    public static final float GAIN = 0.5f;

    /** Consecutive qualifying same-sign slots required before a correction is applied. */
    public static final int CONFIRM_SLOTS = 2;

    /** Absolute floor (seconds) for the MAD-based rejection threshold. */
    static final float MAD_FLOOR_SEC = 0.2f;

    /** Rejection threshold is max(floor, this multiple of the MAD). */
    static final float MAD_MULTIPLIER = 3f;

    // Confirmation streak: how many consecutive qualifying slots have agreed, and
    // the sign (+1/-1) they agreed on. 0 sign = no streak.
    private int streak = 0;
    private int streakSign = 0;

    // Slot dedup: afterDecode fires several times per slot (fast, deep, late
    // passes); only the first qualifying delivery per slot utc may be sampled.
    private long lastProcessedUtc = Long.MIN_VALUE;

    /**
     * Median of {@code a}; the mean of the two middle values for even lengths.
     * An empty (or null) array yields 0 — callers gate on sample count anyway.
     */
    public static float medianOf(float[] a) {
        if (a == null || a.length == 0) {
            return 0f;
        }
        float[] sorted = a.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2f;
    }

    /**
     * MAD-based outlier rejection: samples farther than
     * max({@link #MAD_FLOOR_SEC}, {@link #MAD_MULTIPLIER} x MAD) from the median
     * are dropped. The floor keeps a tight cluster (MAD near 0) from rejecting
     * everything but the exact median value.
     */
    static float[] rejectOutliers(float[] dtSec) {
        if (dtSec == null || dtSec.length == 0) {
            return new float[0];
        }
        float median = medianOf(dtSec);
        float[] deviations = new float[dtSec.length];
        for (int i = 0; i < dtSec.length; i++) {
            deviations[i] = Math.abs(dtSec[i] - median);
        }
        float mad = medianOf(deviations);
        float threshold = Math.max(MAD_FLOOR_SEC, MAD_MULTIPLIER * mad);
        int kept = 0;
        float[] survivors = new float[dtSec.length];
        for (float dt : dtSec) {
            if (Math.abs(dt - median) <= threshold) {
                survivors[kept++] = dt;
            }
        }
        return Arrays.copyOf(survivors, kept);
    }

    /** Clamp to the shared manual-correction range (±5000 ms). */
    static int clampDelayMs(int ms) {
        return Math.max(GeneralVariables.MANUAL_TIME_CORRECTION_MIN_MS,
                Math.min(GeneralVariables.MANUAL_TIME_CORRECTION_MAX_MS, ms));
    }

    /**
     * Mark the slot identified by {@code utc} as processed. Returns true exactly
     * once per slot — callers must only sample DTs (and call
     * {@link #onSlotDecodes}) when this returns true, so the multiple decode
     * passes of one slot cannot be double-counted.
     */
    public synchronized boolean beginSlot(long utc) {
        if (utc == lastProcessedUtc) {
            return false;
        }
        lastProcessedUtc = utc;
        return true;
    }

    /**
     * Per-slot decision. Feeds one slot's decode DTs (seconds) through outlier
     * rejection, the sample-count gate, the deadband, and the two-slot same-sign
     * confirmation, and returns the NEW total clock delay (ms) to apply — or
     * null for no action this slot.
     *
     * @param dtSec          this slot's per-decode DTs (seconds), own-TX echoes
     *                       already filtered out
     * @param currentDelayMs the live {@link UtcTimer#delay} at decision time
     * @return the new clamped delay to fan out, or null to do nothing
     */
    public synchronized Integer onSlotDecodes(float[] dtSec, int currentDelayMs) {
        float[] survivors = rejectOutliers(dtSec);
        if (survivors.length < MIN_SLOT_SAMPLES) {
            // Too little evidence — leave the confirmation state untouched so a
            // sparse slot doesn't break up a real streak.
            return null;
        }
        float median = medianOf(survivors);
        if (Math.abs(median) <= DEADBAND_SEC) {
            // Clock is healthy; a streak that was building was noise.
            streak = 0;
            streakSign = 0;
            return null;
        }
        int sign = median > 0 ? 1 : -1;
        if (sign != streakSign) {
            // First qualifying slot in this direction (or a direction flip):
            // start/restart the streak, act only if a later slot confirms.
            streakSign = sign;
            streak = 1;
        } else {
            streak++;
        }
        if (streak < CONFIRM_SLOTS) {
            return null;
        }
        int newDelay = clampDelayMs(
                currentDelayMs - Math.round(median * 1000f * GAIN));
        streak = 0;
        streakSign = 0;
        return newDelay;
    }

    /**
     * Clears the confirmation streak and the last-processed-slot tracking.
     * Called when the feature is toggled off or GPS clock discipline takes over.
     */
    public synchronized void reset() {
        streak = 0;
        streakSign = 0;
        lastProcessedUtc = Long.MIN_VALUE;
    }
}
