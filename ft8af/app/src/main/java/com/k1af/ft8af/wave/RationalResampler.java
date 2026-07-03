package com.k1af.ft8af.wave;

/**
 * Streaming polyphase rational (L/M) resampler — Java port of
 * {@code cpp/rational_resampler.h} (same Blackman windowed-sinc kernel, cutoff, per-branch
 * normalization, and streaming behavior), for the {@code UsbRequest} fallback capture path in
 * {@link UsbAudioDevice}.
 *
 * <p>Why it exists (issue #364): both USB capture paths used to floor the decimation ratio, so
 * a device that only streams 44.1 kHz got {@code 44100/12000 -> 3} and actually produced
 * 14.7 kHz audio labeled 12 kHz — every FT8 tone shifted off the 6.25 Hz grid and nothing
 * decoded. This class resamples by the exact rational factor instead: interpolate by L,
 * low-pass, decimate by M (44.1 kHz -> 12 kHz is L/M = 40/147), implemented as a polyphase FIR
 * so the zero-stuffed samples are never materialized (~{@code numTaps()/L} multiplies per
 * output sample). The 48 kHz integer path keeps using {@link FirDecimator} untouched.
 *
 * <p>Streaming-safe: call {@link #process} repeatedly across buffers; filter state carries
 * over. This is also the host-testable reference implementation of the native kernel — keep
 * the two in sync (the C++ side is covered by {@code ft8af_glue/test_rational_resampler.cpp}).
 */
public final class RationalResampler {

    private int interpolation;   // L
    private int decimation;      // M
    private float[] kernel;
    private float[] history;
    private int histLen;
    private int pos;
    private long inCount;
    private long nextT;

    /**
     * Reduced interpolate/decimate pair {@code {L, M}} for an input/target rate pair
     * ({@code outputRate == inputRate * L / M} exactly). Mirrors the guards of
     * {@link FirDecimator#decimationRatioFor}: a zero/negative target or a target above the
     * input rate degrades to {@code {1, 1}} (pass-through) instead of trying to upsample audio
     * that has no content above the input Nyquist anyway; callers should warn.
     * 48000/12000 -> {@code {1, 4}} (use the integer {@link FirDecimator} fast path for that);
     * 44100/12000 -> {@code {40, 147}}.
     */
    public static int[] ratioFor(int inputRate, int targetRate) {
        if (targetRate <= 0 || inputRate < targetRate) {
            return new int[] {1, 1};
        }
        int g = gcd(inputRate, targetRate);
        return new int[] {targetRate / g, inputRate / g};
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    public RationalResampler(int interpolation, int decimation) {
        this(interpolation, decimation, 8);
    }

    /**
     * @param tapsPerPhase scales the FIR length ({@code 2*tapsPerPhase*max(L,M) + 1} taps at
     *     the conceptual upsampled rate, i.e. ~{@code 2*tapsPerPhase} taps per polyphase
     *     branch); 8 with a Blackman window gives ~-58 dB stopband, matching
     *     {@link FirDecimator}.
     */
    public RationalResampler(int interpolation, int decimation, int tapsPerPhase) {
        configure(interpolation, decimation, tapsPerPhase);
    }

    /** Rebuild for a new L/M pair and clear state. */
    public void configure(int interpolation, int decimation, int tapsPerPhase) {
        this.interpolation = Math.max(1, interpolation);
        this.decimation = Math.max(1, decimation);
        buildKernel(Math.max(1, tapsPerPhase));
        // Enough input history for the deepest tap any output phase reaches.
        histLen = (kernel.length - 1) / this.interpolation + 2;
        history = new float[histLen];
        reset();
    }

    /** Drop all filter state (e.g. on stream restart) without rebuilding the kernel. */
    public void reset() {
        java.util.Arrays.fill(history, 0f);
        pos = 0;
        inCount = 0;
        nextT = 0;
    }

    public int interpolation() {
        return interpolation;
    }

    public int decimation() {
        return decimation;
    }

    public int numTaps() {
        return kernel.length;
    }

    /** Upper bound on the number of outputs one {@link #process} call can emit for n inputs. */
    public int maxOutputFor(int n) {
        return (int) ((long) n * interpolation / decimation) + 2;
    }

    /**
     * Push {@code n} input samples; write resampled output samples into {@code out} from
     * index 0. Produces about {@code n*L/M} outputs (never more than
     * {@link #maxOutputFor(int)}); {@code out} must be at least that large.
     *
     * @return the number of output samples written
     */
    public int process(float[] in, int n, float[] out) {
        int produced = 0;
        for (int i = 0; i < n; i++) {
            history[pos] = in[i];
            pos = (pos + 1 == histLen) ? 0 : pos + 1;
            inCount++;
            // Output sample k lives at upsampled index k*M; it is computable once the newest
            // input sample (upsampled index (inCount-1)*L) has arrived, because the FIR only
            // looks backward from there.
            long newestT = (inCount - 1) * interpolation;
            while (nextT <= newestT) {
                out[produced++] = interpolateAt(nextT);
                nextT += decimation;
            }
        }
        return produced;
    }

    // Evaluate the polyphase FIR at upsampled index t: with w[] the zero-stuffed input
    // (w[j*L] = x[j], 0 elsewhere), the output is sum_k h[k]*w[t-k]; only k = phase + m*L hit
    // non-zero samples, so we walk taps h[phase], h[phase+L], ... against x[t/L], x[t/L-1], ...
    private float interpolateAt(long t) {
        final int taps = kernel.length;
        long j = t / interpolation;              // newest input index used
        int k = (int) (t - j * interpolation);   // kernel phase
        int back = (int) (inCount - 1 - j);      // 0 == newest sample
        int idx = pos - 1 - back;
        while (idx < 0) idx += histLen;
        float acc = 0f;
        while (k < taps && j >= 0 && back < histLen) {
            acc += history[idx] * kernel[k];
            k += interpolation;
            j--;
            back++;
            idx = (idx == 0) ? histLen - 1 : idx - 1;
        }
        return acc;
    }

    private void buildKernel(int tapsPerPhase) {
        final int lm = Math.max(interpolation, decimation);
        final int n = 2 * tapsPerPhase * lm + 1;  // odd -> exact symmetry about center
        final int m = n - 1;
        // Cutoff below both Nyquists in cycles per *upsampled* sample: input Nyquist is 0.5/L,
        // output Nyquist is 0.5/M; 0.45/max keeps a small transition band under the binding
        // one. For 44.1k -> 12k (L=40, M=147) that is ~5.4 kHz — well above FT8's ~3 kHz top.
        final double fc = 0.45 / lm;

        kernel = new float[n];
        for (int k = 0; k < n; k++) {
            final double x = k - m / 2.0;
            final double sinc = (Math.abs(x) < 1e-9)
                    ? 2.0 * fc
                    : Math.sin(2.0 * Math.PI * fc * x) / (Math.PI * x);
            // Blackman window for a deep, smooth stopband.
            final double w = 0.42
                    - 0.5 * Math.cos(2.0 * Math.PI * k / m)
                    + 0.08 * Math.cos(4.0 * Math.PI * k / m);
            kernel[k] = (float) (sinc * w);
        }
        // Normalize each polyphase branch (taps k ≡ p mod L) to unity DC sum: every output
        // phase then has exactly unity DC gain, and the xL gain that compensates zero-stuffing
        // is baked in. For L == 1 this reduces to FirDecimator's whole-kernel normalization.
        for (int p = 0; p < interpolation; p++) {
            double sum = 0.0;
            for (int k = p; k < n; k += interpolation) {
                sum += kernel[k];
            }
            if (Math.abs(sum) < 1e-12) continue;
            final float norm = (float) (1.0 / sum);
            for (int k = p; k < n; k += interpolation) {
                kernel[k] *= norm;
            }
        }
    }
}
