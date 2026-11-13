package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards {@link GeneralVariables#setSpectrumWidth(int)} against storing a
 * non-positive (or otherwise out-of-range) spectrum display width.
 *
 * <p>{@code spectrumWidth} is a display-only value (the RX audio is always
 * captured/decoded at {@link FT8Common#SAMPLE_RATE}; see {@code SpectrumScale}).
 * The waterfall/spectrum views divide the view pixel width by it to place the
 * TX marker and message labels ({@code WaterfallView.freq_width =
 * (float) w / spectrumWidth}, {@code ColumnarView}), and it drives
 * click-to-tune. The settings UI constrains it to [{@value
 * GeneralVariables#MIN_SPECTRUM_WIDTH_HZ}, {@value
 * GeneralVariables#MAX_SPECTRUM_WIDTH_HZ}] Hz, but config hydration
 * ({@code DatabaseOpr}: {@code parseConfigInt(result, 3500)}) reaches this
 * setter with whatever a hand-edited/corrupted settings backup persisted — with
 * no range check. A stored {@code 0} made {@code freq_width} {@code Infinity}
 * (and a negative value flipped the axis), so the TX marker, message labels, and
 * click-to-tune produced {@code Infinity}/{@code NaN}/mirrored coordinates until
 * the config was fixed. {@code SpectrumScale}'s own javadoc says the range check
 * is "left to setters" — this makes the setter honour that contract, mirroring
 * the sibling {@link GeneralVariables#setFftWindowType(int)} clamp.
 *
 * <p>Robolectric because {@link GeneralVariables} carries Android LiveData
 * statics that must initialize before the static setter can be exercised.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesSpectrumWidthTest {

    private int original;

    @Before
    public void saveOriginal() {
        original = GeneralVariables.getSpectrumWidth();
    }

    @After
    public void restoreOriginal() {
        GeneralVariables.setSpectrumWidth(original);
    }

    @Test
    public void inRangeValue_isStoredUnchanged() {
        // Byte-identical for every value the settings UI can produce.
        GeneralVariables.setSpectrumWidth(3000);
        assertThat(GeneralVariables.getSpectrumWidth()).isEqualTo(3000);

        GeneralVariables.setSpectrumWidth(GeneralVariables.MIN_SPECTRUM_WIDTH_HZ);
        assertThat(GeneralVariables.getSpectrumWidth())
                .isEqualTo(GeneralVariables.MIN_SPECTRUM_WIDTH_HZ);

        GeneralVariables.setSpectrumWidth(GeneralVariables.MAX_SPECTRUM_WIDTH_HZ);
        assertThat(GeneralVariables.getSpectrumWidth())
                .isEqualTo(GeneralVariables.MAX_SPECTRUM_WIDTH_HZ);
    }

    @Test
    public void zero_isClampedToMin_soFreqWidthIsFinite() {
        // Regression: a persisted "0" used to be stored verbatim, making
        // WaterfallView.freq_width = w / 0 == +Infinity.
        GeneralVariables.setSpectrumWidth(0);
        assertThat(GeneralVariables.getSpectrumWidth())
                .isEqualTo(GeneralVariables.MIN_SPECTRUM_WIDTH_HZ);
        assertThat(GeneralVariables.getSpectrumWidth()).isGreaterThan(0);
    }

    @Test
    public void negative_isClampedToMin() {
        GeneralVariables.setSpectrumWidth(-100);
        assertThat(GeneralVariables.getSpectrumWidth())
                .isEqualTo(GeneralVariables.MIN_SPECTRUM_WIDTH_HZ);
    }

    @Test
    public void belowMin_isClampedToMin() {
        GeneralVariables.setSpectrumWidth(GeneralVariables.MIN_SPECTRUM_WIDTH_HZ - 1);
        assertThat(GeneralVariables.getSpectrumWidth())
                .isEqualTo(GeneralVariables.MIN_SPECTRUM_WIDTH_HZ);
    }

    @Test
    public void absurdlyLargeValue_isClampedToMax() {
        GeneralVariables.setSpectrumWidth(999_999_999);
        assertThat(GeneralVariables.getSpectrumWidth())
                .isEqualTo(GeneralVariables.MAX_SPECTRUM_WIDTH_HZ);
    }

    @Test
    public void bounds_arePositiveAndOrdered() {
        assertThat(GeneralVariables.MIN_SPECTRUM_WIDTH_HZ).isGreaterThan(0);
        assertThat(GeneralVariables.MAX_SPECTRUM_WIDTH_HZ)
                .isGreaterThan(GeneralVariables.MIN_SPECTRUM_WIDTH_HZ);
    }
}
