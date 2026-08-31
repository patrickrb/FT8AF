package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link SpectrumTouchMath}. Pure math, no Android runtime — the
 * whole point of extracting this helper is that the tap-to-frequency mapping
 * can be verified without spinning up Robolectric or a device emulator.
 */
public class SpectrumTouchMathTest {

    @Test
    public void touchToFreqHz_leftEdgeMapsToZero() {
        assertThat(SpectrumTouchMath.touchToFreqHz(0, 1000, 3500)).isEqualTo(0);
    }

    @Test
    public void touchToFreqHz_rightEdgeMapsToSpectrumWidth() {
        assertThat(SpectrumTouchMath.touchToFreqHz(1000, 1000, 3500)).isEqualTo(3500);
    }

    @Test
    public void touchToFreqHz_midpointRoundsToHalfSpan() {
        assertThat(SpectrumTouchMath.touchToFreqHz(500, 1000, 3500)).isEqualTo(1750);
    }

    @Test
    public void touchToFreqHz_returnsMinusOneForUnlaidView() {
        // Compose's AndroidView can call setTouch_x before onSizeChanged has
        // measured the view; guarding against a divide-by-zero here keeps the
        // sequence tap → getFreq_hz() safe during that window.
        assertThat(SpectrumTouchMath.touchToFreqHz(100, 0, 3500)).isEqualTo(-1);
    }

    @Test
    public void touchToFreqHz_returnsMinusOneForZeroSpectrum() {
        assertThat(SpectrumTouchMath.touchToFreqHz(100, 1000, 0)).isEqualTo(-1);
    }

    @Test
    public void touchToFreqHz_returnsMinusOneForNegativeTouch() {
        // setTouch_x(-1) is used to clear the cursor; the derived frequency
        // must clear too so getFreq_hz() doesn't leak the previous position.
        assertThat(SpectrumTouchMath.touchToFreqHz(-1, 1000, 3500)).isEqualTo(-1);
    }

    @Test
    public void freqHzToPixelX_isInverseOfTouchToFreqHz_atExactMultiples() {
        // Choose values that divide cleanly so no rounding artifacts creep in.
        int viewWidth = 3500;
        int spectrumWidth = 3500;
        for (int freq : new int[]{0, 100, 1000, 2500, 3500}) {
            float px = SpectrumTouchMath.freqHzToPixelX(freq, viewWidth, spectrumWidth);
            assertThat(SpectrumTouchMath.touchToFreqHz(Math.round(px), viewWidth, spectrumWidth))
                    .isEqualTo(freq);
        }
    }

    @Test
    public void freqHzToPixelX_returnsMinusOneForUnlaidView() {
        assertThat(SpectrumTouchMath.freqHzToPixelX(1000f, 0, 3500)).isEqualTo(-1f);
    }

    @Test
    public void freqHzToPixelX_returnsMinusOneForZeroSpectrum() {
        assertThat(SpectrumTouchMath.freqHzToPixelX(1000f, 1000, 0)).isEqualTo(-1f);
    }

    @Test
    public void redMarkersStayCenteredOnBlueCursor() {
        // Regression for issue #782: after the user taps, the TX bandwidth
        // markers (red) must bracket the tap cursor (blue) symmetrically. With
        // both mappings sharing the same formula, the midpoint between the
        // ±halfBw red markers always lands on the tap column.
        int viewWidth = 1080;
        int spectrumWidth = 3500;
        float halfBw = 25f; // 50 Hz FT8 signal, half-bandwidth
        for (int touchX : new int[]{50, 200, 540, 800, 1030}) {
            int freq = SpectrumTouchMath.touchToFreqHz(touchX, viewWidth, spectrumWidth);
            float blueX = SpectrumTouchMath.freqHzToPixelX(freq, viewWidth, spectrumWidth);
            float leftRedX = SpectrumTouchMath.freqHzToPixelX(freq - halfBw, viewWidth, spectrumWidth);
            float rightRedX = SpectrumTouchMath.freqHzToPixelX(freq + halfBw, viewWidth, spectrumWidth);
            float midOfReds = (leftRedX + rightRedX) / 2f;
            assertThat(midOfReds).isWithin(0.5f).of(blueX);
        }
    }
}
