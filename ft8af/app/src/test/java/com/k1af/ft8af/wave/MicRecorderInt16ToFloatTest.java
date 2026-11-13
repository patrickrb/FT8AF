package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link MicRecorder#int16ToFloat} — the PCM16-to-float conversion the
 * capture reader loop applies to every buffer now that {@code MicRecorder}
 * requests {@code ENCODING_PCM_16BIT} instead of {@code ENCODING_PCM_FLOAT}
 * (see that field's own comment for the real-hardware reason: requesting
 * float capture from a USB input silently produced unusable samples on the
 * MediaTek audio HAL, confirmed by reproducing the fix/break twice in a
 * single app process with everything else held constant).
 */
public class MicRecorderInt16ToFloatTest {

    @Test
    public void convertsFullScalePositive() {
        short[] src = {32767};
        float[] dest = new float[1];
        MicRecorder.int16ToFloat(src, 1, dest);
        assertThat(dest[0]).isEqualTo(1.0f);
    }

    @Test
    public void convertsFullScaleNegative() {
        // -32768 is short's true minimum, but float2Short()'s own inverse
        // scale (elsewhere in the app, the TX path) uses 32767.0 as the
        // divisor for both directions, so -32768 lands just past -1.0f —
        // matching that existing convention rather than a separate one here.
        short[] src = {-32767};
        float[] dest = new float[1];
        MicRecorder.int16ToFloat(src, 1, dest);
        assertThat(dest[0]).isEqualTo(-1.0f);
    }

    @Test
    public void convertsSilence() {
        short[] src = {0};
        float[] dest = new float[1];
        MicRecorder.int16ToFloat(src, 1, dest);
        assertThat(dest[0]).isEqualTo(0.0f);
    }

    @Test
    public void onlyConvertsUpToLength_leavesRestOfDestUntouched() {
        // The reader loop passes bufferReadResult, not the full buffer length
        // (a short/partial read is normal) — dest entries past that count
        // must be left alone, not zeroed or garbage-filled.
        short[] src = {32767, 32767, 32767};
        float[] dest = {-9f, -9f, -9f};
        MicRecorder.int16ToFloat(src, 1, dest);
        assertThat(dest[0]).isEqualTo(1.0f);
        assertThat(dest[1]).isEqualTo(-9f);
        assertThat(dest[2]).isEqualTo(-9f);
    }
}
