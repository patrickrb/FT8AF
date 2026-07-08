package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.CatReconnectPolicy.Kind;

import org.junit.Test;

import java.io.IOException;

/**
 * Pure-JVM tests for {@link CatReconnectPolicy} — the transient/fatal
 * classification, bounded auto-reconnect backoff, and PTT fail-safe retry that
 * keep a single CAT glitch from stranding the user on the manual retry chip.
 */
public class CatReconnectPolicyTest {

    // ---- classify -----------------------------------------------------------

    @Test
    public void bareIoException_isTransient() {
        assertThat(CatReconnectPolicy.classify(new IOException("read failed")))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    public void nullExceptionOrNullMessage_isTransient() {
        assertThat(CatReconnectPolicy.classify(null)).isEqualTo(Kind.TRANSIENT);
        assertThat(CatReconnectPolicy.classify(new IOException()))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    public void deviceGoneMessages_areFatal() {
        assertThat(CatReconnectPolicy.classify(new IOException("ENODEV: No such device")))
                .isEqualTo(Kind.FATAL);
        assertThat(CatReconnectPolicy.classify(new IOException("USB get_status request failed, no device")))
                .isEqualTo(Kind.FATAL);
        assertThat(CatReconnectPolicy.classify(new IOException("device not found")))
                .isEqualTo(Kind.FATAL);
        assertThat(CatReconnectPolicy.classify(new IOException("Permission denied")))
                .isEqualTo(Kind.FATAL);
        assertThat(CatReconnectPolicy.classify(new IOException("connection was disconnected")))
                .isEqualTo(Kind.FATAL);
    }

    // ---- shouldAutoReconnect ------------------------------------------------

    @Test
    public void autoReconnect_transientWithinBudget_allowed() {
        assertThat(CatReconnectPolicy.shouldAutoReconnect(Kind.TRANSIENT, 0)).isTrue();
        assertThat(CatReconnectPolicy.shouldAutoReconnect(
                Kind.TRANSIENT, CatReconnectPolicy.MAX_AUTO_RECONNECT_ATTEMPTS - 1)).isTrue();
    }

    @Test
    public void autoReconnect_budgetExhausted_denied() {
        assertThat(CatReconnectPolicy.shouldAutoReconnect(
                Kind.TRANSIENT, CatReconnectPolicy.MAX_AUTO_RECONNECT_ATTEMPTS)).isFalse();
    }

    @Test
    public void autoReconnect_fatal_neverRetries() {
        assertThat(CatReconnectPolicy.shouldAutoReconnect(Kind.FATAL, 0)).isFalse();
    }

    // ---- backoffMs ----------------------------------------------------------

    @Test
    public void backoff_isExponential() {
        assertThat(CatReconnectPolicy.backoffMs(1))
                .isEqualTo(CatReconnectPolicy.BASE_BACKOFF_MS);       // 500ms
        assertThat(CatReconnectPolicy.backoffMs(2))
                .isEqualTo(CatReconnectPolicy.BASE_BACKOFF_MS * 2);   // 1s
        assertThat(CatReconnectPolicy.backoffMs(3))
                .isEqualTo(CatReconnectPolicy.BASE_BACKOFF_MS * 4);   // 2s
        assertThat(CatReconnectPolicy.backoffMs(4))
                .isEqualTo(CatReconnectPolicy.BASE_BACKOFF_MS * 8);   // 4s
    }

    @Test
    public void backoff_isCappedAndNeverOverflows() {
        assertThat(CatReconnectPolicy.backoffMs(20))
                .isEqualTo(CatReconnectPolicy.MAX_BACKOFF_MS);
        assertThat(CatReconnectPolicy.backoffMs(Integer.MAX_VALUE))
                .isEqualTo(CatReconnectPolicy.MAX_BACKOFF_MS);
    }

    @Test
    public void backoff_nonPositiveAttempt_isZero() {
        assertThat(CatReconnectPolicy.backoffMs(0)).isEqualTo(0);
        assertThat(CatReconnectPolicy.backoffMs(-3)).isEqualTo(0);
    }

    // ---- shouldRetryPtt (fail-safe) -----------------------------------------

    @Test
    public void pttOff_failed_retriedWithinBudget() {
        assertThat(CatReconnectPolicy.shouldRetryPtt(false, false, 0)).isTrue();
        assertThat(CatReconnectPolicy.shouldRetryPtt(
                false, false, CatReconnectPolicy.MAX_PTT_OFF_RETRIES - 1)).isTrue();
    }

    @Test
    public void pttOff_budgetExhausted_notRetried() {
        assertThat(CatReconnectPolicy.shouldRetryPtt(
                false, false, CatReconnectPolicy.MAX_PTT_OFF_RETRIES)).isFalse();
    }

    @Test
    public void pttOff_succeeded_notRetried() {
        assertThat(CatReconnectPolicy.shouldRetryPtt(false, true, 0)).isFalse();
    }

    @Test
    public void pttOn_failed_notRetried() {
        // Only PTT-off is fail-safe retried; a failed key-up is left to the
        // QSO sequencer.
        assertThat(CatReconnectPolicy.shouldRetryPtt(true, false, 0)).isFalse();
    }

    // ---- decide (error → action) --------------------------------------------

    @Test
    public void decide_userDisconnected_isIgnored() {
        // A deliberate user disconnect closes the port and unblocks the read with
        // an expected IOException — it must not surface as a "Lost connection".
        assertThat(CatReconnectPolicy.decide(true, Kind.TRANSIENT, 0))
                .isEqualTo(CatReconnectPolicy.Action.IGNORE);
        assertThat(CatReconnectPolicy.decide(true, Kind.FATAL, 0))
                .isEqualTo(CatReconnectPolicy.Action.IGNORE);
    }

    @Test
    public void decide_transientWithBudget_reconnects() {
        assertThat(CatReconnectPolicy.decide(false, Kind.TRANSIENT, 0))
                .isEqualTo(CatReconnectPolicy.Action.RECONNECT);
    }

    @Test
    public void decide_fatal_surfaces() {
        assertThat(CatReconnectPolicy.decide(false, Kind.FATAL, 0))
                .isEqualTo(CatReconnectPolicy.Action.SURFACE);
    }

    @Test
    public void decide_transientBudgetExhausted_surfaces() {
        assertThat(CatReconnectPolicy.decide(
                false, Kind.TRANSIENT, CatReconnectPolicy.MAX_AUTO_RECONNECT_ATTEMPTS))
                .isEqualTo(CatReconnectPolicy.Action.SURFACE);
    }
}
