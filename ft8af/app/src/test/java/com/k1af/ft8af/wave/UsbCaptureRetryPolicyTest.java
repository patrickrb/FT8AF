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
}
