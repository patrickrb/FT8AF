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

    /** Fixed epoch-like instant for the burst-timing cases. */
    private static final long T0 = 1_700_000_000_000L;

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
                Kind.TRANSIENT, CatReconnectPolicy.BACKOFF_ESCALATION_ATTEMPTS - 1)).isTrue();
    }

    @Test
    public void autoReconnect_transientRetriesWithoutLimit() {
        // The budget was dropped deliberately: giving up strands the operator with no CAT
        // until they notice the retry chip. Containment comes from the backoff escalating
        // to MAX_BACKOFF_MS and staying there, not from stopping.
        assertThat(CatReconnectPolicy.shouldAutoReconnect(
                Kind.TRANSIENT, CatReconnectPolicy.BACKOFF_ESCALATION_ATTEMPTS)).isTrue();
        assertThat(CatReconnectPolicy.shouldAutoReconnect(Kind.TRANSIENT, 1_000)).isTrue();
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
    public void decide_transientKeepsReconnectingPastTheOldBudget() {
        assertThat(CatReconnectPolicy.decide(
                false, Kind.TRANSIENT, CatReconnectPolicy.BACKOFF_ESCALATION_ATTEMPTS))
                .isEqualTo(CatReconnectPolicy.Action.RECONNECT);
    }

    // ---- shouldResetBurst: the reconnect-storm fix -------------------------

    @Test
    public void burst_neverConnected_resets() {
        assertThat(CatReconnectPolicy.shouldResetBurst(T0, 0L)).isTrue();
    }

    @Test
    public void burst_portThatDiesImmediatelyDoesNotReset() {
        // THE bug. CableConnector treated connect() returning true as success and reset
        // the escalation there, so a port that opened and immediately errored restarted
        // the burst at attempt 1 every time — backoff pinned at BASE_BACKOFF_MS (500 ms).
        // Measured result: 13,190 port opens in 88 minutes, inter-arrival 0.51-0.53 s,
        // and the give-up path never reached once.
        assertThat(CatReconnectPolicy.shouldResetBurst(T0 + 30, T0)).isFalse();
        assertThat(CatReconnectPolicy.shouldResetBurst(T0 + 530, T0)).isFalse();
    }

    @Test
    public void burst_connectionThatHeldResets() {
        assertThat(CatReconnectPolicy.shouldResetBurst(
                T0 + CatReconnectPolicy.STABLE_CONNECTION_MS, T0)).isTrue();
    }

    @Test
    public void burst_justUnderStableDoesNotReset() {
        assertThat(CatReconnectPolicy.shouldResetBurst(
                T0 + CatReconnectPolicy.STABLE_CONNECTION_MS - 1, T0)).isFalse();
    }

    @Test
    public void burst_backwardsClockDoesNotFakeStability() {
        // System.currentTimeMillis() is not monotonic and this app disciplines its own
        // clock; a backwards correction must not make a 30 ms connection look stable.
        assertThat(CatReconnectPolicy.shouldResetBurst(T0 - 60_000, T0)).isFalse();
    }

    @Test
    public void escalationConstantMatchesWhenTheCeilingIsReached() {
        // BACKOFF_ESCALATION_ATTEMPTS is no longer a give-up budget, so it only earns its
        // place by describing something real: the attempt at which backoff hits the
        // ceiling. Pin that, or the name drifts from the behaviour again.
        assertThat(CatReconnectPolicy.backoffMs(CatReconnectPolicy.BACKOFF_ESCALATION_ATTEMPTS))
                .isEqualTo(CatReconnectPolicy.MAX_BACKOFF_MS);
        assertThat(CatReconnectPolicy.backoffMs(CatReconnectPolicy.BACKOFF_ESCALATION_ATTEMPTS - 1))
                .isLessThan(CatReconnectPolicy.MAX_BACKOFF_MS);
    }

    @Test
    public void burst_escalationReachesTheCeilingQuickly() {
        // With the counter persisting, a flapping link walks up to the ceiling in a
        // handful of attempts instead of sitting at the first step forever.
        assertThat(CatReconnectPolicy.backoffMs(1)).isEqualTo(CatReconnectPolicy.BASE_BACKOFF_MS);
        assertThat(CatReconnectPolicy.backoffMs(5)).isEqualTo(CatReconnectPolicy.MAX_BACKOFF_MS);
        assertThat(CatReconnectPolicy.backoffMs(50)).isEqualTo(CatReconnectPolicy.MAX_BACKOFF_MS);
    }
}
