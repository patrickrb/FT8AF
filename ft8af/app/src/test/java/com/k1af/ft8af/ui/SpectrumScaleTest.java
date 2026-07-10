package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.FT8Common;

import org.junit.Test;

/**
 * Tests for {@link SpectrumScale}: the RX spectrum/waterfall frequency-axis
 * geometry extracted from ColumnarView and WaterfallView.
 *
 * <p>The bug fixed here: both views used {@code GeneralVariables.audioSampleRate}
 * (the user-selectable <em>transmit</em> rate, 12/24/48 kHz) as the FFT Nyquist,
 * but the RX FFT is always computed over 12 kHz audio ({@link FT8Common#SAMPLE_RATE}).
 * Selecting 24/48 kHz therefore mis-scaled the whole display by 2x/4x. These
 * tests pin the axis to the fixed RX Nyquist and prove it no longer depends on
 * the transmit rate, while staying bit-for-bit identical at the 12 kHz default.
 *
 * <p>Pure math — no Robolectric.
 */
public class SpectrumScaleTest {

    /** The pre-fix ColumnarView expression, parameterized by the (wrong) rate. */
    private static int legacyBinsToShow(int fftLength, int spectrumWidthHz, int assumedRateHz) {
        float nyquist = assumedRateHz / 2f;
        int bins = Math.min(fftLength,
                Math.round((float) spectrumWidthHz / nyquist * fftLength));
        if (bins <= 0) {
            bins = fftLength / 2;
        }
        return bins;
    }

    @Test
    public void rxNyquistIsFixedTwelveKilohertzHalf() {
        assertThat(SpectrumScale.RX_NYQUIST_HZ).isEqualTo(FT8Common.SAMPLE_RATE / 2f);
        assertThat(SpectrumScale.RX_NYQUIST_HZ).isEqualTo(6000f);
    }

    @Test
    public void binsToShowMatchesLegacyAtTwelveKilohertz() {
        // At the 12 kHz default the fix must be a no-op (assumed rate == RX rate).
        int[] widths = {2500, 3000, 3500, 4000, 5000};
        int[] lengths = {480, 960, 1024};
        for (int len : lengths) {
            for (int w : widths) {
                assertThat(SpectrumScale.binsToShow(len, w))
                        .isEqualTo(legacyBinsToShow(len, w, 12000));
            }
        }
    }

    @Test
    public void binsToShowIsIndependentOfTransmitRate() {
        // The core regression: 24 kHz and 48 kHz TX selections must NOT change
        // the RX spectrum geometry — the old code shrank it to 1/2 and 1/4.
        int fftLen = 960;
        int spectrumWidth = 3500;
        int fixed = SpectrumScale.binsToShow(fftLen, spectrumWidth);

        assertThat(fixed).isEqualTo(560); // round(3500/6000 * 960)
        assertThat(legacyBinsToShow(fftLen, spectrumWidth, 24000)).isEqualTo(280); // old, wrong
        assertThat(legacyBinsToShow(fftLen, spectrumWidth, 48000)).isEqualTo(140); // old, wrong
        // Fixed value equals the correct 12 kHz value regardless of TX rate.
        assertThat(fixed).isEqualTo(legacyBinsToShow(fftLen, spectrumWidth, 12000));
    }

    @Test
    public void signalWithinSpectrumWidthStaysOnStrip() {
        // A 1500 Hz signal (bin 240 of a 960-bin 0..6000 Hz FFT) sits well inside
        // a 3500 Hz display window and must be among the drawn bins. Under the old
        // 48 kHz scaling only 140 bins (0..875 Hz) were drawn, so it fell off.
        int fftLen = 960;
        int spectrumWidth = 3500;
        int binForFifteenHundredHz = Math.round(1500f / SpectrumScale.RX_NYQUIST_HZ * fftLen);

        assertThat(binForFifteenHundredHz).isEqualTo(240);
        assertThat(SpectrumScale.binsToShow(fftLen, spectrumWidth)).isGreaterThan(binForFifteenHundredHz);
        assertThat(legacyBinsToShow(fftLen, spectrumWidth, 48000)).isLessThan(binForFifteenHundredHz);
    }

    @Test
    public void binsToShowClampsToFftLength() {
        // spectrumWidth above the RX Nyquist can't show more bins than exist.
        assertThat(SpectrumScale.binsToShow(512, 9000)).isEqualTo(512);
        assertThat(SpectrumScale.binsToShow(512, 6000)).isEqualTo(512);
    }

    @Test
    public void binsToShowFallsBackToHalfWhenRoundsToZero() {
        // Degenerate width rounds to zero bins → fall back to half the array,
        // preserving the pre-fix ColumnarView guard (never draw zero bars).
        assertThat(SpectrumScale.binsToShow(960, 0)).isEqualTo(480);
    }

    @Test
    public void gradientScaleMatchesLegacyAtTwelveKilohertzAndIsRateIndependent() {
        int spectrumWidth = 3500;
        float fixed = SpectrumScale.gradientScale(spectrumWidth);

        assertThat(fixed).isEqualTo(6000f / spectrumWidth);          // RX Nyquist / width
        assertThat(fixed).isEqualTo(12000 / 2f / spectrumWidth);     // == old 12 kHz value
        // Old code at 48 kHz produced 24000/3500, ~4x too large (over-compressed).
        assertThat(24000f / spectrumWidth).isGreaterThan(fixed);
    }
}
