package com.k1af.ft8af.ft8listener;

/**
 * Time bound for the deep-decode subtract-and-redecode loop, pulled out of
 * {@link FT8SignalListener} so the decision is unit-testable without loading the native decoder.
 *
 * <p>The key property: the loop is budgeted by <em>its own</em> elapsed time, measured from the
 * loop's start — not from the start of the whole decode. The earlier fast and first deep passes
 * must not pre-spend the budget, or on a slow device the subtraction loop would abort before it
 * ran even once (which is exactly the weak-signal recovery deep decode exists to do).
 */
public final class DeepDecodeBudget {

    private DeepDecodeBudget() {}

    /**
     * @param loopStartMs wall-clock (ms) captured immediately before the subtraction loop began
     * @param nowMs       current wall-clock (ms)
     * @param budgetMs    per-loop budget from {@code ModeProfile#deepDecodeBudgetMillis}
     * @return true once the loop has run longer than its budget and should stop subtracting
     */
    public static boolean loopExhausted(long loopStartMs, long nowMs, long budgetMs) {
        return nowMs - loopStartMs > budgetMs;
    }
}
