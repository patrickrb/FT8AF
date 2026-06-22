package com.k1af.ft8af.spectrum;

import java.util.Locale;

/**
 * Maps an {@link AudioInputLevel.Reading} to the text and colour the receive meter shows.
 *
 * <p>Kept separate from the fragment so the label/colour decisions are unit-testable without
 * Android: the colours are plain ARGB ints (the same literal form {@code Color.parseColor}
 * yields), and the fragment only forwards them to {@code setText}/{@code setTextColor}.
 */
public final class AudioLevelDisplay {

    private AudioLevelDisplay() {}

    // Meter colours (ARGB). Green = aim here, amber = edge, red = clipping, grey = no signal.
    public static final int COLOR_SILENT = 0xFF9E9E9E; // grey
    public static final int COLOR_LOW = 0xFFFFC107;    // amber
    public static final int COLOR_GOOD = 0xFF4CAF50;   // green
    public static final int COLOR_HIGH = 0xFFFF9800;   // orange
    public static final int COLOR_CLIP = 0xFFF44336;   // red

    /**
     * One-line meter label, e.g. {@code "RX -14 dB"}. Shows the RMS level (the steady
     * signal/noise energy, like WSJT-X's averaged meter) for normal readings, a "CLIP" warning
     * when pinned at full scale, and a dash when there is effectively no input.
     */
    public static String label(AudioInputLevel.Reading r) {
        switch (r.status) {
            case CLIPPING:
                return "RX CLIP";
            case SILENT:
                return "RX --";
            default:
                return String.format(Locale.US, "RX %d dB", Math.round(r.rmsDbfs));
        }
    }

    /** ARGB colour for the meter text, by reading status. */
    public static int color(AudioInputLevel.Reading r) {
        switch (r.status) {
            case CLIPPING:
                return COLOR_CLIP;
            case HIGH:
                return COLOR_HIGH;
            case LOW:
                return COLOR_LOW;
            case SILENT:
                return COLOR_SILENT;
            case GOOD:
            default:
                return COLOR_GOOD;
        }
    }
}
