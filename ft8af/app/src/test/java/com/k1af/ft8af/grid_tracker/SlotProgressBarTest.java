package com.k1af.ft8af.grid_tracker;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM tests for {@link SlotProgressBar} — the mode-aware slot geometry the Grid Tracker
 * UTC progress bar renders. No Android runtime: it is plain modular arithmetic.
 */
public class SlotProgressBarTest {

    // FT8 15s cycle: byte-for-byte the same sweep the old (utcMs/1000)%15 bar drew, just at
    // ms resolution — max is the slot length, full at the slot boundary.
    @Test
    public void ft8_maxIsFifteenSeconds() {
        assertThat(SlotProgressBar.maxFor(15_000L)).isEqualTo(15_000);
    }

    @Test
    public void ft8_progressSweepsOneSlot() {
        assertThat(SlotProgressBar.progressFor(0L, 15_000L)).isEqualTo(0);
        assertThat(SlotProgressBar.progressFor(7_500L, 15_000L)).isEqualTo(7_500);
        // Slot boundary wraps back to empty.
        assertThat(SlotProgressBar.progressFor(15_000L, 15_000L)).isEqualTo(0);
        assertThat(SlotProgressBar.progressFor(16_000L, 15_000L)).isEqualTo(1_000);
    }

    // FT4 7.5s slot: the fill must span 7.5s, not 15s.
    @Test
    public void ft4_maxIsSevenPointFiveSeconds() {
        assertThat(SlotProgressBar.maxFor(7_500L)).isEqualTo(7_500);
    }

    @Test
    public void ft4_progressWrapsEverySlot() {
        assertThat(SlotProgressBar.progressFor(0L, 7_500L)).isEqualTo(0);
        // 0.5s past the first FT4 slot boundary -> 0.5s into the *second* slot.
        assertThat(SlotProgressBar.progressFor(8_000L, 7_500L)).isEqualTo(500);
        assertThat(SlotProgressBar.progressFor(7_500L, 7_500L)).isEqualTo(0);
    }

    // FT2 3.75s slot.
    @Test
    public void ft2_maxAndProgress() {
        assertThat(SlotProgressBar.maxFor(3_750L)).isEqualTo(3_750);
        assertThat(SlotProgressBar.progressFor(4_000L, 3_750L)).isEqualTo(250);
    }

    /**
     * The regression this fixes: for a fast mode the old bar drew the wrong fill because it
     * assumed a 15s cycle. At utcMs=8000, FT4's real state is 0.5s / 7.5s ~= 7% full, but the
     * old {@code (utcMs/1000)%15} on a max=14 bar reported 8/14 ~= 57% full — over half a slot
     * out of step. The new geometry (max=slot, progress=utcMs mod slot) tracks the true slot.
     */
    @Test
    public void ft4_newGeometryDivergesFromOldFifteenSecondFormula() {
        long utcMs = 8_000L;
        float newFrac = SlotProgressBar.progressFor(utcMs, 7_500L)
                / (float) SlotProgressBar.maxFor(7_500L);
        float oldFrac = ((utcMs / 1000L) % 15) / 14f;
        assertThat(newFrac).isWithin(1e-4f).of(500f / 7_500f);
        // The old formula was badly off for the fast mode.
        assertThat(Math.abs(newFrac - oldFrac)).isGreaterThan(0.4f);
    }

    // A non-positive slot length (never produced by ModeProfile, but guarded for the pure
    // contract) falls back to the FT8 15s cycle instead of dividing by zero.
    @Test
    public void nonPositiveSlotFallsBackToFt8() {
        assertThat(SlotProgressBar.maxFor(0L)).isEqualTo(15_000);
        assertThat(SlotProgressBar.maxFor(-5L)).isEqualTo(15_000);
        assertThat(SlotProgressBar.progressFor(3_000L, 0L)).isEqualTo(3_000);
        assertThat(SlotProgressBar.progressFor(3_000L, -5L)).isEqualTo(3_000);
    }

    // Progress is always in [0, max), including for a (theoretical) negative UTC.
    @Test
    public void progressIsAlwaysInRange() {
        assertThat(SlotProgressBar.progressFor(-1L, 15_000L)).isEqualTo(14_999);
        assertThat(SlotProgressBar.progressFor(-15_000L, 15_000L)).isEqualTo(0);
    }
}
