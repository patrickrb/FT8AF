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
        return new LateDecode.Handoff(System.currentTimeMillis(), 15_000);
    }

    private static LateDecode.Handoff handoffWithExpiredDeadline() {
        // Registered far enough in the past that the deadline has already elapsed:
        // awaitBuffer must return null without blocking.
        return new LateDecode.Handoff(
                System.currentTimeMillis() - 15_000 - LateDecode.DELIVERY_GRACE_MS - 1_000,
                15_000);
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
}
