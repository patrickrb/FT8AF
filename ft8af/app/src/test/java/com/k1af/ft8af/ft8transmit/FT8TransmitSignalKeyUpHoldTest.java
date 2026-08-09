package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests for the bound on the adaptive key-up hold — how long the transmitter may wait for
 * the fast decode of the slot that just ended before it must key up regardless.
 *
 * <p>The hold spends otherwise-idle audio slack, so the load-bearing property is that it
 * can never spend so much that the transmission starts past the slack and has its leading
 * Costas sync array clipped. That defect makes a signal loud on the air and undecodable,
 * and this codebase has already shipped it twice; the reserves below exist to keep a third
 * time from being possible.
 *
 * <p>The gate itself is covered by {@code FastDecodeGateTest}.
 */
public class FT8TransmitSignalKeyUpHoldTest {

    /** FT8: 15000 ms slot, 12640 ms of audio. */
    private static final int FT8_SLACK_MS = 2360;
    /** FT4: 7500 ms slot, 5040 ms of audio. */
    private static final int FT4_SLACK_MS = 2460;
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void holdLimitLeavesRoomForKeyUpCosts() {
        long limit = FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, 100);
        assertThat(limit)
                .isEqualTo(FT8_SLACK_MS - 100 - FT8TransmitSignal.KEYUP_HOLD_RESERVE_MS);
    }

    @Test
    public void aHeldStartStillFitsInsideTheSlack() {
        // The property that matters, stated directly: hold + PTT + generation must not
        // exceed the slack, or the waveform gets its leading sync array clipped.
        for (int ptt : new int[] {0, 100, 400, 1_000}) {
            long limit = FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, ptt);
            assertThat(limit + ptt + FT8TransmitSignal.KEYUP_HOLD_RESERVE_MS)
                    .isAtMost((long) FT8_SLACK_MS);
        }
    }

    @Test
    public void holdLimitIsWorthHaving() {
        // Key-up currently lands ~450 ms into the slot, so the limit must be meaningfully
        // beyond that or the hold buys no decode time at all.
        assertThat(FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, 100)).isGreaterThan(450L);
    }

    @Test
    public void aLargePttDelayShrinksTheHoldRatherThanBorrowingMargin() {
        assertThat(FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, 1_500))
                .isEqualTo(FT8_SLACK_MS - 1_500 - FT8TransmitSignal.KEYUP_HOLD_RESERVE_MS);
    }

    @Test
    public void noSlackToSpendMeansNoHold() {
        // Reserves exceed the slack: fall back to today's behaviour rather than clipping.
        assertThat(FT8TransmitSignal.keyUpHoldLimitMs(400, 100)).isEqualTo(0L);
        assertThat(FT8TransmitSignal.keyUpHoldLimitMs(0, 0)).isEqualTo(0L);
        assertThat(FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, 99_999)).isEqualTo(0L);
    }

    @Test
    public void negativePttDelayIsTreatedAsZero() {
        assertThat(FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, -50))
                .isEqualTo(FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, 0));
    }

    @Test
    public void ft4GetsItsOwnSlack() {
        assertThat(FT8TransmitSignal.keyUpHoldLimitMs(FT4_SLACK_MS, 100))
                .isGreaterThan(FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, 100));
    }

    @Test
    public void deadlineIsAnchoredToTheBoundary() {
        assertThat(FT8TransmitSignal.keyUpHoldDeadline(T0, FT8_SLACK_MS, 100))
                .isEqualTo(T0 + FT8TransmitSignal.keyUpHoldLimitMs(FT8_SLACK_MS, 100));
    }

    @Test
    public void deadlineWithNoHeadroomEqualsTheBoundary() {
        // Equal to "now at the boundary", which the caller treats as "do not wait".
        assertThat(FT8TransmitSignal.keyUpHoldDeadline(T0, 400, 100)).isEqualTo(T0);
    }
}
