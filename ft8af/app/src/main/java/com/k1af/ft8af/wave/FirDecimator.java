package com.k1af.ft8af.wave;

/**
 * Fixed-ratio FIR low-pass decimator — Java port of {@code cpp/fir_decimator.h} (same
 * Blackman windowed-sinc kernel, cutoff, and streaming behavior), for the {@code UsbRequest}
 * fallback capture path in {@link UsbAudioDevice}. That path still used the old box-filter
 * average (~12 dB stopband), so on any device where the native libusb capture fails to start,
 * RX sensitivity silently regressed to the pre-FIR noise floor: broadband hiss above the 6 kHz
 * output Nyquist folded back into the 0-3 kHz FT8 passband.
 *
 * <p>Streaming-safe: call {@link #process} repeatedly across buffers; filter state carries
 * over. Kernel: {@code 2*tapsPerRatio*ratio + 1} taps, cutoff {@code 0.45/ratio} (passband
 * flat to ~0.9 of the output Nyquist, well above FT8's ~3 kHz top), unity DC gain,
 * ~-58 dB stopband at the default 8 taps/ratio.
 */
public final class FirDecimator {

    private int ratio;
    private float[] kernel;
    private float[] history;
    private int pos;
    private int phase;

    /**
     * Integer decimation ratio for an input/target rate pair, always >= 1. Mirrors
     * {@code decimationRatioFor} in the native header: a zero/negative target or a target
     * above the input rate degrades to 1 (pass-through). A non-integer multiple floors —
     * callers should warn, since the output rate won't match what the decoder expects.
     */
    public static int decimationRatioFor(int inputRate, int targetRate) {
        if (targetRate <= 0 || inputRate < targetRate) {
            return 1;
        }
        return inputRate / targetRate;
    }

    public FirDecimator(int ratio) {
        this(ratio, 8);
    }

    public FirDecimator(int ratio, int tapsPerRatio) {
        configure(ratio, tapsPerRatio);
    }

    /** Rebuild for a new ratio and clear state. */
    public void configure(int ratio, int tapsPerRatio) {
        this.ratio = Math.max(1, ratio);
        buildKernel(Math.max(1, tapsPerRatio));
        history = new float[kernel.length];
        pos = 0;
        phase = 0;
    }

    /** Drop all filter state (e.g. on stream restart) without rebuilding the kernel. */
    public void reset() {
        java.util.Arrays.fill(history, 0f);
        pos = 0;
        phase = 0;
    }

    public int ratio() {
        return ratio;
    }

    public int numTaps() {
        return kernel.length;
    }

    /**
     * Push {@code n} input samples; write decimated output samples into {@code out} from
     * index 0. Produces about {@code n/ratio} outputs (never more than {@code n/ratio + 1});
     * {@code out} must be at least that large.
     *
     * @return the number of output samples written
     */
    public int process(float[] in, int n, float[] out) {
        final int taps = kernel.length;
        int produced = 0;
        for (int i = 0; i < n; i++) {
            history[pos] = in[i];
            pos = (pos + 1 == taps) ? 0 : pos + 1;
            if (++phase >= ratio) {
                phase = 0;
                out[produced++] = dot();
            }
        }
        return produced;
    }

    // Convolve the current history window with the symmetric kernel; history[pos] holds the
    // oldest sample, paired with kernel[0]. Symmetry means direction only adds group delay.
    private float dot() {
        final int taps = kernel.length;
        float acc = 0f;
        int idx = pos;
        for (int k = 0; k < taps; k++) {
            acc += history[idx] * kernel[k];
            idx = (idx + 1 == taps) ? 0 : idx + 1;
        }
        return acc;
    }

    private void buildKernel(int tapsPerRatio) {
        final int n = 2 * tapsPerRatio * ratio + 1; // odd -> exact symmetry about center
        final int m = n - 1;
        final double fc = 0.45 / ratio;

        kernel = new float[n];
        double sum = 0.0;
        for (int k = 0; k < n; k++) {
            final double x = k - m / 2.0;
            final double sinc = (Math.abs(x) < 1e-9)
                    ? 2.0 * fc
                    : Math.sin(2.0 * Math.PI * fc * x) / (Math.PI * x);
            final double w = 0.42
                    - 0.5 * Math.cos(2.0 * Math.PI * k / m)
                    + 0.08 * Math.cos(4.0 * Math.PI * k / m);
            final double h = sinc * w;
            kernel[k] = (float) h;
            sum += h;
        }
        final float norm = (float) (1.0 / sum);
        for (int k = 0; k < n; k++) {
            kernel[k] *= norm;
        }
    }
}
