package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link PttSafetyLatch}, the "we owe this rig an unkey" flag.
 *
 * <p>Field failure it exists for (POTA, 2026-07-23): an OTG brown-out
 * re-enumerated the USB bus mid-transmission, the CAT PTT-off write went to a
 * port that no longer existed, and the rig stayed keyed — 97s in one case, 89s
 * in another where the failing write also killed the process. The port itself
 * was back within ~2s each time, so the latch's job is to remember the
 * outstanding unkey across the outage and settle it on reconnect.
 *
 * <p>Plain JUnit: pure state, no Android types.
 */
public class PttSafetyLatchTest {

    @Test
    public void startsWithNothingOwed() {
        assertThat(new PttSafetyLatch().needsUnkey()).isFalse();
    }

    @Test
    public void keyingOwesAnUnkey() {
        PttSafetyLatch latch = new PttSafetyLatch();
        latch.onKeyed();
        assertThat(latch.needsUnkey()).isTrue();
    }

    @Test
    public void confirmedUnkeySettlesTheDebt() {
        PttSafetyLatch latch = new PttSafetyLatch();
        latch.onKeyed();
        latch.onUnkeyAttempted(true);
        assertThat(latch.needsUnkey()).isFalse();
    }

    @Test
    public void failedUnkeyKeepsTheDebt() {
        // The exact field case: PTT-off written to a port that had already gone.
        PttSafetyLatch latch = new PttSafetyLatch();
        latch.onKeyed();
        latch.onUnkeyAttempted(false);
        assertThat(latch.needsUnkey()).isTrue();
    }

    @Test
    public void retryAfterFailureCanSettleIt() {
        // Reconnect path: the port came back, so the retry lands.
        PttSafetyLatch latch = new PttSafetyLatch();
        latch.onKeyed();
        latch.onUnkeyAttempted(false);
        latch.onUnkeyAttempted(false);
        assertThat(latch.needsUnkey()).isTrue();
        latch.onUnkeyAttempted(true);
        assertThat(latch.needsUnkey()).isFalse();
    }

    @Test
    public void unkeyWithoutKeyingIsHarmless() {
        // Slot-boundary backstop fires on every cycle, keyed or not.
        PttSafetyLatch latch = new PttSafetyLatch();
        latch.onUnkeyAttempted(true);
        assertThat(latch.needsUnkey()).isFalse();
        latch.onUnkeyAttempted(false);
        assertThat(latch.needsUnkey()).isFalse();
    }

    @Test
    public void rekeyingAfterAFailedUnkeyStillOwesOne() {
        // Transmission N's unkey was lost, transmission N+1 keys again: still one
        // debt outstanding, not zero.
        PttSafetyLatch latch = new PttSafetyLatch();
        latch.onKeyed();
        latch.onUnkeyAttempted(false);
        latch.onKeyed();
        assertThat(latch.needsUnkey()).isTrue();
        latch.onUnkeyAttempted(true);
        assertThat(latch.needsUnkey()).isFalse();
    }

    @Test
    public void resetDropsTheDebtWithoutSending() {
        // Teardown: the rig object is going away, so a stale latch must not chase
        // a rig we no longer drive.
        PttSafetyLatch latch = new PttSafetyLatch();
        latch.onKeyed();
        latch.reset();
        assertThat(latch.needsUnkey()).isFalse();
    }
}
