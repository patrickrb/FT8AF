package com.k1af.ft8af.ui;

import com.k1af.ft8af.FT8Common;

/**
 * Frequency-axis geometry for the two RX spectrum views (ColumnarView and
 * WaterfallView).
 *
 * <p>The receive audio is always captured, resampled and decoded at
 * {@link FT8Common#SAMPLE_RATE} (12 kHz) — see {@code MicRecorder.sampleRateInHz},
 * the USB {@code startCapture(sampleRateInHz)} path, and
 * {@code FT8SignalListener.InitDecoder(..., FT8Common.SAMPLE_RATE, ...)}. The FFT
 * both views render is computed over that RX audio, so its bins always span
 * 0..{@link #RX_NYQUIST_HZ} (0..6 kHz) regardless of anything the user selects.
 *
 * <p>The views previously scaled the axis by {@code GeneralVariables.audioSampleRate},
 * which is the <em>transmit</em> audio rate (user-selectable 12/24/48 kHz for
 * USB sound-card rigs) and has nothing to do with the RX FFT. Picking 24 kHz or
 * 48 kHz doubled/quadrupled the assumed Nyquist, so every FFT bin was mapped to
 * 2x/4x its true frequency: signals no longer lined up with the frequency ruler,
 * the TX marker, or click-to-tune (which are derived from {@code spectrumWidth}
 * alone). The default 12 kHz masked the bug. Keying the axis off the fixed RX
 * rate here fixes it; at 12 kHz the result is bit-for-bit identical to the old
 * code.
 *
 * <p>Extracted as a pure helper so the geometry is JVM-testable, mirroring
 * {@link BinAggregation} and {@code PeakBlocks}.
 */
public final class SpectrumScale {

    /** Nyquist frequency (Hz) of the RX FFT data both spectrum views render. */
    public static final float RX_NYQUIST_HZ = FT8Common.SAMPLE_RATE / 2f;

    private SpectrumScale() {
    }

    /**
     * Number of leading FFT bins that cover {@code spectrumWidthHz} of the
     * displayed band, i.e. how many of the {@code fftLength} bins (which span
     * 0..{@link #RX_NYQUIST_HZ}) fall inside the visible width. Clamped to at
     * most {@code fftLength}; falls back to half the array if the rounded value
     * collapses to zero, preserving the pre-fix ColumnarView behavior.
     */
    public static int binsToShow(int fftLength, int spectrumWidthHz) {
        int bins = Math.min(fftLength,
                Math.round((float) spectrumWidthHz / RX_NYQUIST_HZ * fftLength));
        if (bins <= 0) {
            bins = fftLength / 2;
        }
        return bins;
    }

    /**
     * Horizontal gradient scale for the waterfall strip: the full bin array
     * (0..{@link #RX_NYQUIST_HZ}) is stretched so that {@code spectrumWidthHz}
     * fills the visible view width.
     *
     * <p>A non-positive {@code spectrumWidthHz} (never produced by the settings UI, which
     * constrains the value, but reachable because config hydration parses the persisted
     * {@code spectrumWidth} without clamping — {@code DatabaseOpr.parseConfigInt} leaves range
     * checks to setters) would divide by zero and yield {@code Infinity}/negative, breaking
     * {@code LinearGradient}. Fall back to a scale of {@code 1.0} (the full 0..Nyquist band
     * drawn undistorted across the view) instead, mirroring {@link #binsToShow}'s own
     * collapse-to-zero fallback.
     */
    public static float gradientScale(int spectrumWidthHz) {
        if (spectrumWidthHz <= 0) {
            return 1f;
        }
        return RX_NYQUIST_HZ / spectrumWidthHz;
    }
}
