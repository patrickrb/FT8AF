package com.k1af.ft8af.ui;

/**
 * Gates the waterfall's UTC-timestamp gridline (the horizontal boundary line plus the time
 * label) to one draw per transmit/decode slot, keyed on the <em>current</em> mode's slot
 * length rather than a fixed 15 seconds.
 *
 * <p>FT8 runs a 15s slot, FT4 7.5s, FT2 3.75s (see {@code ModeProfile#slotMillis}). The line
 * marks where one cycle ends and the next begins on the scrolling waterfall so an operator
 * can line signals up against the slot grid. Drawing it every 15s in a faster mode misses
 * every intermediate boundary — FT4 marks only every other slot, FT2 only every fourth —
 * exactly the kind of hard-coded-15s assumption {@code ModeProfile} exists to remove.
 *
 * <p>For a 15000ms slot {@link #slotPeriod(long, long)} reproduces the previous
 * {@code (utcMs / 1000) / 15} exactly (floor identity {@code floor(floor(x/1000)/15) ==
 * floor(x/15000)}), so FT8 rendering is unchanged; faster modes simply get their finer grid.
 *
 * <p>Makes no Android framework calls, so the once-per-slot rule is unit-tested directly (see
 * {@code WaterfallTimestampGateTest}); {@link WaterfallView} keeps only a thin call into it.
 */
public final class WaterfallTimestampGate {

    /** Fallback slot length (FT8, ms) used if the caller has no valid mode slot. */
    private static final long DEFAULT_SLOT_MILLIS = 15_000L;

    private long lastPeriod = -1;
    /** Slot length the {@link #lastPeriod} above was computed under; -1 = none yet. */
    private long lastSlotMillis = -1;

    /**
     * The slot index {@code utcMs} falls in for a mode of slot length {@code slotMillis}. A
     * non-positive {@code slotMillis} falls back to the 15s FT8 grid so a mid-init caller can
     * never divide by zero.
     */
    public static long slotPeriod(long utcMs, long slotMillis) {
        long slot = slotMillis > 0 ? slotMillis : DEFAULT_SLOT_MILLIS;
        return utcMs / slot;
    }

    /**
     * Whether the gridline should be drawn now: true the first frame {@code utcMs} crosses
     * into a new slot for a mode of slot length {@code slotMillis}, false while it stays
     * within the same slot (so the line and label are stamped exactly once per slot).
     *
     * <p>The slot <em>index</em> is only comparable within one mode — period 1 spans 7.5–15s
     * in FT4 but 15–30s in FT8. So when {@code slotMillis} changes mid-stream (the operator
     * switched modes), the previous index can't be compared to the new one; doing so would
     * emit a spurious gridline in the middle of a slot (e.g. FT4→FT8 at t=8s, where the next
     * true FT8 boundary is 15s). On a mode change we re-baseline to the new mode's grid
     * silently, drawing only if {@code utcMs} happens to land exactly on a new-mode boundary,
     * so the next line falls on the next true boundary of the mode now in effect.
     */
    public boolean shouldDraw(long utcMs, long slotMillis) {
        long slot = slotMillis > 0 ? slotMillis : DEFAULT_SLOT_MILLIS;
        long period = utcMs / slot;
        if (lastSlotMillis > 0 && slot != lastSlotMillis) {
            // Mode change: adopt the new grid without comparing across incompatible indices.
            lastSlotMillis = slot;
            lastPeriod = period;
            return utcMs % slot == 0;
        }
        lastSlotMillis = slot;
        if (period == lastPeriod) {
            return false;
        }
        lastPeriod = period;
        return true;
    }

    /** Forget the last drawn slot and mode (e.g. when the bitmap is recreated). */
    public void reset() {
        lastPeriod = -1;
        lastSlotMillis = -1;
    }
}
