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
     * Lowest audio frequency a tap may select, in Hz. Matches the {@code min}
     * of the audio-frequency editor in RadioAudioSettings, so a tap and a typed
     * value can never disagree about what the rig will accept.
     */
    public static final int MIN_TX_AUDIO_HZ = 100;

    /**
     * Headroom kept below {@code spectrumWidth} for the same reason: the editor
     * caps the audio frequency at {@code spectrumWidth - 100} so a full 50 Hz
     * FT8 signal still fits inside the passband at the top of the span.
     */
    public static final int TX_AUDIO_TOP_MARGIN_HZ = 100;

    /**
     * Convert a horizontal touch coordinate on a spectrum view into the audio
     * frequency (Hz) the touched column represents.
     *
     * <p>The result is clamped to the transmittable range
     * {@code [MIN_TX_AUDIO_HZ, spectrumWidth - TX_AUDIO_TOP_MARGIN_HZ]} — the
     * same range the audio-frequency editor enforces. WaterfallView used to
     * apply that clamp inline in {@code onDraw()}; folding it in here keeps it
     * when the math moved out, and extends it to ColumnarView, which never had
     * it. Without the clamp a left-edge tap yields 0 (which the touch handlers
     * discard, so the tap does nothing) and a right-edge tap commits
     * {@code spectrumWidth} — e.g. 3500 Hz — as the base frequency even though
     * the editor would refuse to accept that value.
     *
     * @param touchX       pointer x coordinate in view pixels
     * @param viewWidth    the view's rendered width in pixels
     * @param spectrumWidth the audio frequency span mapped across the view (Hz)
     * @return the audio frequency at {@code touchX}, or {@code -1} if the inputs
     *         are invalid (view not laid out yet, or touch not on screen — which
     *         includes a drag that ran off either edge of the view)
     */
    public static int touchToFreqHz(int touchX, int viewWidth, int spectrumWidth) {
        if (touchX < 0 || viewWidth <= 0 || spectrumWidth <= 0 || touchX > viewWidth) {
            return -1;
        }
        int raw = Math.round((float) spectrumWidth * (float) touchX / (float) viewWidth);
        // Math.max guards a pathologically narrow span (< 200 Hz) that would
        // otherwise put the ceiling below the floor. The spectrum-width editor
        // enforces >= 2500 Hz, so this is belt-and-braces.
        int max = Math.max(MIN_TX_AUDIO_HZ, spectrumWidth - TX_AUDIO_TOP_MARGIN_HZ);
        return Math.min(Math.max(raw, MIN_TX_AUDIO_HZ), max);
    }

    /**
     * Whether the blue tap cursor should be drawn for the frequency the last
     * touch selected. Only the frequency decides: the cleared state is
     * {@code setTouch_x(-1)}, which {@link #touchToFreqHz} turns into -1. The
     * views used to also require {@code touch_x > 0}, which hid the cursor for
     * a touch at exactly x == 0 even though the clamp above accepts it as
     * {@link #MIN_TX_AUDIO_HZ} and the touch handlers commit it — the red
     * markers moved to 100 Hz with no blue line between them.
     */
    public static boolean hasTapCursor(int freqHz) {
        return freqHz > 0;
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
