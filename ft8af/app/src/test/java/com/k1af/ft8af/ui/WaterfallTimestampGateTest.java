package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link WaterfallTimestampGate}: the waterfall's UTC gridline is drawn once
 * per transmit/decode slot of the <em>current</em> mode (15s FT8, 7.5s FT4, 3.75s FT2), not
 * on a hard-coded 15s grid. Pure JUnit, no Android.
 */
public class WaterfallTimestampGateTest {

    /** The exact expression WaterfallView used before the fix (a fixed 15s grid). */
    private static long legacyFt8Period(long utcMs) {
        return (utcMs / 1000) / 15;
    }

    @Test
    public void ft8SlotReproducesTheLegacyFifteenSecondGrid() {
        // floor(floor(x/1000)/15) == floor(x/15000): a 15000ms slot must be byte-identical to
        // the old (utcMs/1000)/15, so FT8 rendering is unchanged by the fix.
        long[] samples = {0, 1, 999, 1000, 14_999, 15_000, 15_001, 22_500, 1_700_000_123_456L};
        for (long utcMs : samples) {
            assertThat(WaterfallTimestampGate.slotPeriod(utcMs, 15_000))
                    .isEqualTo(legacyFt8Period(utcMs));
        }
    }

    @Test
    public void ft4SlotAdvancesEveryHalfCycleAndSplitsAnFt8Slot() {
        // Two 7.5s FT4 boundaries fall inside one 15s FT8 slot, so the FT4 gridline must
        // advance where the fixed-15s code would have skipped it.
        assertThat(WaterfallTimestampGate.slotPeriod(0, 7_500)).isEqualTo(0);
        assertThat(WaterfallTimestampGate.slotPeriod(7_499, 7_500)).isEqualTo(0);
        assertThat(WaterfallTimestampGate.slotPeriod(7_500, 7_500)).isEqualTo(1);
        assertThat(WaterfallTimestampGate.slotPeriod(15_000, 7_500)).isEqualTo(2);
    }

    @Test
    public void ft2SlotAdvancesEveryQuarterCycle() {
        assertThat(WaterfallTimestampGate.slotPeriod(3_750, 3_750)).isEqualTo(1);
        assertThat(WaterfallTimestampGate.slotPeriod(15_000, 3_750)).isEqualTo(4);
    }

    @Test
    public void nonPositiveSlotMillisFallsBackToFifteenSeconds() {
        assertThat(WaterfallTimestampGate.slotPeriod(22_500, 0))
                .isEqualTo(legacyFt8Period(22_500));
        assertThat(WaterfallTimestampGate.slotPeriod(22_500, -1))
                .isEqualTo(legacyFt8Period(22_500));
    }

    @Test
    public void drawsOnceWhenEnteringANewSlotAndSuppressesRepeatsWithinIt() {
        WaterfallTimestampGate gate = new WaterfallTimestampGate();
        assertThat(gate.shouldDraw(15_000, 15_000)).isTrue();   // first frame of the slot
        assertThat(gate.shouldDraw(15_123, 15_000)).isFalse();  // still same slot
        assertThat(gate.shouldDraw(29_999, 15_000)).isFalse();  // last frame of the slot
        assertThat(gate.shouldDraw(30_000, 15_000)).isTrue();   // next slot boundary
    }

    @Test
    public void ft4DrawsAtTheHalfCycleBoundaryThatFixedFifteenSecondsWouldMiss() {
        // The regression under test: in FT4 the intermediate 7.5s boundary must draw a line.
        WaterfallTimestampGate gate = new WaterfallTimestampGate();
        assertThat(gate.shouldDraw(0, 7_500)).isTrue();
        assertThat(gate.shouldDraw(7_500, 7_500)).isTrue();     // fixed-15s code would skip this
        assertThat(gate.shouldDraw(15_000, 7_500)).isTrue();
    }

    @Test
    public void resetAllowsTheSameSlotToDrawAgain() {
        WaterfallTimestampGate gate = new WaterfallTimestampGate();
        assertThat(gate.shouldDraw(15_000, 15_000)).isTrue();
        assertThat(gate.shouldDraw(15_000, 15_000)).isFalse();
        gate.reset();
        assertThat(gate.shouldDraw(15_000, 15_000)).isTrue();   // after a bitmap recreate
    }

    @Test
    public void modeSwitchMidSlotDoesNotDrawASpuriousGridline() {
        // FT4→FT8 at t=8000: FT4 last drew slot index 1 (7.5s boundary); switching to FT8
        // re-bases to the 15s grid. t=8000 is mid-FT8-slot-0, so no line — the next true FT8
        // boundary is 15000, where it must draw. The old code compared 0 (FT8) against the
        // stale 1 (FT4) and drew mid-slot.
        WaterfallTimestampGate gate = new WaterfallTimestampGate();
        assertThat(gate.shouldDraw(0, 7_500)).isTrue();         // FT4 slot 0
        assertThat(gate.shouldDraw(7_500, 7_500)).isTrue();     // FT4 slot 1
        assertThat(gate.shouldDraw(8_000, 15_000)).isFalse();   // switched to FT8, mid-slot
        assertThat(gate.shouldDraw(15_000, 15_000)).isTrue();   // first true FT8 boundary
    }

    @Test
    public void modeSwitchLandingExactlyOnANewModeBoundaryStillDraws() {
        // FT8→FT4 exactly at 15000: 15000 is a boundary in both modes, so the switch should
        // still stamp the line rather than swallow a real boundary.
        WaterfallTimestampGate gate = new WaterfallTimestampGate();
        assertThat(gate.shouldDraw(0, 15_000)).isTrue();        // FT8 slot 0
        assertThat(gate.shouldDraw(15_000, 7_500)).isTrue();    // switched to FT4, on a boundary
        assertThat(gate.shouldDraw(15_000, 7_500)).isFalse();   // same FT4 slot, no repeat
    }
}
