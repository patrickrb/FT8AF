package com.k1af.ft8af.ft8listener;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.ModeProfile;

import org.junit.Test;

/**
 * Coverage for the late full-slot decode pass policy and buffer handoff (issue #363):
 * which cycles schedule the pass, how long the decode thread may wait for the full-slot
 * buffer, and the one-shot handoff between the recorder's monitor thread and the decode
 * thread.
 */
public class LateDecodeTest {

    // ---- shouldRunLatePass -------------------------------------------------------------

    @Test
    public void latePass_runs_forFt8WithEarlyDecodeOn() {
        assertThat(LateDecode.shouldRunLatePass(true, ModeProfile.FT8)).isTrue();
    }

    @Test
    public void latePass_skipped_whenEarlyDecodeOff() {
        // With earlyDecode off the primary decode already covers the whole slot; a second
        // full-slot pass would be a pure duplicate.
        assertThat(LateDecode.shouldRunLatePass(false, ModeProfile.FT8)).isFalse();
    }

    @Test
    public void latePass_skipped_forFt4AndFt2() {
        // Deliberate policy: the fast modes' early gaps (1.0s / 0.75s) are small relative
        // to the doubling of per-slot decode work, and their late threads would routinely
        // chain across several subsequent slots.
        assertThat(LateDecode.shouldRunLatePass(true, ModeProfile.FT4)).isFalse();
        assertThat(LateDecode.shouldRunLatePass(true, ModeProfile.FT2)).isFalse();
    }

    // ---- wait-bound math ---------------------------------------------------------------

    @Test
    public void deliveryDeadline_isSlotEndPlusGrace() {
        // Monitor registered at the slot boundary; buffer completes slotMillis later;
        // HamRecorder's stall watchdog force-delivers a partial at +2s; 3s covers both.
        assertThat(LateDecode.deliveryDeadlineMillis(1_000_000L, 15_000))
                .isEqualTo(1_000_000L + 15_000 + LateDecode.DELIVERY_GRACE_MS);
    }

    @Test
    public void remainingWait_countsDownToDeadline() {
        assertThat(LateDecode.remainingWaitMillis(1_018_000L, 1_016_500L)).isEqualTo(1_500L);
    }

    @Test
    public void remainingWait_neverNegative_pastDeadline() {
        // A decode thread that finishes its early passes after the deadline must not be
        // handed a negative timeout (poll would throw / wait forever depending on impl).
        assertThat(LateDecode.remainingWaitMillis(1_018_000L, 1_020_000L)).isEqualTo(0L);
        assertThat(LateDecode.remainingWaitMillis(1_018_000L, 1_018_000L)).isEqualTo(0L);
    }

    // ---- Handoff state machine -----------------------------------------------------------

    private static LateDecode.Handoff handoffWithLiveDeadline() {
        // Registered "now": deadline is ~18s away, so an offered buffer is returned
        // immediately and no test ever actually waits that long.
        return new LateDecode.Handoff(System.currentTimeMillis(), ModeProfile.FT8);
    }

    private static LateDecode.Handoff handoffWithExpiredDeadline() {
        // Registered far enough in the past that the deadline has already elapsed:
        // awaitBuffer must return null without blocking.
        return new LateDecode.Handoff(
                System.currentTimeMillis() - 15_000 - LateDecode.DELIVERY_GRACE_MS - 1_000,
                ModeProfile.FT8);
    }

    @Test
    public void handoff_offerThenAwait_deliversSameBuffer() {
        LateDecode.Handoff handoff = handoffWithLiveDeadline();
        float[] buffer = new float[]{0.1f, 0.2f};
        assertThat(handoff.offer(buffer)).isTrue();
        assertThat(handoff.awaitBuffer(System.currentTimeMillis())).isSameInstanceAs(buffer);
    }

    @Test
    public void handoff_isOneShot_secondOfferRejected() {
        LateDecode.Handoff handoff = handoffWithLiveDeadline();
        assertThat(handoff.offer(new float[]{1f})).isTrue();
        assertThat(handoff.offer(new float[]{2f})).isFalse();
    }

    @Test
    public void handoff_rejectsNullBuffer() {
        LateDecode.Handoff handoff = handoffWithLiveDeadline();
        assertThat(handoff.offer(null)).isFalse();
    }

    @Test
    public void handoff_await_pastDeadline_returnsNullWithoutBlocking() {
        // Capture stopped mid-slot: the buffer never arrives. The decode thread's wait is
        // bounded by the delivery deadline, so it gives up (and skips the late pass).
        LateDecode.Handoff handoff = handoffWithExpiredDeadline();
        long before = System.currentTimeMillis();
        assertThat(handoff.awaitBuffer(before)).isNull();
        // "Without blocking": far under the 18s a naive unbounded wait would burn.
        assertThat(System.currentTimeMillis() - before).isLessThan(1_000L);
    }

    @Test
    public void handoff_bufferOfferedBeforeLateAwait_stillDelivered() {
        // Early passes may finish AFTER the deadline (slow device, deep decode): a buffer
        // that already arrived must still be consumed even though the remaining wait is 0.
        LateDecode.Handoff handoff = handoffWithExpiredDeadline();
        float[] buffer = new float[]{0.5f};
        assertThat(handoff.offer(buffer)).isTrue();
        assertThat(handoff.awaitBuffer(System.currentTimeMillis())).isSameInstanceAs(buffer);
    }

    @Test
    public void handoff_awaitWhileInterrupted_returnsNullAndKeepsInterruptFlag() {
        LateDecode.Handoff handoff = handoffWithLiveDeadline();
        Thread.currentThread().interrupt();
        try {
            assertThat(handoff.awaitBuffer(System.currentTimeMillis())).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();// clear the flag so it can't leak into other tests
        }
    }

    // ---- planSlot: the record-time capture of the whole cycle's decode parameters ------
    // A mid-slot mode switch must not retroactively change how an in-flight slot decodes,
    // so EVERYTHING mode-derived is frozen into the SlotPlan at the slot boundary.

    @Test
    public void planSlot_ft8WithEarlyDecode_freezesModeEarlyWindowAndLateHandoff() {
        LateDecode.SlotPlan plan = LateDecode.planSlot(true, ModeProfile.FT8, 1_000_000L);
        assertThat(plan.mode).isEqualTo(ModeProfile.FT8);
        assertThat(plan.recordMillis).isEqualTo(ModeProfile.FT8.earlyDecodeMillis);
        assertThat(plan.lateHandoff).isNotNull();
    }

    @Test
    public void planSlot_earlyDecodeOff_recordsFullSlotWithNoLatePass() {
        // With early decode off the primary window already covers the whole slot; a late
        // pass would be a pure duplicate.
        LateDecode.SlotPlan plan = LateDecode.planSlot(false, ModeProfile.FT8, 1_000_000L);
        assertThat(plan.mode).isEqualTo(ModeProfile.FT8);
        assertThat(plan.recordMillis).isEqualTo(ModeProfile.FT8.slotMillis);
        assertThat(plan.lateHandoff).isNull();
    }

    @Test
    public void planSlot_fastModes_earlyWindowButNoLatePass() {
        // Matches shouldRunLatePass: FT4/FT2 keep a single early-window pass.
        for (ModeProfile mode : new ModeProfile[]{ModeProfile.FT4, ModeProfile.FT2}) {
            LateDecode.SlotPlan plan = LateDecode.planSlot(true, mode, 1_000_000L);
            assertThat(plan.mode).isEqualTo(mode);
            assertThat(plan.recordMillis).isEqualTo(mode.earlyDecodeMillis);
            assertThat(plan.lateHandoff).isNull();
        }
    }

    // ---- late-pass scheduling: the full-slot pass must coexist with the NEXT slot's
    // early decode instead of fighting it (the "decoders working against each other" bug:
    // the early buffer's subtraction loop delayed the late pass until it collided with
    // the next early decode and aborted its whole candidate scan).

    @Test
    public void latePassDeadline_endsBeforeNextSlotsEarlyDecodeStarts() {
        // Next slot's early decode thread starts at boundary + slot + earlyWindow; the
        // late pass must be done a safety margin before that.
        long registeredAt = 1_000_000L;
        long nextEarlyDecodeStart = registeredAt + ModeProfile.FT8.slotMillis
                + ModeProfile.FT8.earlyDecodeMillis;
        assertThat(LateDecode.latePassDeadlineMillis(registeredAt, ModeProfile.FT8))
                .isEqualTo(nextEarlyDecodeStart - LateDecode.LATE_PASS_SAFETY_MS);
    }

    @Test
    public void latePassDeadline_leavesUsefulDecodeWindow() {
        // Sanity on the constants: the window between buffer arrival (boundary + slot)
        // and the deadline must stay big enough for real deep work — if a future tuning
        // of earlyDecodeMillis/safety collapses it, the late pass silently degenerates
        // back into an abort-every-slot pass.
        long registeredAt = 0L;
        long bufferArrival = ModeProfile.FT8.slotMillis;
        long window = LateDecode.latePassDeadlineMillis(registeredAt, ModeProfile.FT8)
                - bufferArrival;
        assertThat(window).isAtLeast(10_000L);
    }

    @Test
    public void effectiveLatePassDeadline_isTheEarlierOfWindowAndBudget() {
        assertThat(LateDecode.effectiveLatePassDeadline(1_000L, 2_000L)).isEqualTo(1_000L);
        assertThat(LateDecode.effectiveLatePassDeadline(2_000L, 1_000L)).isEqualTo(1_000L);
        assertThat(LateDecode.effectiveLatePassDeadline(1_500L, 1_500L)).isEqualTo(1_500L);
    }

    @Test
    public void handoff_carriesLatePassDeadline() {
        long registeredAt = 1_000_000L;
        LateDecode.Handoff handoff = new LateDecode.Handoff(registeredAt, ModeProfile.FT8);
        assertThat(handoff.latePassDeadlineEpochMs)
                .isEqualTo(LateDecode.latePassDeadlineMillis(registeredAt, ModeProfile.FT8));
    }

    // ---- deep-loop placement: exactly one buffer per slot hosts the subtraction loop --

    @Test
    public void deepLoop_runsOnEarlyBuffer_whenNoLatePassScheduled() {
        // Early decode off (or FT4/FT2): the early buffer is the only buffer.
        assertThat(LateDecode.deepLoopRunsOnEarlyBuffer(null)).isTrue();
    }

    @Test
    public void deepLoop_deferredToFullSlotPass_whenLatePassScheduled() {
        // With a full-slot pass pending, deep work on the (strict-prefix) early buffer
        // would be duplicated AND delay the late pass into the next slot's early decode.
        LateDecode.SlotPlan plan = LateDecode.planSlot(true, ModeProfile.FT8, 1_000_000L);
        assertThat(LateDecode.deepLoopRunsOnEarlyBuffer(plan.lateHandoff)).isFalse();
    }

    @Test
    public void planSlot_handoffLatePassDeadline_anchoredAtPlanTime() {
        LateDecode.SlotPlan plan = LateDecode.planSlot(true, ModeProfile.FT8, 1_000_000L);
        assertThat(plan.lateHandoff.latePassDeadlineEpochMs)
                .isEqualTo(LateDecode.latePassDeadlineMillis(1_000_000L, ModeProfile.FT8));
    }

    @Test
    public void planSlot_handoffDeadline_anchoredAtPlanTime() {
        LateDecode.SlotPlan plan = LateDecode.planSlot(true, ModeProfile.FT8, 1_000_000L);
        // Past the plan-time-anchored deadline (slot end + grace) the wait is zero: a
        // decode thread that finishes its early passes very late must not block at all.
        long pastDeadline = 1_000_000L + ModeProfile.FT8.slotMillis
                + LateDecode.DELIVERY_GRACE_MS + 1;
        long before = System.currentTimeMillis();
        assertThat(plan.lateHandoff.awaitBuffer(pastDeadline)).isNull();
        assertThat(System.currentTimeMillis() - before).isLessThan(1_000L);
    }
}
