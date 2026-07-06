package com.k1af.ft8af.ui;

/**
 * Spectrum-strip bin combining (issue #428): how ColumnarView merges an FFT
 * bin with its right neighbor into one drawn bar.
 *
 * <p>Extracted from ColumnarView so the decision logic is JVM-testable and the
 * mode is a developer setting (GeneralVariables.getSpectrumBinAggregation()).
 * MODE_MAX reproduces the legacy expression bit-for-bit, including the
 * last-index tail case.
 *
 * <p>Inputs are dB-scaled 0..255 display intensities (fft_display.c output),
 * not linear magnitudes — so AVG/RMS here are display heuristics for A/B
 * comparison, not physically meaningful power averages.
 */
public final class BinAggregation {

    /** Max of the pair — the legacy (pre-#428) behavior and default. */
    public static final int MODE_MAX = 0;
    /** Arithmetic mean of the pair. */
    public static final int MODE_AVG = 1;
    /** Root-mean-square of the pair (emphasizes the stronger bin less than MAX). */
    public static final int MODE_RMS = 2;

    private BinAggregation() {
    }

    /**
     * Combines bin {@code i} with its right neighbor (the last bin stands
     * alone) under the given mode, clamped to 0..255. Unknown modes fall back
     * to MAX so a bad persisted value can never blank the strip.
     */
    public static int combine(int[] data, int i, int mode) {
        int a = data[i];
        if (i + 1 >= data.length) {
            return clamp(a);
        }
        int b = data[i + 1];
        switch (mode) {
            case MODE_AVG:
                return clamp((a + b + 1) / 2); // +1: round half up, keeps 255+255 -> 255
            case MODE_RMS:
                return clamp((int) Math.round(Math.sqrt((a * (double) a + b * (double) b) / 2.0)));
            case MODE_MAX:
            default:
                return clamp(Math.max(a, b));
        }
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }
}
