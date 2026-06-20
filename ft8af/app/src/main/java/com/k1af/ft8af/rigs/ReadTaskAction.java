package com.k1af.ft8af.rigs;

/**
 * Pure-logic helper that decides what the readFreqTimer tick should do.
 * Extracted so the decision can be unit-tested without Timer or Android deps.
 */
final class ReadTaskAction {

    enum Action { SKIP, READ_FREQ, READ_METERS }

    private ReadTaskAction() {} // utility class

    /**
     * @param connected true when {@link BaseRig#isConnected()} is true
     * @param pttOn     true when {@link BaseRig#isPttOn()} is true
     * @return the action the timer tick should take
     */
    static Action decide(boolean connected, boolean pttOn) {
        if (!connected) return Action.SKIP;
        if (pttOn) return Action.READ_METERS;
        return Action.READ_FREQ;
    }
}
