package com.k1af.ft8af.timer;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Arrays;

/**
 * Pure unit tests for {@link ClockSelfSync} — the self-syncing clock estimator
 * (median + MAD rejection + deadband + slot confirmation + acquire/track gain).
 * No Android types are touched (the GeneralVariables clamp bounds are
 * compile-time constants), so no Robolectric runner is needed.
 *
 * <p>Sign convention under test matches {@code suggestedCorrectionMs} in
 * {@code TimeCorrection.kt}: positive DT (decodes late in our window = clock
 * fast) must DECREASE the delay.
 *
 * <p>Two regimes: <b>acquisition</b> (|median| &gt; 0.5 s — a well-populated slot
 * acts alone and removes the whole error) and <b>tracking</b> (0.15–0.5 s —
 * two agreeing slots, half the error per step). Sparse slots (&lt; 4 survivors)
 * always need three agreeing slots.
 */
public class ClockSelfSyncTest {

    // ---- mayRun: the decode-time gate shared with the settings lock-out ----

    @Test
    public void mayRun_onlyWhenEnabledAndNothingAuthoritativeOwnsTheClock() {
        assertThat(ClockSelfSync.mayRun(true, false, false)).isTrue();
        assertThat(ClockSelfSync.mayRun(false, false, false)).isFalse();
    }

    @Test
    public void mayRun_standsDownForGpsDiscipline() {
        assertThat(ClockSelfSync.mayRun(true, true, false)).isFalse();
    }

    @Test
    public void mayRun_standsDownForNtpDiscipline() {
        // NTP rewrites UtcTimer.delay on its own schedule; a second writer would fight it.
        assertThat(ClockSelfSync.mayRun(true, false, true)).isFalse();
        assertThat(ClockSelfSync.mayRun(true, true, true)).isFalse();
    }

    /** A slot of {@code n} identical DTs (survives rejection unchanged). */
    private static float[] slot(float dtSec, int n) {
        float[] a = new float[n];
        Arrays.fill(a, dtSec);
        return a;
    }

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
    // Confirmation / gain policy
    // ------------------------------------------------------------------

    @Test
    public void confirmSlotsFor_fullSlotPlainlyOff_actsAlone() {
        assertThat(ClockSelfSync.confirmSlotsFor(ClockSelfSync.FULL_CONFIDENCE_SAMPLES, 1.0f))
                .isEqualTo(1);
        assertThat(ClockSelfSync.confirmSlotsFor(12, -2.0f)).isEqualTo(1);
    }

    @Test
    public void confirmSlotsFor_fullSlotTracking_needsTwo() {
        assertThat(ClockSelfSync.confirmSlotsFor(ClockSelfSync.FULL_CONFIDENCE_SAMPLES, 0.4f))
                .isEqualTo(ClockSelfSync.CONFIRM_SLOTS);
        // Exactly the acquire threshold is still tracking.
        assertThat(ClockSelfSync.confirmSlotsFor(10, ClockSelfSync.ACQUIRE_SEC))
                .isEqualTo(ClockSelfSync.CONFIRM_SLOTS);
    }

    @Test
    public void confirmSlotsFor_sparseSlot_needsThreeRegardlessOfMagnitude() {
        assertThat(ClockSelfSync.confirmSlotsFor(ClockSelfSync.FULL_CONFIDENCE_SAMPLES - 1, 0.4f))
                .isEqualTo(ClockSelfSync.SPARSE_CONFIRM_SLOTS);
        assertThat(ClockSelfSync.confirmSlotsFor(1, 3.0f))
                .isEqualTo(ClockSelfSync.SPARSE_CONFIRM_SLOTS);
    }

    @Test
    public void gainFor_fullWhileAcquiring_halfWhileTracking() {
        assertThat(ClockSelfSync.gainFor(1.0f)).isEqualTo(ClockSelfSync.ACQUIRE_GAIN);
        assertThat(ClockSelfSync.gainFor(-0.6f)).isEqualTo(ClockSelfSync.ACQUIRE_GAIN);
        assertThat(ClockSelfSync.gainFor(0.4f)).isEqualTo(ClockSelfSync.GAIN);
        assertThat(ClockSelfSync.gainFor(ClockSelfSync.ACQUIRE_SEC)).isEqualTo(ClockSelfSync.GAIN);
    }

    // ------------------------------------------------------------------
    // onSlotDecodes: gates
    // ------------------------------------------------------------------

    @Test
    public void fewerThanMinSamples_returnsNull() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.0f, ClockSelfSync.MIN_SLOT_SAMPLES - 1), 0))
                .isNull();
        assertThat(sync.onSlotDecodes(new float[0], 0)).isNull();
        assertThat(sync.onSlotDecodes(null, 0)).isNull();
    }

    @Test
    public void emptySlot_doesNotTouchConfirmationState() {
        ClockSelfSync sync = new ClockSelfSync();
        // Slot 1: qualifying tracking slot starts the streak.
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();
        // Slot 2: nothing decoded — must NOT reset the streak.
        assertThat(sync.onSlotDecodes(new float[0], 0)).isNull();
        // Slot 3: same sign again — confirmation completes, correction applied.
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNotNull();
    }

    @Test
    public void medianInsideDeadband_returnsNullAndResetsStreak() {
        ClockSelfSync sync = new ClockSelfSync();
        // Start a streak with an out-of-deadband tracking slot...
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();
        // ...then a healthy slot (|median| <= 0.15) clears it...
        assertThat(sync.onSlotDecodes(slot(0.10f, 4), 0)).isNull();
        // ...so the next qualifying slot is a FIRST slot again (null), not a second.
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();
        // And only its confirmation acts.
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNotNull();
    }

    @Test
    public void deadbandBoundary_isInclusive() {
        ClockSelfSync sync = new ClockSelfSync();
        // Exactly 0.15 s is still "good".
        assertThat(sync.onSlotDecodes(slot(ClockSelfSync.DEADBAND_SEC, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(ClockSelfSync.DEADBAND_SEC, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(-ClockSelfSync.DEADBAND_SEC, 4), 0)).isNull();
    }

    @Test
    public void quarterSecondResidual_isNoLongerLeftAlone() {
        // The field complaint: the old 0.30 s deadband parked the clock ~0.25 s
        // off and the operator kept applying the manual suggestion. 0.25 s must
        // now be corrected (tracking: two slots, half step).
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(0.25f, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.25f, 4), 0)).isEqualTo(-125);
    }

    // ------------------------------------------------------------------
    // Acquisition: plainly off + well populated => one slot, full step
    // ------------------------------------------------------------------

    @Test
    public void fullSlotPlainlyOff_correctsInFullOnTheFirstSlot() {
        // Fresh launch with the phone 1.2 s fast and six stations agreeing: the
        // "it just works" moment — no waiting for a second slot, no halving.
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.2f, 6), 0)).isEqualTo(-1200);
    }

    @Test
    public void acquisition_sign_matchesSuggestedCorrection() {
        ClockSelfSync sync = new ClockSelfSync();
        // Negative DT (clock slow) => delay INCREASES, full step.
        assertThat(sync.onSlotDecodes(slot(-0.8f, 4), -100)).isEqualTo(-100 + 800);
    }

    @Test
    public void acquisition_thenResidualIsTracked() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.0f, 5), 0)).isEqualTo(-1000);
        // Residual 0.2 s after the step: tracking rules, two slots, half gain.
        assertThat(sync.onSlotDecodes(slot(0.2f, 5), -1000)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.2f, 5), -1000)).isEqualTo(-1100);
    }

    @Test
    public void acquisition_doesNotFireOnASparseSlot() {
        // One station 1.0 s out is not a consensus: sparse rules apply.
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.0f, 3), 0)).isNull();
    }

    @Test
    public void acquisition_usesMedianOfSurvivors_notTheRawMean() {
        ClockSelfSync sync = new ClockSelfSync();
        // One -3 s outlier among a 1.0 s cluster: the mean would be dragged to
        // 0.2 s (tracking band!), but MAD rejection + median stays at 1.0 s.
        float[] withOutlier = {1.0f, 1.0f, 1.0f, 1.0f, -3.0f};
        assertThat(sync.onSlotDecodes(withOutlier, 0)).isEqualTo(-1000);
    }

    // ------------------------------------------------------------------
    // Tracking: two agreeing full slots, half step
    // ------------------------------------------------------------------

    @Test
    public void trackingSlot_firstIsNull_secondSameSign_steps() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(0.4f, 5), 0)).isNull();
        Integer applied = sync.onSlotDecodes(slot(0.4f, 5), 0);
        assertThat(applied).isNotNull();
        // GAIN 0.5: 0.4 s median -> -200 ms step from a 0 baseline.
        assertThat(applied).isEqualTo(-200);
    }

    @Test
    public void oppositeSignSecondSlot_restartsStreak() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();  // +streak = 1
        assertThat(sync.onSlotDecodes(slot(-0.4f, 4), 0)).isNull(); // flip: -streak = 1
        // Confirming the NEW (negative) direction acts; sign is the negative one's.
        Integer applied = sync.onSlotDecodes(slot(-0.4f, 4), 0);
        assertThat(applied).isEqualTo(200);
    }

    @Test
    public void afterApplying_streakResets_soNextCorrectionNeedsTwoMoreSlots() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNotNull(); // applied
        // Immediately after an apply, one more qualifying slot is not enough.
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), -200)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), -200)).isNotNull();
    }

    @Test
    public void trackingStepMath_halvesTheMeasuredError() {
        ClockSelfSync sync = new ClockSelfSync();
        // Positive DT (clock fast) => delay DECREASES — same direction as
        // suggestedCorrectionMs(currentDelayMs, avgDtSec), at half magnitude.
        sync.onSlotDecodes(slot(0.3f, 4), 200);
        assertThat(sync.onSlotDecodes(slot(0.3f, 4), 200))
                .isEqualTo(200 - Math.round(0.3f * 1000f * ClockSelfSync.GAIN)); // = 50
        // Negative DT (clock slow) => delay INCREASES.
        sync.reset();
        sync.onSlotDecodes(slot(-0.4f, 4), -100);
        assertThat(sync.onSlotDecodes(slot(-0.4f, 4), -100)).isEqualTo(-100 + 200);
    }

    // ------------------------------------------------------------------
    // Sparse slots (quiet band): they count, but need three agreeing slots
    // ------------------------------------------------------------------

    @Test
    public void singleStationEverySlot_correctsAfterThreeAgreeingSlots_fullStepWhenOff() {
        // The POTA case that never used to sync: one station heard per slot,
        // clock 1.0 s out. Three agreeing slots, then the whole error goes.
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(1.0f, 1), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(1.0f, 1), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(1.0f, 1), 0)).isEqualTo(-1000);
    }

    @Test
    public void singleStationEverySlot_trackingResidual_halfStepAfterThree() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(0.4f, 1), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.4f, 1), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.4f, 1), 0)).isEqualTo(-200);
    }

    @Test
    public void sparseThenFullSlot_fullSlotConfirmsOnItsOwnRule() {
        // A sparse first slot starts the streak; a well-populated tracking slot
        // is a real consensus and may confirm with the normal two-slot rule.
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(0.4f, 2), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isEqualTo(-200);
    }

    @Test
    public void fullThenSparseSlot_sparseSlotWaitsForAThird() {
        // Reverse order: the sparse slot is the one asking to act, so the
        // stricter rule applies to it.
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.4f, 2), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.4f, 2), 0)).isEqualTo(-200);
    }

    @Test
    public void sparseSlotOfOppositeSign_restartsStreak() {
        // A lone station disagreeing with the previous slot is not ignored as
        // "too few samples" — it restarts the streak like any other slot.
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(-0.4f, 1), 0)).isNull();
        // Back to positive: first slot of a new streak, not a confirmation.
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isEqualTo(-200);
    }

    // ------------------------------------------------------------------
    // Clamp
    // ------------------------------------------------------------------

    @Test
    public void newDelay_clampsAtPlusMinus5000() {
        ClockSelfSync sync = new ClockSelfSync();
        // Acquisition full step: -4000 - 4000 = -8000 -> clamped to -5000.
        assertThat(sync.onSlotDecodes(slot(4.0f, 4), -4000)).isEqualTo(-5000);
        sync.reset();
        // 4000 + 4000 = 8000 -> clamped to +5000.
        assertThat(sync.onSlotDecodes(slot(-4.0f, 4), 4000)).isEqualTo(5000);
    }

    // ------------------------------------------------------------------
    // Convergence property
    // ------------------------------------------------------------------

    /**
     * Drive the estimator from {@code startErrorSec} with {@code samplesPerSlot}
     * consistent decodes per slot until |error| is inside the deadband; returns
     * the number of slots it took. Each applied correction moves the clock, so
     * the band's apparent DT shrinks by the same amount. Asserts the approach
     * never overshoots into a larger |error|.
     */
    private static int slotsToConverge(float startErrorSec, int samplesPerSlot) {
        ClockSelfSync sync = new ClockSelfSync();
        int delayMs = 0;
        float trueErrorSec = startErrorSec;
        float prevAbs = Math.abs(trueErrorSec);
        int slots = 0;
        while (Math.abs(trueErrorSec) > ClockSelfSync.DEADBAND_SEC && slots < 30) {
            slots++;
            Integer newDelay = sync.onSlotDecodes(slot(trueErrorSec, samplesPerSlot), delayMs);
            if (newDelay != null) {
                trueErrorSec -= (delayMs - newDelay) / 1000f;
                delayMs = newDelay;
                assertThat(Math.abs(trueErrorSec)).isAtMost(prevAbs);
                prevAbs = Math.abs(trueErrorSec);
            }
        }
        assertThat(Math.abs(trueErrorSec)).isAtMost(ClockSelfSync.DEADBAND_SEC);
        return slots;
    }

    @Test
    public void convergence_busyBand_1500msOff_isFixedInOneSlot() {
        assertThat(slotsToConverge(1.5f, 6)).isEqualTo(1);
    }

    @Test
    public void convergence_busyBand_400msOff_isFixedInTwoSlots() {
        // Tracking: 400 -> 200 -> 100 ms. The first half-step lands at 200 ms,
        // still outside the 150 ms deadband, so a second confirmed step is
        // needed: two half-steps of two slots each.
        assertThat(slotsToConverge(0.4f, 6)).isEqualTo(4);
    }

    @Test
    public void convergence_quietBand_1000msOff_isFixedInThreeSlots() {
        // One station per slot: three agreeing slots, then the full error goes.
        assertThat(slotsToConverge(1.0f, 1)).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // beginSlot / onSlot / reset
    // ------------------------------------------------------------------

    @Test
    public void beginSlot_trueOncePerUtc() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.beginSlot(15_000L)).isTrue();
        assertThat(sync.beginSlot(15_000L)).isFalse(); // same slot redelivered
        assertThat(sync.beginSlot(30_000L)).isTrue();  // next slot
    }

    @Test
    public void beginSlot_rejectsOlderSlotArrivingLate() {
        // Adjacent-slot decode threads run concurrently: a slow slot's delivery
        // can land AFTER its successor's. Monotonic dedup must reject it.
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.beginSlot(30_000L)).isTrue();
        assertThat(sync.beginSlot(15_000L)).isFalse(); // straggler from the past
        assertThat(sync.beginSlot(45_000L)).isTrue();
    }

    @Test
    public void onSlot_redeliveredSlot_isIgnoredEvenWithQualifyingSamples() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlot(15_000L, slot(0.4f, 4), 0)).isNull(); // streak = 1
        // Redelivery of the same slot must not advance the streak to a step.
        assertThat(sync.onSlot(15_000L, slot(0.4f, 4), 0)).isNull();
        // The genuine second slot confirms and steps: -median*1000*GAIN.
        assertThat(sync.onSlot(30_000L, slot(0.4f, 4), 0)).isEqualTo(-200);
    }

    @Test
    public void onSlot_lateOlderSlot_cannotAffectStreak() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlot(30_000L, slot(0.4f, 4), 0)).isNull(); // streak = 1
        // A stale slot from the past, even with opposite-sign evidence that
        // would reset the streak, is rejected by the monotonic dedup.
        assertThat(sync.onSlot(15_000L, slot(-0.4f, 4), 0)).isNull();
        assertThat(sync.onSlot(45_000L, slot(0.4f, 4), 0)).isEqualTo(-200);
    }

    @Test
    public void onSlot_acquisitionAlsoDedupesRedelivery() {
        // A redelivered acquisition slot must not apply the full step twice.
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.onSlot(15_000L, slot(1.0f, 4), 0)).isEqualTo(-1000);
        assertThat(sync.onSlot(15_000L, slot(1.0f, 4), -1000)).isNull();
    }

    @Test
    public void reset_clearsStreakAndSlotTracking() {
        ClockSelfSync sync = new ClockSelfSync();
        assertThat(sync.beginSlot(15_000L)).isTrue();
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull(); // streak = 1
        sync.reset();
        // Streak gone: the next qualifying slot is a first slot again.
        assertThat(sync.onSlotDecodes(slot(0.4f, 4), 0)).isNull();
        // Slot tracking gone: the same utc is processable again.
        assertThat(sync.beginSlot(15_000L)).isTrue();
    }
}
