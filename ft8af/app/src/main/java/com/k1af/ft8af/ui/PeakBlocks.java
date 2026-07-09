package com.k1af.ft8af.ui;

import java.util.Arrays;

/**
 * Peak-hold "falling block" geometry for the spectrum strip. Extracted from
 * {@link ColumnarView} so the sizing/index decision is JVM-testable.
 *
 * <p>The old inline code rebuilt the block list to the CURRENT frame's bin
 * count ({@code binsToShow}) but the fall loop read the PREVIOUS frame's bars
 * ({@code newData}). When the bin count grew between two frames the loop indexed
 * past the previous bars and threw {@link IndexOutOfBoundsException} on the UI
 * thread (drawing is not wrapped in try/catch, so that is a hard crash). The
 * trigger is any change to the bin count between frames — a spectrum-width or
 * audio-sample-rate change while the peak blocks are shown.
 *
 * <p>The fix: size the block state to the bars we actually have this frame.
 * When the count changes the peak-hold state resets to the floor (matching the
 * legacy full-rebuild); when it is unchanged the geometry is bit-for-bit
 * identical to the pre-fix code.
 */
public final class PeakBlocks {

    private PeakBlocks() {
    }

    /**
     * Recomputes the peak-hold block tops for one frame.
     *
     * @param prevTops    previous frame's block tops (peak-hold state); its
     *                    length may differ from {@code barTops} after a
     *                    bin-count change.
     * @param barTops     current frame's bar tops, one per drawn bar.
     * @param floorTop    the resting top of a block ({@code height - blockHeight}).
     * @param blockHeight block thickness in px.
     * @param blockSpeed  how far a block falls each frame when its bar is quiet.
     * @param distance    gap kept between a rising block and its bar.
     * @return new block tops, always {@code barTops.length} long.
     */
    public static int[] update(int[] prevTops, int[] barTops, int floorTop,
                               int blockHeight, int blockSpeed, int distance) {
        int n = barTops.length;
        int[] tops = prevTops;
        if (tops.length != n) {
            // Bin count changed (or first frame): reset peak-hold to the floor,
            // matching the legacy full-rebuild. Sizing to barTops (not the new
            // bin count read from the previous bars) is the crash fix.
            tops = new int[n];
            Arrays.fill(tops, floorTop);
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            if (barTops[i] < tops[i]) {
                out[i] = barTops[i] - blockHeight - distance;
            } else {
                out[i] = tops[i] + blockSpeed;
            }
        }
        return out;
    }
}
