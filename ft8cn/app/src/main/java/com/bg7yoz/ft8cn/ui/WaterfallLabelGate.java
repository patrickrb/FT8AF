package com.bg7yoz.ft8cn.ui;

/**
 * Gates the waterfall's decoded-message labels to one stamp per decode slot.
 *
 * <p>The waterfall stamps the labels onto its scrolling bitmap as a one-shot, re-armed
 * every time a decode pass finishes. A decode runs more than one pass per slot (a normal
 * pass and a slower deep pass), so without a gate the same slot's labels are stamped several
 * times, at different scroll offsets, and appear to repeat down the waterfall — including
 * over the next slot's blank rows where the signal is already gone. Keyed on a per-slot
 * index (the caller derives it from the current mode's slot length, e.g.
 * {@code utcMs / slotMillis} — 15s FT8, 7.5s FT4, 3.8s FT2), this returns true for only the
 * first stamp of each slot.
 *
 * <p>Plain Java (no Android), so the once-per-slot rule can be unit-tested directly.
 */
public final class WaterfallLabelGate {

    private long lastStampedPeriod = Long.MIN_VALUE;

    /**
     * Whether the labels for {@code period} (a decode-slot index — the caller keys it off
     * the current mode's slot length, e.g. {@code utcMs / slotMillis}, NOT a fixed 15s)
     * should be stamped now. Returns true once per distinct period and false for any repeat
     * arming within the same period.
     */
    public boolean shouldStamp(long period) {
        if (period == lastStampedPeriod) {
            return false;
        }
        lastStampedPeriod = period;
        return true;
    }

    /** Forget the last stamped cycle (e.g. when the bitmap is recreated). */
    public void reset() {
        lastStampedPeriod = Long.MIN_VALUE;
    }
}
