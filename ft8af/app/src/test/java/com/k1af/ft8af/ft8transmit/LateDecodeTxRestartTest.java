package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link FT8TransmitSignal#shouldRestartForNewOrder} — the mid-cycle
 * message swap that closes the late-decode TX race.
 *
 * <p>The race, measured from a real POTA activation: the fast pass delivers ~13.5 s into
 * the slot, the auto-sequencer keys up ~0.45 s into the next one, and the late full-slot
 * pass (issue #363) then delivers its recovered decodes 0&ndash;3.5 s into that next slot.
 * When one of those recovered decodes is the partner's reply, the sequencer advances the
 * over just after key-up — in the logged case by 9 ms — and the whole cycle is spent
 * re-sending the previous message instead of the RR73 that would have completed the QSO.
 *
 * <p>The swap is free inside the audio slack (FT8: 2360 ms) because the replayed message
 * still fits the slot complete; past it the new message could not fit without clipping its
 * leading Costas sync array, which is the one thing that must never happen.
 *
 * <p>Pure JVM: the predicate is static and touches no Android types.
 */
public class LateDecodeTxRestartTest {

    /** FT8: 15000 ms slot, 12640 ms of audio. */
    private static final int FT8_SLACK_MS = 2360;
    /** FT4: 7500 ms slot, 5040 ms of audio. */
    private static final int FT4_SLACK_MS = 2460;

    // ---------------------------------------------------------------
    // The case this exists for
    // ---------------------------------------------------------------

    @Test
    public void lateDecodeJustAfterKeyUp_restarts() {
        // The logged 17:50 K5UUT case: keyed up at order 3 (R-10), the late pass
        // advanced to order 2 about half a second into the slot.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 2, 507, FT8_SLACK_MS)).isTrue();
    }

    @Test
    public void lateDecodeAtTheVeryStartOfTheSlot_restarts() {
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 4, 0, FT8_SLACK_MS)).isTrue();
    }

    @Test
    public void lateDecodeWellInsideTheSlack_restarts() {
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 2, 3, 1500, FT8_SLACK_MS)).isTrue();
    }

    // ---------------------------------------------------------------
    // The slack boundary — a restart past it would clip the new message
    // ---------------------------------------------------------------

    @Test
    public void restartExactlyAtTheHeadroomLimit_stillRestarts() {
        int lastSafe = FT8_SLACK_MS - FT8TransmitSignal.RESTART_HEADROOM_MS;
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 4, lastSafe, FT8_SLACK_MS)).isTrue();
    }

    @Test
    public void oneMsPastTheHeadroomLimit_doesNotRestart() {
        int firstUnsafe = FT8_SLACK_MS - FT8TransmitSignal.RESTART_HEADROOM_MS + 1;
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 4, firstUnsafe, FT8_SLACK_MS)).isFalse();
    }

    @Test
    public void restartInsideRawSlackButInsideHeadroom_doesNotRestart() {
        // 2300 ms is within the 2360 ms slack, but the swap itself would push the
        // replay past it — exactly the clipping this guard exists to prevent.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 4, 2300, FT8_SLACK_MS)).isFalse();
    }

    @Test
    public void lateDecodeWellPastTheSlack_doesNotRestart() {
        // The 2.5-3.5 s arrivals: unsaveable, let the original over finish.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 4, 3000, FT8_SLACK_MS)).isFalse();
    }

    @Test
    public void ft4UsesItsOwnSlack() {
        // FT4's slack is wider than FT8's, so a swap FT8 would refuse is fine here.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 4, 2200, FT4_SLACK_MS)).isTrue();
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 4, 2200, FT8_SLACK_MS)).isFalse();
    }

    // ---------------------------------------------------------------
    // Cases that must never restart
    // ---------------------------------------------------------------

    @Test
    public void notTransmitting_doesNotRestart() {
        // The ordinary case: the late pass advanced the sequencer between overs.
        // Nothing is on the air, so the next key-up already sends the right message.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                false, false, 3, 4, 500, FT8_SLACK_MS)).isFalse();
    }

    @Test
    public void orderUnchanged_doesNotRestart() {
        // The overwhelmingly common late-pass outcome: decodes arrive, none of them
        // advance the QSO. Restarting here would swap the message for an identical
        // one and put a needless discontinuity on the air.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 3, 500, FT8_SLACK_MS)).isFalse();
    }

    @Test
    public void noKeyUpBaseline_doesNotRestart() {
        // Before the first key-up of a run there is no baseline to compare against,
        // so a non-matching order is initialization, not a real change.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, -1, 6, 500, FT8_SLACK_MS)).isFalse();
    }

    @Test
    public void negativeMsInCycle_doesNotRestart() {
        // A clock correction landing mid-over can produce this; refuse rather than
        // reason about a slot position we don't trust.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, false, 3, 4, -50, FT8_SLACK_MS)).isFalse();
    }

    @Test
    public void freeTextArmed_doesNotRestart() {
        // Free text does not depend on functionOrder, so the replay would send the
        // identical message — a discontinuity on the air that buys nothing. Same
        // inputs as lateDecodeJustAfterKeyUp_restarts, which does swap.
        assertThat(FT8TransmitSignal.shouldRestartForNewOrder(
                true, true, 3, 2, 507, FT8_SLACK_MS)).isFalse();
    }

    @Test
    public void headroomIsPositive() {
        // A zero/negative headroom would let a swap start at the slack boundary and
        // clip its own leading sync array by the time playback actually began.
        assertThat(FT8TransmitSignal.RESTART_HEADROOM_MS).isGreaterThan(0);
        assertThat(FT8TransmitSignal.RESTART_HEADROOM_MS).isLessThan(FT8_SLACK_MS);
    }
}
