package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link FT8TransmitSignal#hasReachedMax73Sends(int, int, int)} —
 * the user-configurable hard cap on RR73/73 (order 4/5) transmissions per QSO.
 *
 * <p>Unlike the no-reply caps in {@code shouldCompleteQso}, this cap counts
 * actual sends, so it must also end the loops the no-reply counter never sees:
 * a partner re-sending R+report at our RR73, or repeating RR73 at our 73 —
 * both of which reset {@code noReplyCount} every cycle.
 *
 * <p>Plain JUnit: the helper is a pure static predicate.
 */
public class Max73SendsTest {

    // ---- setting disabled (0 == Auto) ---------------------------------------

    @Test
    public void autoSetting_neverCaps() {
        // 0 = Auto: classic behavior only, no matter how many sends piled up.
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(0, 0, 4)).isFalse();
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(0, 100, 4)).isFalse();
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(0, 100, 5)).isFalse();
    }

    // ---- cap threshold -------------------------------------------------------

    @Test
    public void belowCap_keepsGoing() {
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, 0, 4)).isFalse();
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, 2, 4)).isFalse();
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, 2, 5)).isFalse();
    }

    @Test
    public void atCap_completes() {
        // Cap N means exactly N RR73/73 transmissions go out, then we move on.
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, 3, 4)).isTrue();
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, 3, 5)).isTrue();
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(1, 1, 4)).isTrue();
    }

    @Test
    public void aboveCap_completes() {
        // Counter can overshoot (e.g. cap lowered mid-QSO); still ends the QSO.
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, 5, 4)).isTrue();
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, 5, 5)).isTrue();
    }

    // ---- only the final-ack states are capped --------------------------------

    @Test
    public void nonFinalAckOrders_neverCap() {
        // Orders 1-3 (calling/report) are governed by the no-reply limit, and
        // order 6 (CQ) must never be cut off by a stale counter.
        for (int order : new int[]{1, 2, 3, 6}) {
            assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, 99, order)).isFalse();
        }
    }

    @Test
    public void negativeCounter_neverCaps() {
        // Defensive: a wrapped/corrupt counter must not end QSOs spuriously.
        assertThat(FT8TransmitSignal.hasReachedMax73Sends(3, -1, 4)).isFalse();
    }

    // ---- isCappedContinuation -------------------------------------------------

    @Test
    public void cappedContinuation_matchesOnlyContinuationOrdersFromCappedStation() {
        // R+report (3) and RR73/RRR (4) from the capped station restart the
        // capped loop and must be ignored.
        assertThat(FT8TransmitSignal.isCappedContinuation("N2JFD", "N2JFD", 3)).isTrue();
        assertThat(FT8TransmitSignal.isCappedContinuation("N2JFD", "N2JFD", 4)).isTrue();
        // A fresh QSO attempt (grid/report) still deserves an answer.
        assertThat(FT8TransmitSignal.isCappedContinuation("N2JFD", "N2JFD", 1)).isFalse();
        assertThat(FT8TransmitSignal.isCappedContinuation("N2JFD", "N2JFD", 2)).isFalse();
        // Other stations are never gated.
        assertThat(FT8TransmitSignal.isCappedContinuation("N2JFD", "W1XYZ", 4)).isFalse();
    }

    @Test
    public void cappedContinuation_noCappedStation_neverGates() {
        assertThat(FT8TransmitSignal.isCappedContinuation("", "N2JFD", 4)).isFalse();
        assertThat(FT8TransmitSignal.isCappedContinuation(null, "N2JFD", 4)).isFalse();
    }
}
