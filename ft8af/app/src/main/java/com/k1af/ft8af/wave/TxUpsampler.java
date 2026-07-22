package com.k1af.ft8af.wave;

/**
 * Band-limited one-shot resampler for the USB-direct (libusb) TX output path in
 * {@link UsbAudioDevice#writeAudio}.
 *
 * <p>Why it exists (distorted TX audio with the Yaesu FT-710): the FT8 waveform is generated at
 * 12 kHz and the USB audio device streams at 48 kHz, so writeAudio() must upsample 4x. The old
 * path did this with naive linear interpolation. A linear interpolator is a convolution with a
 * triangular kernel, whose frequency response ({@code sinc^2}) only lightly attenuates the
 * spectral images of the 12 kHz-sampled tone. For a ~1500 Hz FT8 tone the first images land at
 * {@code 12000 - 1500 = 10500} Hz and {@code 12000 + 1500 = 13500} Hz — inside the 48 kHz output
 * band — and ride into the radio's modulator as harmonic distortion. The phone-speaker path
 * stays clean because Android/the OS USB driver resamples it with a proper band-limited filter;
 * only the app's own direct-libusb path used the crude interpolator.
 *
 * <p>The fix reuses the same Blackman-windowed-sinc polyphase kernel already used (and
 * host-tested) on the capture side ({@link RationalResampler}): interpolate by L, low-pass,
 * decimate by M for the exact reduced ratio {@code toRate/fromRate}. The stopband sits well below
 * FT8's ~3 kHz top, so the images are rejected by > 40 dB and the transmitted tone is clean.
 * Covered by {@code TxUpsamplerTest}.
 */
public final class TxUpsampler {

    private TxUpsampler() {}

    /**
     * Resample mono full-scale float PCM from {@code fromRate} to {@code toRate} with a
     * band-limited polyphase filter. Returns a buffer of exactly {@code input.length * L / M}
     * samples, where {@code L/M} is the reduced {@code toRate/fromRate} ratio, group-delay
     * compensated so output sample 0 lines up with input sample 0 (the FT8 leading Costas sync
     * array must not be shifted or clipped). Returns the input array unchanged when the rates
     * match or either rate is non-positive.
     */
    public static float[] resample(float[] input, int fromRate, int toRate) {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || input.length == 0) {
            return input;
        }

        final int g = gcd(toRate, fromRate);
        final int l = toRate / g;   // interpolation factor
        final int m = fromRate / g; // decimation factor

        final RationalResampler rs = new RationalResampler(l, m);
        final int targetLen = (int) ((long) input.length * l / m);

        // The FIR is symmetric, so the output lags the input by (numTaps - 1) / 2 upsampled
        // samples, i.e. (numTaps - 1) / (2 * M) output samples. Push that many extra output
        // samples out by feeding trailing zeros, then drop them off the front so output[0]
        // corresponds to input[0] rather than to the filter's ramp-up.
        final int groupDelayOut = (rs.numTaps() - 1) / (2 * m);
        final int flushInputs = (int) Math.ceil((double) groupDelayOut * m / l) + 2;
        final int fedLen = input.length + flushInputs;

        final float[] fed = java.util.Arrays.copyOf(input, fedLen); // pads with trailing zeros
        final float[] tmp = new float[rs.maxOutputFor(fedLen)];
        final int produced = rs.process(fed, fedLen, tmp);

        final float[] out = new float[targetLen];
        final int copy = Math.min(targetLen, produced - groupDelayOut);
        if (copy > 0) {
            System.arraycopy(tmp, groupDelayOut, out, 0, copy);
        }
        return out;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
