package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Java port of the assertions in {@code cpp/test_fir_decimator.cpp}: the {@link FirDecimator}
 * used by the {@code UsbRequest} fallback capture path must behave like the native FIR that
 * replaced the box filter — otherwise devices that fall off the libusb path silently regress
 * to the aliasing-prone downsampler (the pre-#319 noise floor).
 */
public class FirDecimatorTest {

    private static final int RATE_IN = 48000;
    private static final int RATIO = 4;

    /** RMS of a decimated pure tone, for gain measurements. */
    private static double decimatedToneRms(FirDecimator dec, double freqHz) {
        int n = RATE_IN; // 1 second
        float[] in = new float[n];
        for (int i = 0; i < n; i++) {
            in[i] = (float) Math.sin(2.0 * Math.PI * freqHz * i / RATE_IN);
        }
        float[] out = new float[n / dec.ratio() + 1];
        int produced = dec.process(in, n, out);
        // Skip the filter's warm-up (one kernel length of output samples).
        int skip = dec.numTaps() / dec.ratio() + 1;
        double sum = 0;
        int counted = 0;
        for (int i = skip; i < produced; i++) {
            sum += (double) out[i] * out[i];
            counted++;
        }
        return Math.sqrt(sum / counted);
    }

    @Test
    public void producesQuarterOfInputSamples() {
        FirDecimator dec = new FirDecimator(RATIO);
        float[] in = new float[4000];
        float[] out = new float[in.length / RATIO + 1];
        assertThat(dec.process(in, in.length, out)).isEqualTo(1000);
        assertThat(dec.ratio()).isEqualTo(RATIO);
    }

    @Test
    public void kernelIsOddLengthForSymmetry() {
        FirDecimator dec = new FirDecimator(RATIO);
        assertThat(dec.numTaps() % 2).isEqualTo(1);
    }

    @Test
    public void unityDcGain() {
        FirDecimator dec = new FirDecimator(RATIO);
        int n = 8 * dec.numTaps();
        float[] in = new float[n];
        java.util.Arrays.fill(in, 1.0f);
        float[] out = new float[n / RATIO + 1];
        int produced = dec.process(in, n, out);
        // After warm-up every output must be ~1.0.
        assertThat((double) out[produced - 1]).isWithin(1e-3).of(1.0);
    }

    @Test
    public void passbandTonePassesNearUnity() {
        // 1 kHz is deep inside the FT8 passband; expected RMS of a unit sine is 1/sqrt(2).
        double rms = decimatedToneRms(new FirDecimator(RATIO), 1000.0);
        assertThat(rms).isWithin(0.05).of(1.0 / Math.sqrt(2.0));
    }

    @Test
    public void aliasToneIsStronglyRejected() {
        // 9 kHz aliases to 3 kHz after /4 decimation — dead center of the FT8 band.
        // The box filter this replaces only managed ~10 dB here; require >= 40 dB.
        double gain = decimatedToneRms(new FirDecimator(RATIO), 9000.0) / (1.0 / Math.sqrt(2.0));
        assertThat(gain).isLessThan(0.01);
    }

    @Test
    public void streamingAcrossBuffersMatchesSingleCall() {
        // Feed the same signal in one call vs many small chunks; outputs must be identical
        // (state carries across process() calls — the property the capture loop relies on).
        int n = 4800;
        float[] in = new float[n];
        for (int i = 0; i < n; i++) {
            in[i] = (float) Math.sin(2.0 * Math.PI * 700.0 * i / RATE_IN);
        }
        FirDecimator whole = new FirDecimator(RATIO);
        float[] outWhole = new float[n / RATIO + 1];
        int producedWhole = whole.process(in, n, outWhole);

        FirDecimator chunked = new FirDecimator(RATIO);
        float[] outChunks = new float[n / RATIO + 1];
        int producedChunks = 0;
        int chunk = 97; // deliberately not a multiple of the ratio
        for (int off = 0; off < n; off += chunk) {
            int len = Math.min(chunk, n - off);
            float[] piece = java.util.Arrays.copyOfRange(in, off, off + len);
            float[] out = new float[len / RATIO + 2];
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
    public void decimationRatioForMirrorsNativeGuards() {
        assertThat(FirDecimator.decimationRatioFor(48000, 12000)).isEqualTo(4);
        assertThat(FirDecimator.decimationRatioFor(48000, 0)).isEqualTo(1);      // zero target
        assertThat(FirDecimator.decimationRatioFor(48000, -1)).isEqualTo(1);     // negative target
        assertThat(FirDecimator.decimationRatioFor(8000, 12000)).isEqualTo(1);   // no upsample
        assertThat(FirDecimator.decimationRatioFor(44100, 12000)).isEqualTo(3);  // floors
        assertThat(FirDecimator.decimationRatioFor(12000, 12000)).isEqualTo(1);  // equal rates
    }
}
