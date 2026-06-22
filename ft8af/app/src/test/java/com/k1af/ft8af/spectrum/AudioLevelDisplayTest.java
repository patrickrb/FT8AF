package com.k1af.ft8af.spectrum;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.spectrum.AudioInputLevel.Reading;

import org.junit.Test;

/**
 * Coverage for the meter's label/colour mapping. Builds readings through the real
 * {@link AudioInputLevel#compute} so the status thresholds and the display mapping are tested
 * together.
 */
public class AudioLevelDisplayTest {

    private static float[] constant(float magnitude, int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = (i % 2 == 0) ? magnitude : -magnitude;
        }
        return a;
    }

    @Test
    public void goodReading_showsRoundedRmsInDb_green() {
        Reading r = AudioInputLevel.compute(constant(0.1f, 256)); // -20 dBFS, GOOD
        assertThat(AudioLevelDisplay.label(r)).isEqualTo("RX -20 dB");
        assertThat(AudioLevelDisplay.color(r)).isEqualTo(AudioLevelDisplay.COLOR_GOOD);
    }

    @Test
    public void clipping_showsClipWarning_red() {
        Reading r = AudioInputLevel.compute(constant(1.0f, 64));
        assertThat(AudioLevelDisplay.label(r)).isEqualTo("RX CLIP");
        assertThat(AudioLevelDisplay.color(r)).isEqualTo(AudioLevelDisplay.COLOR_CLIP);
    }

    @Test
    public void silence_showsDash_grey() {
        Reading r = AudioInputLevel.compute(new float[0]);
        assertThat(AudioLevelDisplay.label(r)).isEqualTo("RX --");
        assertThat(AudioLevelDisplay.color(r)).isEqualTo(AudioLevelDisplay.COLOR_SILENT);
    }

    @Test
    public void low_isAmber() {
        Reading r = AudioInputLevel.compute(constant(0.005f, 256)); // ~ -46 dBFS, LOW
        assertThat(AudioLevelDisplay.color(r)).isEqualTo(AudioLevelDisplay.COLOR_LOW);
        assertThat(AudioLevelDisplay.label(r)).startsWith("RX ");
    }

    @Test
    public void high_isOrange() {
        float[] a = constant(0.001f, 256);
        a[10] = 0.8f; // -1.9 dBFS peak, HIGH
        Reading r = AudioInputLevel.compute(a);
        assertThat(AudioLevelDisplay.color(r)).isEqualTo(AudioLevelDisplay.COLOR_HIGH);
    }
}
