package com.k1af.ft8af.ui;

/**
 * Pure geometry helpers for mapping between a spectrum/waterfall view's pixel
 * space and audio frequency in Hz.
 *
 * <p>Extracted so the touch → frequency conversion the tuning cursor uses can be
 * unit-tested without pulling in the Android runtime, and so both
 * {@link ColumnarView} and {@link WaterfallView} apply exactly the same math.
 *
 * <p>Historically each view derived {@code freq_hz} inside its {@code onDraw()},
 * meaning a tap handler that read {@code getFreq_hz()} right after
 * {@code setTouch_x(x)} got the frequency from the previous frame (issue #782:
 * "sometimes the blue line is not in the middle of the red ones"). Doing the
 * conversion in the setter — via this helper — keeps the tap handler and the
 * TX marker draw in agreement, so the blue tap cursor is always centered
 * between the red TX bandwidth markers.
 */
public final class SpectrumTouchMath {
    private SpectrumTouchMath() {}

    /**
     * Convert a horizontal touch coordinate on a spectrum view into the audio
     * frequency (Hz) the touched column represents.
     *
     * @param touchX       pointer x coordinate in view pixels
     * @param viewWidth    the view's rendered width in pixels
     * @param spectrumWidth the audio frequency span mapped across the view (Hz)
     * @return the audio frequency at {@code touchX}, or {@code -1} if the inputs
     *         are invalid (view not laid out yet, or touch not on screen)
     */
    public static int touchToFreqHz(int touchX, int viewWidth, int spectrumWidth) {
        if (touchX < 0 || viewWidth <= 0 || spectrumWidth <= 0) {
            return -1;
        }
        return Math.round((float) spectrumWidth * (float) touchX / (float) viewWidth);
    }

    /**
     * Inverse of {@link #touchToFreqHz}: place a frequency at its horizontal
     * pixel position in the spectrum view. Used to anchor the TX bandwidth
     * markers so they bracket the same column the tap frequency lives in.
     *
     * @return the pixel x, or {@code -1} if the inputs are invalid
     */
    public static float freqHzToPixelX(float freqHz, int viewWidth, int spectrumWidth) {
        if (viewWidth <= 0 || spectrumWidth <= 0) {
            return -1f;
        }
        return freqHz * (float) viewWidth / (float) spectrumWidth;
    }
}
