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
 * <p><b>Acquire fast, track gently.</b> A clock that is plainly off (|median| &gt;
 * {@link #ACQUIRE_SEC}, confirmed by a well-populated slot) is corrected in full
 * at once — that is the "it just works" moment after launch. Inside that band
 * the loop tracks: each applied correction shifts the next slot's measured DT,
 * and the decoder's DT is quantised (~80 ms), so a proportional gain
 * ({@link #GAIN}) halves the residual per step rather than chasing noise. There
 * is deliberately NO hard cap on step size: see the long comment in
 * {@code GpsClockUpdater} (a step limiter shipped there once and locked in a bad
 * baseline for a whole activation — proportional gain always converges, a step
 * cap can refuse the truth forever).
 *
 * <p>Robustness per slot: a median (not mean) over the slot's DTs, MAD-based
 * outlier rejection on top of it, a deadband, and consecutive-slot same-sign
 * confirmation before any correction is applied. A slot with few surviving
 * decodes (a quiet POTA band, one station calling CQ) still counts as evidence
 * — it just needs one more confirming slot than a well-populated one, because a
 * single station's DT is also that station's clock error.
 *
 * <p>The deadband is deliberately tighter than the UI's "good clock" threshold
 * (0.30 s): the estimator used to stop there, which parked the clock a quarter
 * second off and left the operator re-applying the manual suggestion every few
 * minutes. Driving the residual under {@link #DEADBAND_SEC} makes the auto-sync
 * toggle actually replace that chore.
 *
 * <p>Pure Java, no Android imports (the {@link GeneralVariables} references are
 * {@code static final int} constant expressions, inlined at compile time), so
 * this is unit-testable without Robolectric. Public entry points are
 * synchronized: decode passes for adjacent slots can deliver concurrently.
 */
public class ClockSelfSync {

    /** Minimum surviving (post-outlier-rejection) decodes for a slot to count. */
    public static final int MIN_SLOT_SAMPLES = 1;

    /**
     * Surviving decodes at/above which a slot is "well populated": the median is
     * a real consensus and {@link #CONFIRM_SLOTS} slots are enough. Below this a
     * slot is "sparse" and needs {@link #SPARSE_CONFIRM_SLOTS}.
     */
    public static final int FULL_CONFIDENCE_SAMPLES = 4;

    /**
     * |median DT| at/below this (seconds) is left alone. Half the 0.30 s the
     * clock-health indicator ({@code CLOCK_SYNC_GOOD_SEC} in
     * {@code ui/components/ClockSync.kt}) calls "good", so auto-sync lands the
     * clock comfortably inside "good" rather than right on its edge. Two DT
     * measurement steps (the decoder's time oversampling is 80 ms), so it does
     * not chase measurement noise.
     */
    public static final float DEADBAND_SEC = 0.15f;

    /**
     * |median DT| above this (seconds) means the clock is plainly off — not a
     * tracking residual but an acquisition problem (fresh launch, phone drifted
     * offline). Acquisition is fast and decisive: a well-populated slot acts on
     * its own (no second slot to confirm) and the full error is removed in one
     * step ({@link #ACQUIRE_GAIN}). Half a second is well clear of both the
     * deadband and the measurement noise, so a single consensus of four or more
     * stations that far out cannot be a fluke.
     */
    public static final float ACQUIRE_SEC = 0.5f;

    /** Gain used while acquiring (|median| &gt; {@link #ACQUIRE_SEC}): take the whole error. */
    public static final float ACQUIRE_GAIN = 1.0f;

    /**
     * Proportional gain applied to a tracking-band error (|median| within
     * {@link #ACQUIRE_SEC}): halves the residual per step so the loop cannot
     * ring on the decoder's ~80 ms DT quantisation.
     */
    public static final float GAIN = 0.5f;

    /**
     * Consecutive qualifying same-sign slots required before a correction is
     * applied, when the confirming slot is well populated.
     */
    public static final int CONFIRM_SLOTS = 2;

    /**
     * Consecutive qualifying same-sign slots required when the confirming slot
     * is sparse (fewer than {@link #FULL_CONFIDENCE_SAMPLES} survivors). One
     * extra slot of agreement before trusting what may be a single station.
     */
    public static final int SPARSE_CONFIRM_SLOTS = 3;

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
     * once per slot, and only for slots NEWER than the last processed one —
     * decode threads for adjacent slots run concurrently, so a slow slot's
     * delivery can arrive after its successor's; accepting it then would feed
     * stale evidence into the confirmation streak. Monotonic rejection makes
     * late stragglers a no-op.
     */
    public synchronized boolean beginSlot(long utc) {
        if (utc <= lastProcessedUtc) {
            return false;
        }
        lastProcessedUtc = utc;
        return true;
    }

    /**
     * Atomic per-slot entry point: slot dedup/ordering ({@link #beginSlot}) and
     * the correction decision ({@link #onSlotDecodes}) under one lock, so two
     * decode threads delivering different slots cannot interleave between the
     * dedup check and the streak update. Production code must use this; the
     * two-step methods stay public for targeted unit tests.
     */
    public synchronized Integer onSlot(long utc, float[] dtSec, int currentDelayMs) {
        if (!beginSlot(utc)) {
            return null;
        }
        return onSlotDecodes(dtSec, currentDelayMs);
    }

    /**
     * Consecutive same-sign slots needed before a slot with {@code survivors}
     * post-rejection decodes and median {@code medianSec} may apply a correction.
     * A well-populated slot that is plainly off ({@link #ACQUIRE_SEC}) acts at
     * once; a well-populated tracking slot needs one confirming slot; a sparse
     * slot always needs two.
     */
    static int confirmSlotsFor(int survivors, float medianSec) {
        if (survivors < FULL_CONFIDENCE_SAMPLES) return SPARSE_CONFIRM_SLOTS;
        return Math.abs(medianSec) > ACQUIRE_SEC ? 1 : CONFIRM_SLOTS;
    }

    /** Gain for a confirmed error: the whole thing while acquiring, half while tracking. */
    static float gainFor(float medianSec) {
        return Math.abs(medianSec) > ACQUIRE_SEC ? ACQUIRE_GAIN : GAIN;
    }

    /**
     * Per-slot decision. Feeds one slot's decode DTs (seconds) through outlier
     * rejection, the sample-count gate, the deadband, and the consecutive-slot
     * same-sign confirmation ({@link #confirmSlotsFor}), and returns the NEW
     * total clock delay (ms) to apply — or null for no action this slot.
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
        if (streak < confirmSlotsFor(survivors.length, median)) {
            return null;
        }
        int newDelay = clampDelayMs(
                currentDelayMs - Math.round(median * 1000f * gainFor(median)));
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
