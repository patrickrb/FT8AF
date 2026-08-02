package com.k1af.ft8af.timer;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Arrays;

/**
 * Pure unit tests for {@link ClockSelfSync} — the self-syncing clock estimator
 * (median + MAD rejection + deadband + two-slot confirmation + proportional
 * gain). No Android types are touched (the GeneralVariables clamp bounds are
 * compile-time constants), so no Robolectric runner is needed.
 *
 * <p>Sign convention under test matches {@code suggestedCorrectionMs} in
 * {@code TimeCorrection.kt}: positive DT (decodes late in our window = clock
 * fast) must DECREASE the delay.
 */
public class ClockSelfSyncTest {

    // ------------------------------------------------------------------
    // medianOf
    // ------------------------------------------------------------------

    @Test
    public void median_oddLength_returnsMiddleValue() {
        assertThat(ClockSelfSync.medianOf(new float[]{0.3f, 0.1f, 0.2f})).isEqualTo(0.2f);
        assertThat(ClockSelfSync.medianOf(new float[]{5f})).isEqualTo(5f);
    }

    @Test
    public void median_evenLength_returnsMeanOfMiddleTwo() {
        assertThat(ClockSelfSync.medianOf(new float[]{0.1f, 0.2f, 0.3f, 0.4f}))
                .isWithin(1e-6f).of(0.25f);
        assertThat(ClockSelfSync.medianOf(new float[]{2f, 1f})).isWithin(1e-6f).of(1.5f);
    }

    @Test
    public void median_empty_isZero() {
        assertThat(ClockSelfSync.medianOf(new float[0])).isEqualTo(0f);
        assertThat(ClockSelfSync.medianOf(null)).isEqualTo(0f);
    }

    @Test
    public void median_doesNotMutateInput() {
        float[] input = {0.3f, 0.1f, 0.2f};
        ClockSelfSync.medianOf(input);
        assertThat(input).usingExactEquality().containsExactly(0.3f, 0.1f, 0.2f).inOrder();
    }

    // ------------------------------------------------------------------
    // MAD-based outlier rejection
    // ------------------------------------------------------------------

    @Test
    public void rejectOutliers_dropsFarOutlier_keepsTightCluster() {
        // Cluster near 0.5 s plus one wild -3 s decode (e.g. a wrong-slot signal).
        float[] dt = {0.48f, 0.50f, 0.52f, 0.49f, -3.0f};
        float[] survivors = ClockSelfSync.rejectOutliers(dt);
        assertThat(survivors.length).isEqualTo(4);
        for (float s : survivors) {
            assertThat(s).isGreaterThan(0.4f);
        }
    }

    @Test
    public void rejectOutliers_identicalSamples_allSurviveViaMadFloor() {
        // MAD == 0 for identical values; without the 0.2 s floor everything but
        // exact matches of the median would be rejected on real (jittery) data.
        float[] dt = {0.7f, 0.7f, 0.7f, 0.7f};
        assertThat(ClockSelfSync.rejectOutliers(dt).length).isEqualTo(4);
    }

    @Test
    public void rejectOutliers_jitterWithinFloor_allSurvive() {
        // Spread of ±0.15 s around the median is inside the 0.2 s floor even
        // though 3xMAD alone would be tighter.
        float[] dt = {0.55f, 0.60f, 0.65f, 0.70f, 0.75f};
        assertThat(ClockSelfSync.rejectOutliers(dt).length).isEqualTo(5);
    }

    @Test
    public void rejectOutliers_empty_returnsEmpty() {
        assertThat(ClockSelfSync.rejectOutliers(new float[0]).length).isEqualTo(0);
        assertThat(ClockSelfSync.rejectOutliers(null).length).isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // onSlotDecodes: gates
    // ------------------------------------------------------------------

    /** A slot of {@code n} identical DTs (survives rejection unchanged). */
    private static float[] slot(float dtSec, int n) {
        float[] a = new float[n];
        Arrays.fill(a, dtSec);
        return a;
    }

    @Test
    public void fewerThanMinSamples_returnsNull() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.0f, ClockSelfSync.MIN_SLOT_SAMPLES - 1), 0))
                .isNull();
        assertThat(sync.onSlotDecodes(new float[0], 0)).isNull();
    }

    @Test
    public void sparseSlot_doesNotTouchConfirmationState() {
        ClockSelfSync sync = new ClockSelfSync();
        // Slot 1: qualifying out-of-deadband slot starts the streak.
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNull();
        // Slot 2: too few samples — must NOT reset the streak.
        assertThat(sync.onSlotDecodes(slot(1.0f, 2), 0)).isNull();
        // Slot 3: same sign again — confirmation completes, correction applied.
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNotNull();
    }

    @Test
    public void medianInsideDeadband_returnsNullAndResetsStreak() {
        ClockSelfSync sync = new ClockSelfSync();
        // Start a streak with an out-of-deadband slot...
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNull();
        // ...then a healthy slot (|median| <= 0.30) clears it...
        assertThat(sync.onSlotDecodes(slot(0.25f, 4), 0)).isNull();
        // ...so the next qualifying slot is a FIRST slot again (null), not a second.
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNull();
        // And only its confirmation acts.
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNotNull();
    }

    @Test
    public void deadbandBoundary_isInclusive() {
        ClockSelfSync sync = new ClockSelfSync();
        // Exactly 0.30 s is still "good" (matches CLOCK_SYNC_GOOD_SEC semantics).
        assertThat(sync.onSlotDecodes(slot(ClockSelfSync.DEADBAND_SEC, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(ClockSelfSync.DEADBAND_SEC, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(-ClockSelfSync.DEADBAND_SEC, 4), 0)).isNull();
    }

    // ------------------------------------------------------------------
    // onSlotDecodes: hysteresis
    // ------------------------------------------------------------------

    @Test
    public void firstQualifyingSlot_isNull_secondSameSign_steps() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.0f, 5), 0)).isNull();
        Integer applied = sync.onSlotDecodes(slot(1.0f, 5), 0);
        assertThat(applied).isNotNull();
        // GAIN 0.5: 1.0 s median -> -500 ms step from a 0 baseline.
        assertThat(applied).isEqualTo(-500);
    }

    @Test
    public void oppositeSignSecondSlot_restartsStreak() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNull();  // +streak = 1
        assertThat(sync.onSlotDecodes(slot(-1.0f, 4), 0)).isNull(); // flip: -streak = 1
        // Confirming the NEW (negative) direction acts; sign is the negative one's.
        Integer applied = sync.onSlotDecodes(slot(-1.0f, 4), 0);
        assertThat(applied).isEqualTo(500);
    }

    @Test
    public void afterApplying_streakResets_soNextCorrectionNeedsTwoMoreSlots() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNotNull(); // applied
        // Immediately after an apply, one more qualifying slot is not enough.
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), -500)).isNull();
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), -500)).isNotNull();
    }

    // ------------------------------------------------------------------
    // onSlotDecodes: step math, sign convention, clamp
    // ------------------------------------------------------------------

    @Test
    public void stepMath_halvesTheMeasuredError_matchingSuggestedCorrectionSign() {
        ClockSelfSync sync = new ClockSelfSync();
        // Positive DT (clock fast) => delay DECREASES — same direction as
        // suggestedCorrectionMs(currentDelayMs, avgDtSec), at half magnitude.
        sync.onSlotDecodes(slot(0.8f, 4), 200);
        assertThat(sync.onSlotDecodes(slot(0.8f, 4), 200))
                .isEqualTo(200 - Math.round(0.8f * 1000f * ClockSelfSync.GAIN)); // = -200
        // Negative DT (clock slow) => delay INCREASES.
        sync.reset();
        sync.onSlotDecodes(slot(-0.6f, 4), -100);
        assertThat(sync.onSlotDecodes(slot(-0.6f, 4), -100)).isEqualTo(-100 + 300);
    }

    @Test
    public void stepUsesMedianOfSurvivors_notTheRawMean() {
        ClockSelfSync sync = new ClockSelfSync();
        // One -3 s outlier among a 1.0 s cluster: the mean would be dragged to
        // 0.2 s (inside the deadband!), but MAD rejection + median stays at 1.0 s.
        float[] withOutlier = {1.0f, 1.0f, 1.0f, 1.0f, -3.0f};
        assertThat(sync.onSlotDecodes(withOutlier, 0)).isNull();
        assertThat(sync.onSlotDecodes(withOutlier, 0)).isEqualTo(-500);
    }

    @Test
    public void newDelay_clampsAtPlusMinus5000() {
        ClockSelfSync sync = new ClockSelfSync();
        sync.onSlotDecodes(slot(4.0f, 4), -4000);
        // -4000 - 2000 = -6000 -> clamped to -5000.
        assertThat(sync.onSlotDecodes(slot(4.0f, 4), -4000)).isEqualTo(-5000);
        sync.reset();
        sync.onSlotDecodes(slot(-4.0f, 4), 4000);
        // 4000 + 2000 = 6000 -> clamped to +5000.
        assertThat(sync.onSlotDecodes(slot(-4.0f, 4), 4000)).isEqualTo(5000);
    }

    // ------------------------------------------------------------------
    // Convergence property
    // ------------------------------------------------------------------

    @Test
    public void convergence_from1500msOff_reachesDeadbandMonotonically() {
        ClockSelfSync sync = new ClockSelfSync();
        // trueErrorMs: how far the app clock is from the band. Each slot's
        // measured DT is exactly that error (consistent signals); each applied
        // correction shifts the clock and therefore the next slot's DT.
        int delayMs = 0;
        float trueErrorSec = 1.5f; // clock 1500 ms fast: decodes land at +1.5 s DT
        float prevAbs = Math.abs(trueErrorSec);
        int slots = 0;
        while (Math.abs(trueErrorSec) > ClockSelfSync.DEADBAND_SEC && slots < 20) {
            slots++;
            Integer newDelay = sync.onSlotDecodes(slot(trueErrorSec, 6), delayMs);
            if (newDelay != null) {
                // The applied correction moves the clock; the band's apparent DT
                // shrinks by the same amount.
                trueErrorSec -= (delayMs - newDelay) / 1000f;
                delayMs = newDelay;
                // Monotonic approach: never overshoots into a larger |error|.
                assertThat(Math.abs(trueErrorSec)).isLessThan(prevAbs);
                prevAbs = Math.abs(trueErrorSec);
            }
        }
        // 1500 -> 750 -> 375 -> 187.5 ms: three corrections, two slots each.
        assertThat(Math.abs(trueErrorSec)).isAtMost(ClockSelfSync.DEADBAND_SEC);
        assertThat(slots).isAtMost(6);
    }

    // ------------------------------------------------------------------
    // beginSlot / reset
    // ------------------------------------------------------------------

    @Test
    public void beginSlot_trueOncePerUtc() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.beginSlot(15_000L)).isTrue();
        assertThat(sync.beginSlot(15_000L)).isFalse(); // same slot redelivered
        assertThat(sync.beginSlot(30_000L)).isTrue();  // next slot
    }

    @Test
    public void reset_clearsStreakAndSlotTracking() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.beginSlot(15_000L)).isTrue();
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNull(); // streak = 1
        sync.reset();
        // Streak gone: the next qualifying slot is a first slot again.
        assertThat(sync.onSlotDecodes(slot(1.0f, 4), 0)).isNull();
        // Slot tracking gone: the same utc is processable again.
        assertThat(sync.beginSlot(15_000L)).isTrue();
    }
}
