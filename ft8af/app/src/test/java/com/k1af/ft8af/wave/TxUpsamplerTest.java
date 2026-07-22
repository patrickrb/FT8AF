package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Guards the band-limited TX upsampler used by the USB-direct (libusb) output path in
 * {@link UsbAudioDevice#writeAudio}. The FT8 waveform is generated at 12 kHz and the USB audio
 * device streams at a higher rate — commonly 48 kHz (a 4x upsample), which is what most of the
 * cases below use; {@code handlesNonIntegerRatio44100} covers a 44.1 kHz device, where the ratio
 * reduces to 147/40.
 *
 * <p>The bug this covers (distorted TX audio with the Yaesu FT-710): the old path used naive
 * linear interpolation, whose triangular kernel leaves the spectral images of the 12 kHz-sampled
 * tone only lightly attenuated. For a ~1500 Hz FT8 tone those images land at 12000 - 1500 =
 * 10500 Hz and 12000 + 1500 = 13500 Hz — inside the 48 kHz output band — and ride into the
 * radio's modulator as harmonic distortion, even though the OS-resampled phone-speaker path stays
 * clean. {@link TxUpsampler} replaces the linear interpolator with the same Blackman-windowed-sinc
 * polyphase kernel already used on the capture side, which rejects those images.
 */
public class TxUpsamplerTest {

    private static final int SRC_RATE = 12000;   // FT8 generator rate
    private static final int DST_RATE = 48000;   // USB audio device rate

    private static float[] tone(int rate, double freqHz, double seconds) {
        int n = (int) (rate * seconds);
        float[] in = new float[n];
        for (int i = 0; i < n; i++) {
            in[i] = (float) Math.sin(2.0 * Math.PI * freqHz * i / rate);
        }
        return in;
    }

    /** Naive linear-interpolation upsampler — the code being replaced, kept here as a control. */
    private static float[] linearResample(float[] input, int fromRate, int toRate) {
        if (fromRate == toRate) return input;
        double ratio = (double) toRate / fromRate;
        int outputLen = (int) (input.length * ratio);
        float[] output = new float[outputLen];
        for (int i = 0; i < outputLen; i++) {
            double srcIndex = i / ratio;
            int idx = (int) srcIndex;
            double frac = srcIndex - idx;
            if (idx + 1 < input.length) {
                output[i] = (float) (input[idx] * (1 - frac) + input[idx + 1] * frac);
            } else if (idx < input.length) {
                output[i] = input[idx];
            }
        }
        return output;
    }

    /** Single-bin magnitude via Goertzel, normalized so a unit-amplitude tone reads ~1.0. */
    private static double toneMag(float[] x, double freqHz, int rate) {
        double w = 2.0 * Math.PI * freqHz / rate;
        double cw = Math.cos(w);
        double sw = Math.sin(w);
        double coeff = 2.0 * cw;
        double s1 = 0.0;
        double s2 = 0.0;
        for (float v : x) {
            double s0 = v + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double re = s1 - s2 * cw;
        double im = s2 * sw;
        return Math.sqrt(re * re + im * im) / (x.length / 2.0);
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

    private static float steadyPeak(float[] out) {
        float peak = 0f;
        for (int i = out.length / 2; i < out.length; i++) {
            peak = Math.max(peak, Math.abs(out[i]));
        }
        return peak;
    }

    @Test
    public void passesThroughWhenRatesEqual() {
        float[] in = tone(SRC_RATE, 1000.0, 0.1);
        assertThat(TxUpsampler.resample(in, SRC_RATE, SRC_RATE)).isSameInstanceAs(in);
    }

    @Test
    public void quadruplesLengthFor12kTo48k() {
        float[] in = tone(SRC_RATE, 1500.0, 1.0);
        float[] out = TxUpsampler.resample(in, SRC_RATE, DST_RATE);
        assertThat(out.length).isEqualTo(in.length * 4);
    }

    @Test
    public void preservesToneFrequencyAndAmplitude() {
        float[] out = TxUpsampler.resample(tone(SRC_RATE, 1500.0, 1.0), SRC_RATE, DST_RATE);
        assertThat(zeroCrossFreq(out, DST_RATE)).isWithin(5.0).of(1500.0);
        assertThat((double) steadyPeak(out)).isWithin(0.05).of(1.0);
    }

    @Test
    public void rejectsSpectralImagesThatLinearInterpolationLeaves() {
        float[] in = tone(SRC_RATE, 1500.0, 1.0);

        // Control: naive linear interpolation leaves a clearly audible image at 12000 - 1500 Hz.
        float[] linear = linearResample(in, SRC_RATE, DST_RATE);
        double linearFund = toneMag(linear, 1500.0, DST_RATE);
        double linearImage = toneMag(linear, 10500.0, DST_RATE);
        // The image the field bug is about is real and non-trivial on the old path.
        assertThat(linearImage / linearFund).isGreaterThan(0.02);

        // Band-limited polyphase upsampler: same fundamental, image rejected by > 40 dB.
        float[] out = TxUpsampler.resample(in, SRC_RATE, DST_RATE);
        double fund = toneMag(out, 1500.0, DST_RATE);
        double image = toneMag(out, 10500.0, DST_RATE);
        assertThat((double) fund).isWithin(0.05).of(1.0);
        assertThat(image / fund).isLessThan(0.01);
    }

    @Test
    public void handlesNonIntegerRatio44100() {
        // A device that only streams 44.1 kHz reduces to L/M = 147/40; the tone must survive.
        float[] in = tone(SRC_RATE, 1500.0, 1.0);
        float[] out = TxUpsampler.resample(in, SRC_RATE, 44100);
        assertThat(out.length).isEqualTo((int) ((long) in.length * 147 / 40));
        assertThat(zeroCrossFreq(out, 44100)).isWithin(5.0).of(1500.0);
    }

    @Test
    public void guardsDegenerateRates() {
        float[] in = tone(SRC_RATE, 1000.0, 0.05);
        assertThat(TxUpsampler.resample(in, 0, DST_RATE)).isSameInstanceAs(in);
        assertThat(TxUpsampler.resample(in, SRC_RATE, 0)).isSameInstanceAs(in);
    }
}
