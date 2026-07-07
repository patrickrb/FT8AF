package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for the CAT liveness watchdog decision logic. Pure JUnit — no Android.
 */
public class CatLivenessTest {

    private static final long TIMEOUT = CatLiveness.DEFAULT_TIMEOUT_MS;

    // ---- shouldProbe --------------------------------------------------------

    @Test
    public void probe_onlyWhenConnectedAndNotTransmitting() {
        assertThat(CatLiveness.shouldProbe(true, false)).isTrue();
        assertThat(CatLiveness.shouldProbe(false, false)).isFalse(); // not connected
        assertThat(CatLiveness.shouldProbe(true, true)).isFalse();   // mid-transmit
        assertThat(CatLiveness.shouldProbe(false, true)).isFalse();
    }

    // ---- shouldRearmAfterTx -------------------------------------------------

    @Test
    public void rearm_onlyOnTxToRxEdge() {
        // Falling edge: was transmitting last tick, no longer transmitting -> re-arm.
        assertThat(CatLiveness.shouldRearmAfterTx(true, false)).isTrue();
        // Still transmitting -> nothing to re-arm yet.
        assertThat(CatLiveness.shouldRearmAfterTx(true, true)).isFalse();
        // Steady receive (never transmitting) -> no edge.
        assertThat(CatLiveness.shouldRearmAfterTx(false, false)).isFalse();
        // Rising edge (RX->TX) -> not our concern; only the falling edge re-arms.
        assertThat(CatLiveness.shouldRearmAfterTx(false, true)).isFalse();
    }

    @Test
    public void rearm_preventsFalseStaleAfterLongTransmit() {
        // Reproduces the FT8 red-chip bug: an over is 12.64s, longer than the 8s timeout, so
        // lastResponseMs is frozen ~13s in the past when TX ends. Without a re-arm the first
        // post-TX tick would judge the rig stale...
        long now = 100_000;
        long frozenDuringTx = now - 13_000;
        assertThat(CatLiveness.isRigStale(true, false, true, now, frozenDuringTx, TIMEOUT))
                .isTrue();
        // ...but on the TX->RX edge we re-arm the timestamp to "now", after which the same tick
        // no longer sees the rig as stale.
        assertThat(CatLiveness.shouldRearmAfterTx(true, false)).isTrue();
        long rearmed = now; // re-arm sets lastResponseMs = now
        assertThat(CatLiveness.isRigStale(true, false, true, now, rearmed, TIMEOUT)).isFalse();
    }

    // ---- isRigStale ---------------------------------------------------------

    @Test
    public void stale_whenResponsiveRigGoesQuietPastTimeout() {
        // Armed (saw a reply), connected, idle, and silent for longer than the timeout.
        assertThat(CatLiveness.isRigStale(true, false, true,
                100_000, 100_000 - (TIMEOUT + 1), TIMEOUT)).isTrue();
    }

    @Test
    public void notStale_withinTimeout() {
        assertThat(CatLiveness.isRigStale(true, false, true,
                100_000, 100_000 - (TIMEOUT - 1), TIMEOUT)).isFalse();
        // Exactly at the boundary is not yet stale (strictly greater-than).
        assertThat(CatLiveness.isRigStale(true, false, true,
                100_000, 100_000 - TIMEOUT, TIMEOUT)).isFalse();
    }

    @Test
    public void neverStale_beforeFirstResponse() {
        // The arming guard: a rig that has never echoed a frequency read (sawResponse=false)
        // must not be flagged dead, even after a long silence. This is what stops the
        // watchdog from false-positiving rigs/transports that don't answer freq queries.
        assertThat(CatLiveness.isRigStale(true, false, false,
                100_000, 0, TIMEOUT)).isFalse();
    }

    @Test
    public void neverStale_whileTransmitting() {
        // We don't poll during TX, so we must not judge silence as death then.
        assertThat(CatLiveness.isRigStale(true, true, true,
                100_000, 0, TIMEOUT)).isFalse();
    }

    @Test
    public void neverStale_whenDisconnected() {
        assertThat(CatLiveness.isRigStale(false, false, true,
                100_000, 0, TIMEOUT)).isFalse();
    }
}
