package com.k1af.ft8af.wave;

/**
 * RX input gain + input-level metering (issue #356).
 *
 * <p>{@link #applyGain} multiplies incoming float samples by the user's
 * configured input volume before they reach the resampler / slot accumulator /
 * decoder. It is called from {@link HamRecorder#doOnWaveDataReceived}, the
 * single choke point every RX audio path funnels through (built-in mic
 * AudioRecord, direct-USB capture, and the network-audio connectors), so the
 * decoder, the waterfall FFT, and the level meter all see the same post-gain
 * samples.
 *
 * <p>The instance side is a short-sliding-window level meter: it accumulates
 * peak (max absolute sample) and sum-of-squares over {@link #WINDOW_SAMPLES}
 * post-gain samples and emits one {@link Levels} snapshot per completed
 * window (~4 per second at 12 kHz), which the caller posts to LiveData for
 * the Compose UI. Emitting once per window instead of once per audio buffer
 * keeps main-thread traffic bounded regardless of capture buffer size.
 *
 * <p>Plain Java with no Android dependencies so it is unit-testable on the
 * host JVM.
 */
public final class InputAudioLevel {

    /**
     * Sliding-window length in samples. 3000 samples at the app-wide 12 kHz
     * RX rate is 250 ms — short enough that the meter tracks a single FT8
     * transmission appearing mid-slot, long enough to smooth per-buffer
     * jitter.
     */
    public static final int WINDOW_SAMPLES = 3000;

    /** Immutable level snapshot for one completed metering window. */
    public static final class Levels {
        /** Max absolute sample value in the window (1.0 = full scale). */
        public final float peak;
        /** Root-mean-square sample value in the window (1.0 = full scale). */
        public final float rms;

        public Levels(float peak, float rms) {
            this.peak = peak;
            this.rms = rms;
        }
    }

    private double sumSquares = 0.0;
    private float windowPeak = 0f;
    private int count = 0;

    /**
     * Multiply the first {@code len} samples of {@code data} in place by
     * {@code gain}. Unity gain is an exact no-op so the default 100% setting
     * leaves the audio path bit-identical to previous releases.
     */
    public static void applyGain(float[] data, int len, float gain) {
        if (data == null || gain == 1.0f) {
            return;
        }
        int n = Math.min(len, data.length);
        for (int i = 0; i < n; i++) {
            data[i] *= gain;
        }
    }

    /**
     * Feed post-gain samples into the meter. Returns a {@link Levels}
     * snapshot each time a full {@link #WINDOW_SAMPLES} window completes
     * (possibly the last of several windows when {@code len} is huge),
     * otherwise {@code null}. Not thread-safe; call from the single audio
     * dispatch thread.
     */
    public Levels process(float[] data, int len) {
        if (data == null) {
            return null;
        }
        int n = Math.min(len, data.length);
        Levels result = null;
        for (int i = 0; i < n; i++) {
            float v = data[i];
            float abs = Math.abs(v);
            if (abs > windowPeak) {
                windowPeak = abs;
            }
            sumSquares += (double) v * v;
            count++;
            if (count >= WINDOW_SAMPLES) {
                result = new Levels(windowPeak,
                        (float) Math.sqrt(sumSquares / count));
                sumSquares = 0.0;
                windowPeak = 0f;
                count = 0;
            }
        }
        return result;
    }
}
