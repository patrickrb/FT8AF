package com.k1af.ft8af.x6100;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link X6100Meters#getMeter_volt(float)} — the Xiegu
 * X6100 supply-voltage meter conversion.
 *
 * <p>The device sends a raw 0..255 meter value; the class header documents the
 * calibration as {@code 0000=0V, 0075=5V, 0241=16V}. The low segment (0..75)
 * must therefore be linear from 0V to 5V (slope 5/75 = 1/15). The previous
 * implementation divided by 25 instead of 15, so a raw value of 75 read 3.0V
 * instead of 5.0V and every reading below the 5V knee under-reported the supply
 * — and the two segments no longer met at the knee.
 *
 * <p>No Robolectric runner: {@code getMeter_volt} is pure arithmetic and never
 * touches Android types.
 */
public class X6100MetersVoltTest {

    private static final float TOL = 0.02f;

    @Test
    public void zero_isZeroVolts() {
        assertThat(X6100Meters.getMeter_volt(0f)).isWithin(TOL).of(0.0f);
    }

    @Test
    public void knee_at75_is5Volts() {
        // Regression: the old 1/25 divisor returned 3.0V here.
        assertThat(X6100Meters.getMeter_volt(75f)).isWithin(TOL).of(5.0f);
    }

    @Test
    public void lowSegment_midpoint_isHalfOf5Volts() {
        // 37.5 -> 2.5V on the 0..75 -> 0..5V ramp (old code gave 1.5V).
        assertThat(X6100Meters.getMeter_volt(37.5f)).isWithin(TOL).of(2.5f);
    }

    @Test
    public void lowSegment_isContinuousWithUpperSegmentAtKnee() {
        // Both branches must agree at the knee (value == 75) so the reading
        // does not jump. The old low branch (3.0V) broke this.
        float low = X6100Meters.getMeter_volt(75f);
        float high = X6100Meters.getMeter_volt(75.0001f);
        assertThat(low).isWithin(0.01f).of(high);
    }

    @Test
    public void upperCalibrationPoint_at241_is16Volts() {
        assertThat(X6100Meters.getMeter_volt(241f)).isWithin(TOL).of(16.0f);
    }

    @Test
    public void upperSegment_midpoint() {
        // Halfway between the 75 (5V) and 241 (16V) points -> 10.5V.
        assertThat(X6100Meters.getMeter_volt(158f)).isWithin(TOL).of(10.5f);
    }

    @Test
    public void isMonotonicallyIncreasing() {
        float prev = X6100Meters.getMeter_volt(0f);
        for (int v = 1; v <= 255; v++) {
            float cur = X6100Meters.getMeter_volt(v);
            assertThat(cur).isGreaterThan(prev);
            prev = cur;
        }
    }
}
