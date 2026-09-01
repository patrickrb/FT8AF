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
    public void touchToFreqHz_leftEdgeClampsToMinTxAudio() {
        // Unclamped this was 0, which every touch handler discards
        // (`if (freqHz > 0)`) — so a left-edge tap silently did nothing.
        assertThat(SpectrumTouchMath.touchToFreqHz(0, 1000, 3500))
                .isEqualTo(SpectrumTouchMath.MIN_TX_AUDIO_HZ);
    }

    @Test
    public void touchToFreqHz_rightEdgeClampsBelowSpectrumWidth() {
        // Unclamped this committed 3500 Hz as the base frequency, a value the
        // audio-frequency editor caps at spectrumWidth - 100.
        assertThat(SpectrumTouchMath.touchToFreqHz(1000, 1000, 3500)).isEqualTo(3400);
    }

    @Test
    public void touchToFreqHz_clampsBothEndsToTheEditorRange() {
        // The whole point of the clamp: every value a tap can produce is one
        // the audio-frequency editor would also accept.
        int viewWidth = 1080;
        int spectrumWidth = 3500;
        for (int touchX = 0; touchX <= viewWidth; touchX += 9) {
            int freq = SpectrumTouchMath.touchToFreqHz(touchX, viewWidth, spectrumWidth);
            assertThat(freq).isAtLeast(SpectrumTouchMath.MIN_TX_AUDIO_HZ);
            assertThat(freq).isAtMost(spectrumWidth - SpectrumTouchMath.TX_AUDIO_TOP_MARGIN_HZ);
        }
    }

    @Test
    public void touchToFreqHz_beyondRightEdgeIsRejected() {
        // A drag that ran off the right edge is not a position on the
        // spectrum; -1 makes the handlers ignore it rather than commit the
        // clamped ceiling as if the user had tapped there.
        assertThat(SpectrumTouchMath.touchToFreqHz(1001, 1000, 3500)).isEqualTo(-1);
    }

    @Test
    public void touchToFreqHz_narrowSpectrumDoesNotInvertTheRange() {
        // Defensive: a span narrower than the two 100 Hz margins must not
        // produce a ceiling below the floor.
        assertThat(SpectrumTouchMath.touchToFreqHz(1000, 1000, 150))
                .isEqualTo(SpectrumTouchMath.MIN_TX_AUDIO_HZ);
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
        // Only frequencies inside the clamped TX range round-trip; 0 and 3500
        // are deliberately no longer reachable from a tap.
        for (int freq : new int[]{100, 1000, 2500, 3400}) {
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
