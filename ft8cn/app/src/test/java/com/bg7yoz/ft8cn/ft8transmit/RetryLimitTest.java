package com.bg7yoz.ft8cn.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for retry-limit logic in {@link FT8TransmitSignal}:
 * <ul>
 *   <li>{@link FT8TransmitSignal#shouldResetNoReplyCount} — only resets when the
 *       target callsign changes (fixes counter being zeroed every cycle when
 *       Hunt re-targets the same station still calling CQ).</li>
 *   <li>{@link FT8TransmitSignal#shouldGiveUpTarget} — respects "unlimited"
 *       (noReplyLimit == 0) by never triggering, relying on the TX supervision
 *       timeout as the safety net.</li>
 * </ul>
 */
public class RetryLimitTest {

    // ===== shouldResetNoReplyCount =====

    @Test
    public void sameTarget_doesNotReset() {
        assertThat(FT8TransmitSignal.shouldResetNoReplyCount("K5ABC", "K5ABC")).isFalse();
    }

    @Test
    public void differentTarget_resets() {
        assertThat(FT8TransmitSignal.shouldResetNoReplyCount("K5ABC", "W1AW")).isTrue();
    }

    @Test
    public void emptyToCallsign_resets() {
        assertThat(FT8TransmitSignal.shouldResetNoReplyCount("", "K5ABC")).isTrue();
    }

    @Test
    public void firstCall_emptyLast_resets() {
        assertThat(FT8TransmitSignal.shouldResetNoReplyCount("K5ABC", "")).isTrue();
    }

    @Test
    public void cqToReal_resets() {
        assertThat(FT8TransmitSignal.shouldResetNoReplyCount("K5ABC", "CQ")).isTrue();
    }

    // ===== shouldGiveUpTarget =====

    @Test
    public void unlimitedMode_neverGivesUp() {
        // noReplyLimit == 0 means "unlimited"; should never trigger give-up
        assertThat(FT8TransmitSignal.shouldGiveUpTarget(0, 0)).isFalse();
        assertThat(FT8TransmitSignal.shouldGiveUpTarget(0, 4)).isFalse();
        assertThat(FT8TransmitSignal.shouldGiveUpTarget(0, 100)).isFalse();
    }

    @Test
    public void limitSet_belowLimit_noGiveUp() {
        assertThat(FT8TransmitSignal.shouldGiveUpTarget(3, 2)).isFalse();
    }

    @Test
    public void limitSet_atLimit_noGiveUp() {
        // "noReplyCount > noReplyLimit" so at-limit is not yet give-up
        assertThat(FT8TransmitSignal.shouldGiveUpTarget(3, 3)).isFalse();
    }

    @Test
    public void limitSet_exceedsLimit_givesUp() {
        assertThat(FT8TransmitSignal.shouldGiveUpTarget(3, 4)).isTrue();
    }

    @Test
    public void limitOf1_exceedsAfterSecondNoReply() {
        assertThat(FT8TransmitSignal.shouldGiveUpTarget(1, 1)).isFalse();
        assertThat(FT8TransmitSignal.shouldGiveUpTarget(1, 2)).isTrue();
    }
}
