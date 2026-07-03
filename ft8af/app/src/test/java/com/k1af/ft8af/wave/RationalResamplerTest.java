package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Java port of the assertions in {@code cpp/test_rational_resampler.cpp}: the
 * {@link RationalResampler} used by the {@code UsbRequest} fallback capture path must behave
 * like the native polyphase resampler in {@code usb_audio_capture.cpp} (issue #364). The bug
 * this guards against: a 44.1 kHz-only device used to get its ratio floored (44100/12000 -> 3),
 * producing 14.7 kHz audio labeled 12 kHz — every FT8 tone off the 6.25 Hz grid, zero decodes.
 */
public class RationalResamplerTest {

    private static final int IN_RATE = 44100;
    private static final int OUT_RATE = 12000;
    private static final int L = 40;
    private static final int M = 147;

    private static float[] tone(int rate, double freqHz, double seconds) {
        int n = (int) (rate * seconds);
        float[] in = new float[n];
        for (int i = 0; i < n; i++) {
            in[i] = (float) Math.sin(2.0 * Math.PI * freqHz * i / rate);
        }
        return in;
    }

    private static float[] resampled(float[] in, RationalResampler rs) {
        float[] out = new float[rs.maxOutputFor(in.length)];
        int produced = rs.process(in, in.length, out);
        return java.util.Arrays.copyOf(out, produced);
    }

    /** Peak amplitude over the back half of the output (skips filter warm-up). */
    private static float steadyPeak(float[] out) {
        float peak = 0f;
        for (int i = out.length / 2; i < out.length; i++) {
            peak = Math.max(peak, Math.abs(out[i]));
        }
        return peak;
    }

    /** Dominant frequency of the back half via zero-crossing count (clean single tone). */
    private static double zeroCrossFreq(float[] out, int outRate) {
        int start = out.length / 2;
        int crossings = 0;
        for (int i = start + 1; i < out.length; i++) {
            if ((out[i - 1] < 0f) != (out[i] < 0f)) crossings++;
        }
        double seconds = (double) (out.length - start) / outRate;
        return crossings / (2.0 * seconds);
    }

    @Test
    public void ratioForReducesByGcd() {
        assertThat(RationalResampler.ratioFor(48000, 12000)).isEqualTo(new int[] {1, 4});
        assertThat(RationalResampler.ratioFor(44100, 12000)).isEqualTo(new int[] {40, 147});
        assertThat(RationalResampler.ratioFor(96000, 12000)).isEqualTo(new int[] {1, 8});
        assertThat(RationalResampler.ratioFor(32000, 12000)).isEqualTo(new int[] {3, 8});
    }

    @Test
    public void ratioForGuardsDegenerateRates() {
        assertThat(RationalResampler.ratioFor(48000, 0)).isEqualTo(new int[] {1, 1});
        assertThat(RationalResampler.ratioFor(48000, -12000)).isEqualTo(new int[] {1, 1});
        assertThat(RationalResampler.ratioFor(8000, 12000)).isEqualTo(new int[] {1, 1});
        assertThat(RationalResampler.ratioFor(12000, 12000)).isEqualTo(new int[] {1, 1});
    }

    @Test
    public void ratioIsExact_outputRateEqualsTarget() {
        // The whole point of #364: inputRate * L / M must equal the target rate exactly,
        // with no rounding — the floored integer ratio was off by 22.5% at 44.1 kHz.
        int[] lm = RationalResampler.ratioFor(IN_RATE, OUT_RATE);
        assertThat((long) IN_RATE * lm[0] % lm[1]).isEqualTo(0);
        assertThat((long) IN_RATE * lm[0] / lm[1]).isEqualTo(OUT_RATE);
    }

    @Test
    public void exactOutputCountOverTenSeconds_noDrift() {
        RationalResampler rs = new RationalResampler(L, M);
        int n = IN_RATE * 10;
        float[] in = new float[n];
        float[] out = new float[rs.maxOutputFor(n)];
        int produced = rs.process(in, n, out);
        // Outputs live at upsampled indices 0, M, 2M, ... <= (n-1)*L.
        long expected = (long) (n - 1) * L / M + 1;  // exactly 120000
        assertThat(expected).isEqualTo(OUT_RATE * 10L);
        assertThat((long) produced).isEqualTo(expected);
    }

    @Test
    public void unityDcGainOnAllOutputPhases() {
        RationalResampler rs = new RationalResampler(L, M);
        float[] in = new float[IN_RATE];
        java.util.Arrays.fill(in, 1.0f);
        float[] out = resampled(in, rs);
        // Per-branch kernel normalization must make every output phase exactly unity at DC.
        for (int i = out.length / 2; i < out.length; i++) {
            assertThat((double) out[i]).isWithin(1e-3).of(1.0);
        }
    }

    @Test
    public void tone1500HzStays1500Hz() {
        // The core regression: through the old floored /3 path a 1500 Hz tone came out at
        // ~1224 Hz (1500 * 12000/14700). The rational path must preserve it.
        float[] out = resampled(tone(IN_RATE, 1500.0, 1.0), new RationalResampler(L, M));
        assertThat(zeroCrossFreq(out, OUT_RATE)).isWithin(5.0).of(1500.0);
        assertThat((double) steadyPeak(out)).isWithin(0.05).of(1.0);
    }

    @Test
    public void tone2500HzStays2500Hz() {
        float[] out = resampled(tone(IN_RATE, 2500.0, 1.0), new RationalResampler(L, M));
        assertThat(zeroCrossFreq(out, OUT_RATE)).isWithin(5.0).of(2500.0);
    }

    @Test
    public void aliasToneIsStronglyRejected() {
        // 10 kHz at 44.1k folds to |10000 - 12000| = 2 kHz — mid-FT8-band — without the
        // low-pass. Require > 40 dB rejection.
        float[] out = resampled(tone(IN_RATE, 10000.0, 1.0), new RationalResampler(L, M));
        assertThat(steadyPeak(out)).isLessThan(0.01f);
    }

    @Test
    public void streamingAcrossBuffersMatchesSingleCall() {
        // The capture loop feeds ~1 ms USB packets; state must carry across process() calls.
        int n = IN_RATE / 5;
        float[] in = tone(IN_RATE, 700.0, 0.2);
        RationalResampler whole = new RationalResampler(L, M);
        float[] outWhole = new float[whole.maxOutputFor(n)];
        int producedWhole = whole.process(in, n, outWhole);

        RationalResampler chunked = new RationalResampler(L, M);
        float[] outChunks = new float[chunked.maxOutputFor(n)];
        int producedChunks = 0;
        int chunk = 97;  // deliberately not aligned to L or M
        for (int off = 0; off < n; off += chunk) {
            int len = Math.min(chunk, n - off);
            float[] piece = java.util.Arrays.copyOfRange(in, off, off + len);
            float[] out = new float[chunked.maxOutputFor(len)];
            int p = chunked.process(piece, len, out);
            System.arraycopy(out, 0, outChunks, producedChunks, p);
            producedChunks += p;
        }
        assertThat(producedChunks).isEqualTo(producedWhole);
        for (int i = 0; i < producedWhole; i++) {
            assertThat(outChunks[i]).isEqualTo(outWhole[i]);
        }
    }

    @Test
    public void integerRatioStillWorksThroughRationalPath() {
        // L=1 degenerates to a plain FIR decimator (the capture path uses FirDecimator for
        // this case, but the rational kernel must still be correct if configured that way).
        RationalResampler rs = new RationalResampler(1, 4);
        float[] out = resampled(tone(48000, 1000.0, 0.5), rs);
        assertThat(out.length).isEqualTo(48000 / 2 / 4);
        assertThat(zeroCrossFreq(out, 12000)).isWithin(5.0).of(1000.0);
    }
}
