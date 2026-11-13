package com.k1af.ft8af.grid_tracker;

/**
 * Pure slot-progress geometry for the Grid Tracker's UTC cycle-progress bar, extracted so
 * the mode-aware math can be unit-tested without an Android {@code ProgressBar}.
 *
 * <p>The bar used to hard-code a 15&nbsp;s FT8 cycle: the layout fixed
 * {@code android:max="14"} and the observer set
 * {@code setProgress((int) ((utcMs / 1000) % 15))}. That is correct only for FT8. In FT4
 * (7.5&nbsp;s slot) the fill swept over 15 wall-seconds instead of 7.5, so it reached "full"
 * on only every other slot boundary; in FT2 (3.75&nbsp;s) on only every fourth. The bar
 * never lined up with the mode's actual TX/RX grid — the same mode-agnostic defect PR&nbsp;#523
 * fixed for the waterfall timestamp and the Compose {@code SlotTimerBar} already avoids.
 *
 * <p>Keyed on the current mode's {@link com.k1af.ft8af.ModeProfile#slotMillis} the fill now
 * spans exactly one slot: {@code progress = utcMs mod slotMillis} (in milliseconds), with
 * {@code max = slotMillis - 1} so the progress value can reach {@code max} at the last
 * millisecond of the slot (a plain {@code max = slotMillis} would leave the bar one unit shy
 * of full, since {@code utcMs mod slotMillis} is always {@code < slotMillis}). At the slot
 * boundary the progress wraps back to 0. For FT8 that is {@code max = 14999}; the sweep
 * mirrors the old {@code (utcMs/1000) % 15}/{@code max=14} bar (fill rising across the slot,
 * empty at the boundary) at millisecond rather than whole-second resolution — and the timer
 * that drives it only ticks about once per second, so the visible cadence is unchanged.
 */
public final class SlotProgressBar {

    /** FT8 slot length in ms, used as the fallback for a non-positive {@code slotMillis}. */
    private static final long FT8_SLOT_MILLIS = 15_000L;

    private SlotProgressBar() {
    }

    /**
     * The {@code ProgressBar} max for a slot of the given length: {@code slotMillis - 1}, so
     * {@link #progressFor(long, long)} (which is always {@code < slotMillis}) can reach it at
     * the last millisecond of the slot and the bar renders completely full. Falls back to the
     * FT8 15&nbsp;s cycle for a non-positive length so {@code progressFor} can never divide by
     * zero.
     */
    public static int maxFor(long slotMillis) {
        return (int) ((slotMillis > 0 ? slotMillis : FT8_SLOT_MILLIS) - 1);
    }

    /**
     * The {@code ProgressBar} progress for {@code utcMs}: the milliseconds elapsed into the
     * current slot, always in {@code [0, maxFor(slotMillis)]}. Uses a floored modulo so a
     * (theoretical) negative UTC value still lands in range instead of going negative.
     */
    public static int progressFor(long utcMs, long slotMillis) {
        long slot = slotMillis > 0 ? slotMillis : FT8_SLOT_MILLIS;
        long into = ((utcMs % slot) + slot) % slot;
        return (int) into;
    }
}
