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
     */
    public boolean shouldDraw(long utcMs, long slotMillis) {
        long period = slotPeriod(utcMs, slotMillis);
        if (period == lastPeriod) {
            return false;
        }
        lastPeriod = period;
        return true;
    }

    /** Forget the last drawn slot (e.g. when the bitmap is recreated). */
    public void reset() {
        lastPeriod = -1;
    }
}
