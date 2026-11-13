package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Pure-JVM tests for {@link UsbCaptureRetryPolicy}. */
public class UsbCaptureRetryPolicyTest {

    @Test
    public void isFailure_noDataEver_isFailure() {
        // The car-dash case: requestWait/iso dies immediately, no samples.
        assertThat(UsbCaptureRetryPolicy.isFailure(false, 0)).isTrue();
        assertThat(UsbCaptureRetryPolicy.isFailure(false, 10_000)).isTrue();
    }

    @Test
    public void isFailure_sawDataButDiedTooQuickly_isFailure() {
        // The Pixel 8 + C-Media field case: a little audio, then all transfers
        // retire ~100ms in. Must count as a failure so the churn breaks.
        assertThat(UsbCaptureRetryPolicy.isFailure(true, 100)).isTrue();
        assertThat(UsbCaptureRetryPolicy.isFailure(
                true, UsbCaptureRetryPolicy.MIN_USEFUL_SESSION_MS - 1)).isTrue();
    }

    @Test
    public void isFailure_sawDataAndStayedAlive_isNotFailure() {
        assertThat(UsbCaptureRetryPolicy.isFailure(
                true, UsbCaptureRetryPolicy.MIN_USEFUL_SESSION_MS)).isFalse();
        assertThat(UsbCaptureRetryPolicy.isFailure(true, 60_000)).isFalse();
    }

    @Test
    public void backoff_isExponential() {
        assertThat(UsbCaptureRetryPolicy.backoffMs(1))
                .isEqualTo(UsbCaptureRetryPolicy.BASE_BACKOFF_MS);          // 2s
        assertThat(UsbCaptureRetryPolicy.backoffMs(2))
                .isEqualTo(UsbCaptureRetryPolicy.BASE_BACKOFF_MS * 2);      // 4s
        assertThat(UsbCaptureRetryPolicy.backoffMs(3))
                .isEqualTo(UsbCaptureRetryPolicy.BASE_BACKOFF_MS * 4);      // 8s
        assertThat(UsbCaptureRetryPolicy.backoffMs(4))
                .isEqualTo(UsbCaptureRetryPolicy.BASE_BACKOFF_MS * 8);      // 16s
    }

    @Test
    public void backoff_isCappedAndNeverOverflows() {
        assertThat(UsbCaptureRetryPolicy.backoffMs(10))
                .isEqualTo(UsbCaptureRetryPolicy.MAX_BACKOFF_MS);
        // A pathological count must not overflow the shift into a negative/huge delay.
        assertThat(UsbCaptureRetryPolicy.backoffMs(1000))
                .isEqualTo(UsbCaptureRetryPolicy.MAX_BACKOFF_MS);
        assertThat(UsbCaptureRetryPolicy.backoffMs(Integer.MAX_VALUE))
                .isEqualTo(UsbCaptureRetryPolicy.MAX_BACKOFF_MS);
    }

    @Test
    public void backoff_nonPositiveCount_isZero() {
        assertThat(UsbCaptureRetryPolicy.backoffMs(0)).isEqualTo(0);
        assertThat(UsbCaptureRetryPolicy.backoffMs(-5)).isEqualTo(0);
    }

    // ---- isRetryableFailure ------------------------------------------------

    @Test
    public void cleanStop_isNotRetryable() {
        // code 0 = a nativeStop WE requested (reinit / band change / stopRecord /
        // teardown). Must NOT drive a retry — that self-kill loop starved the
        // decoder in the field (429 of 434 stops were clean stops).
        assertThat(UsbCaptureRetryPolicy.isRetryableFailure(0)).isFalse();
    }

    @Test
    public void genuineFailures_areRetryable() {
        // Every non-zero native stop reason is a real capture death and must retry.
        assertThat(UsbCaptureRetryPolicy.isRetryableFailure(1)).isTrue();       // transfers retired
        assertThat(UsbCaptureRetryPolicy.isRetryableFailure(2004)).isTrue();    // resubmit NO_DEVICE
        assertThat(UsbCaptureRetryPolicy.isRetryableFailure(1005)).isTrue();    // transfer NO_DEVICE
        assertThat(UsbCaptureRetryPolicy.isRetryableFailure(3110)).isTrue();    // handle_events failed
        assertThat(UsbCaptureRetryPolicy.isRetryableFailure(
                UsbAudioDevice.CAPTURE_STOP_FALLBACK_FAILURE)).isTrue();        // UsbRequest fallback died
    }

    // ---- failureTallyAfter -------------------------------------------------

    @Test
    public void tally_incrementsOnFailure() {
        // A too-short session bumps the running count.
        assertThat(UsbCaptureRetryPolicy.failureTallyAfter(0, true, 100)).isEqualTo(1);
        assertThat(UsbCaptureRetryPolicy.failureTallyAfter(3, false, 0)).isEqualTo(4);
    }

    @Test
    public void tally_resetsOnHealthySession() {
        // A session that stayed alive long enough clears the streak, no matter
        // how high it had climbed.
        assertThat(UsbCaptureRetryPolicy.failureTallyAfter(
                7, true, UsbCaptureRetryPolicy.MIN_USEFUL_SESSION_MS)).isEqualTo(0);
        assertThat(UsbCaptureRetryPolicy.failureTallyAfter(1, true, 60_000)).isEqualTo(0);
    }

    // ---- planReinit --------------------------------------------------------

    @Test
    public void plan_firstFailure_backsOffBaseDelay() {
        UsbCaptureRetryPolicy.ReinitPlan plan =
                UsbCaptureRetryPolicy.planReinit(true, 100, 0);
        assertThat(plan.consecutiveFailures).isEqualTo(1);
        assertThat(plan.backoffMs).isEqualTo(UsbCaptureRetryPolicy.BASE_BACKOFF_MS);
    }

    @Test
    public void plan_repeatedFailures_growExponentiallyUpToCap() {
        // The Pixel 8 + C-Media field mode: session after session dies ~100ms in.
        UsbCaptureRetryPolicy.ReinitPlan second =
                UsbCaptureRetryPolicy.planReinit(true, 100, 1);
        assertThat(second.consecutiveFailures).isEqualTo(2);
        assertThat(second.backoffMs).isEqualTo(UsbCaptureRetryPolicy.BASE_BACKOFF_MS * 2);

        UsbCaptureRetryPolicy.ReinitPlan many =
                UsbCaptureRetryPolicy.planReinit(false, 0, 99);
        assertThat(many.consecutiveFailures).isEqualTo(100);
        assertThat(many.backoffMs).isEqualTo(UsbCaptureRetryPolicy.MAX_BACKOFF_MS);
    }

    @Test
    public void plan_healthySession_resetsTallyAndReinitsImmediately() {
        // A session that carried real audio clears the streak and re-arms with
        // no backoff, so a genuine transient stop doesn't stall capture.
        UsbCaptureRetryPolicy.ReinitPlan plan =
                UsbCaptureRetryPolicy.planReinit(true, 30_000, 5);
        assertThat(plan.consecutiveFailures).isEqualTo(0);
        assertThat(plan.backoffMs).isEqualTo(0);
    }

    // ---- tallyAfterCleanStop -----------------------------------------------

    @Test
    public void cleanStop_afterHealthySession_clearsStaleStreak() {
        // A long, healthy session ended by a band change / teardown proves the
        // device works, so a stale failure streak from earlier is cleared.
        assertThat(UsbCaptureRetryPolicy.tallyAfterCleanStop(
                4, true, UsbCaptureRetryPolicy.MIN_USEFUL_SESSION_MS)).isEqualTo(0);
        assertThat(UsbCaptureRetryPolicy.tallyAfterCleanStop(9, true, 60_000)).isEqualTo(0);
    }

    @Test
    public void cleanStop_afterShortOrSilentSession_leavesStreakUnchanged() {
        // A clean stop is never a failure (no increment), but a session that was
        // too short or delivered no audio hasn't proven the device healthy, so an
        // existing streak must be preserved rather than reset.
        assertThat(UsbCaptureRetryPolicy.tallyAfterCleanStop(3, true, 100)).isEqualTo(3);
        assertThat(UsbCaptureRetryPolicy.tallyAfterCleanStop(3, false, 60_000)).isEqualTo(3);
        // Never increments, even from a short session.
        assertThat(UsbCaptureRetryPolicy.tallyAfterCleanStop(0, true, 100)).isEqualTo(0);
    }
}
