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
 * <p>The slot length is part of the key as well as the slot index, because a single
 * {@code WaterfallView} (and its one gate) is reused across mode changes: switching
 * FT8&rarr;FT4/FT2 changes the divisor, so {@code utcMs / slotMillis} can land on a slot
 * index already stamped under the previous mode and wrongly suppress the new mode's first
 * label. Including {@code slotMillis} makes any mode change a distinct key, so the first
 * slot after the switch always stamps once.
 *
 * <p>Plain Java (no Android), so the once-per-slot rule can be unit-tested directly.
 */
public final class WaterfallLabelGate {

    /** Sentinel mode key for the slot-index-only {@link #shouldStamp(long)} overload. */
    private static final long NO_MODE = Long.MIN_VALUE;

    private long lastSlotMillis = Long.MIN_VALUE;
    private long lastStampedPeriod = Long.MIN_VALUE;

    /**
     * Whether the labels for {@code period} under a mode of slot length {@code slotMillis}
     * should be stamped now. {@code period} is a decode-slot index the caller keys off the
     * current mode's slot length (e.g. {@code utcMs / slotMillis}, NOT a fixed 15s). Returns
     * true once per distinct {@code (slotMillis, period)} pair and false for any repeat
     * arming of the same pair, so a mode change always stamps even when the new slot index
     * collides with one already stamped under the old mode.
     */
    public boolean shouldStamp(long slotMillis, long period) {
        if (slotMillis == lastSlotMillis && period == lastStampedPeriod) {
            return false;
        }
        lastSlotMillis = slotMillis;
        lastStampedPeriod = period;
        return true;
    }

    /**
     * Slot-index-only gate, for callers that never change mode. Equivalent to
     * {@link #shouldStamp(long, long)} with a fixed mode key.
     */
    public boolean shouldStamp(long period) {
        return shouldStamp(NO_MODE, period);
    }

    /** Forget the last stamped slot (e.g. when the bitmap is recreated). */
    public void reset() {
        lastSlotMillis = Long.MIN_VALUE;
        lastStampedPeriod = Long.MIN_VALUE;
    }
}
