package com.bg7yoz.ft8cn.ui;

/**
 * Gates the waterfall's decoded-message labels to one stamp per 15-second FT8 cycle.
 *
 * <p>The waterfall stamps the labels onto its scrolling bitmap as a one-shot, re-armed
 * every time a decode pass finishes. FT8 runs more than one pass per cycle (a normal pass
 * and a slower deep pass), so without a gate the same cycle's labels are stamped several
 * times, at different scroll offsets, and appear to repeat down the waterfall — including
 * over the next cycle's blank rows where the signal is already gone. Keyed on the UTC
 * 15-second period, this returns true for only the first stamp of each cycle.
 *
 * <p>Plain Java (no Android), so the once-per-cycle rule can be unit-tested directly.
 */
public final class WaterfallLabelGate {

    private long lastStampedPeriod = Long.MIN_VALUE;

    /**
     * Whether the labels for {@code period} (a UTC 15s cycle index, e.g. {@code utcSec/15})
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
