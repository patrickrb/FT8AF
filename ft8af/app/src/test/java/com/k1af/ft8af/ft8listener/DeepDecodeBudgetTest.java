package com.k1af.ft8af.ft8listener;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for the deep-decode loop budget. The regression these guard against: the budget once
 * compared the <em>total</em> decode elapsed time, so the initial fast + first deep passes could
 * pre-spend it and abort the subtraction loop on slow devices before it ran once.
 */
public class DeepDecodeBudgetTest {

    @Test
    public void notExhausted_whileLoopElapsedUnderBudget() {
        // Loop started at t=10000; now t=10500 -> 500ms of loop time, budget 11250ms.
        assertThat(DeepDecodeBudget.loopExhausted(10_000, 10_500, 11_250)).isFalse();
    }

    @Test
    public void exhausted_onceLoopElapsedExceedsBudget() {
        assertThat(DeepDecodeBudget.loopExhausted(10_000, 21_300, 11_250)).isTrue();
    }

    @Test
    public void boundaryIsExclusive_equalElapsedIsNotExhausted() {
        // Exactly at budget must still allow one more pass (strictly greater-than).
        assertThat(DeepDecodeBudget.loopExhausted(0, 5_625, 5_625)).isFalse();
        assertThat(DeepDecodeBudget.loopExhausted(0, 5_626, 5_625)).isTrue();
    }

    @Test
    public void measuredFromLoopStart_notDecodeStart() {
        // The whole decode may already be 20s deep (huge absolute "now"), but if the loop only
        // just started, it is NOT exhausted — this is the bug fix the helper exists for.
        long decodeStart = 1_000_000L;
        long loopStart = decodeStart + 20_000L;   // 20s of fast+deep passes already elapsed
        long now = loopStart + 100L;              // loop itself has run 100ms
        assertThat(DeepDecodeBudget.loopExhausted(loopStart, now, 11_250)).isFalse();
    }
}
