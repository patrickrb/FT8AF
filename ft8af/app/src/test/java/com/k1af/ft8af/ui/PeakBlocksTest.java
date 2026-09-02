package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests for {@link PeakBlocks}: the spectrum-strip peak-hold "falling block"
 * geometry extracted from ColumnarView.
 *
 * <p>The extraction fixes a UI-thread crash. The legacy inline code rebuilt the
 * block list to the CURRENT frame's bin count ({@code binsToShow}) while the
 * fall loop read from the PREVIOUS frame's bar list ({@code newData}), so when
 * the bin count GREW between two frames the loop indexed past the previous
 * bars and threw {@link IndexOutOfBoundsException} on the main thread (no
 * try/catch around draw). {@link #legacyBuggy} reproduces that throw; the new
 * code sizes everything to the current bars, so it can't. Pure math — no
 * Robolectric.
 */
public class PeakBlocksTest {

    private static final int FLOOR = 95;      // getHeight() - blockHeight
    private static final int BLOCK_HEIGHT = 5;
    private static final int BLOCK_SPEED = 5;
    private static final int DISTANCE = 2;

    /**
     * The pre-fix ColumnarView block loop, kept as the oracle for the stable
     * case and to demonstrate the crash. {@code prevBlockTops} is the retained
     * block state; {@code barTops} the previous frame's {@code newData} bar
     * tops; {@code binsToShow} the current bin count the list was
     * (incorrectly) rebuilt to when the size differed.
     */
    private static int[] legacyBuggy(int[] prevBlockTops, int[] barTops, int binsToShow) {
        int[] blockTops = prevBlockTops;
        if (blockTops.length == 0 || barTops.length != blockTops.length) {
            // Rebuild block list to binsToShow when the size differs (the bug:
            // the rebuild uses the CURRENT bin count, the loop reads barTops).
            blockTops = new int[binsToShow];
            for (int i = 0; i < binsToShow; i++) {
                blockTops[i] = FLOOR;
            }
        }
        for (int i = 0; i < blockTops.length; i++) {
            // Reads barTops (the previous frame's bars) at every block index.
            if (barTops[i] < blockTops[i]) {
                blockTops[i] = barTops[i] - BLOCK_HEIGHT - DISTANCE;
            } else {
                blockTops[i] = blockTops[i] + BLOCK_SPEED;
            }
        }
        return blockTops;
    }

    private static int[] update(int[] prevTops, int[] barTops) {
        return PeakBlocks.update(prevTops, barTops, FLOOR, BLOCK_HEIGHT, BLOCK_SPEED, DISTANCE);
    }

    @Test
    public void growingBinCountDoesNotThrow() {
        // The bin count grew to 6 this frame while newData still holds the
        // previous frame's 3 bars, and the block state size does not match
        // (empty here, e.g. the first frame blocks are built). The legacy code
        // rebuilt to 6 blocks and read barTops[3..5] of a length-3 array.
        int[] prevBlockTops = new int[0];
        int[] barTops = {10, 20, 30};   // only the 3 previous bars available

        try {
            legacyBuggy(prevBlockTops, barTops, 6);
            throw new AssertionError("expected the legacy code to crash on a grown bin count");
        } catch (IndexOutOfBoundsException expected) {
            // This is the bug we are fixing.
        }

        // The extracted logic sizes to the bars it actually has: no throw, and
        // exactly one block per current bar.
        int[] out = update(prevBlockTops, barTops);
        assertThat(out).hasLength(barTops.length);
    }

    @Test
    public void firstFrameStartsFromTheFloor() {
        // No prior state (empty peak-hold): each block falls one step from the
        // floor unless its bar rises above it.
        int[] barTops = {FLOOR + 100, FLOOR + 100};   // bars below the floor
        int[] out = update(new int[0], barTops);
        assertThat(out).hasLength(2);
        assertThat(out[0]).isEqualTo(FLOOR + BLOCK_SPEED);
        assertThat(out[1]).isEqualTo(FLOOR + BLOCK_SPEED);
    }

    @Test
    public void stableSizeMatchesLegacyMath() {
        // When the bin count is unchanged the new code must reproduce the
        // legacy geometry bit-for-bit.
        int[] prevTops = {FLOOR, 40, 88};
        int[] barTops = {12, 200, 3};
        // legacyBuggy mutates its block-state array in place, so give each call
        // its own copy of the inputs.
        int[] expected = legacyBuggy(prevTops.clone(), barTops.clone(), prevTops.length);
        assertThat(update(prevTops.clone(), barTops.clone())).isEqualTo(expected);
    }

    @Test
    public void tallBarPushesBlockUpAboveIt() {
        // A bar whose top is above the current block snaps the block up to
        // barTop - blockHeight - distance.
        int[] prevTops = {FLOOR};
        int[] barTops = {30};   // 30 < FLOOR, so the block rises
        int[] out = update(prevTops, barTops);
        assertThat(out[0]).isEqualTo(30 - BLOCK_HEIGHT - DISTANCE);
    }

    @Test
    public void quietBarLetsBlockFallByOneStep() {
        int[] prevTops = {30};
        int[] barTops = {80};   // 80 > 30, so the block falls
        int[] out = update(prevTops, barTops);
        assertThat(out[0]).isEqualTo(30 + BLOCK_SPEED);
    }

    @Test
    public void shrinkingBinCountResetsToFloor() {
        // Bar count shrank; sizes differ so peak-hold resets to the floor
        // (legacy full-rebuild behavior), then falls one step.
        int[] prevTops = {10, 10, 10, 10, 10};
        int[] barTops = {FLOOR + 50, FLOOR + 50};
        int[] out = update(prevTops, barTops);
        assertThat(out).hasLength(2);
        assertThat(out[0]).isEqualTo(FLOOR + BLOCK_SPEED);
    }

    @Test
    public void emptyBarsProduceEmptyResult() {
        assertThat(update(new int[] {1, 2, 3}, new int[0])).isEmpty();
    }

    @Test
    public void reusePreviousBlocks_onlyWhenGridUnchanged() {
        // Steady state: previous bar count matches the bins to draw -> reuse the
        // held blocks (bit-for-bit legacy behavior).
        assertThat(PeakBlocks.canReusePreviousBlocks(64, 64)).isTrue();
        // First frame: no previous bars -> nothing to reuse.
        assertThat(PeakBlocks.canReusePreviousBlocks(0, 64)).isFalse();
        // Bin count grew (spectrum-width / sample-rate change) -> stale grid.
        assertThat(PeakBlocks.canReusePreviousBlocks(32, 64)).isFalse();
        // Bin count shrank -> stale grid; blocks from the wider grid would smear
        // over the new narrower bars, so they must be dropped, not reused.
        assertThat(PeakBlocks.canReusePreviousBlocks(64, 32)).isFalse();
    }
}
