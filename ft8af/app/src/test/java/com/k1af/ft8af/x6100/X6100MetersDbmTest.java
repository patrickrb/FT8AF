package com.k1af.ft8af.x6100;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link X6100Meters#getMeter_dBm(float)} — the conversion
 * from the Xiegu X6100's raw 0–242 S-meter count to a dBm reading shown in the
 * meter panel ({@link X6100Meters#toString()} calls it every render).
 *
 * <p>The radio pushes the raw count over the network (meter VITA stream, handled
 * by {@code X6100Meters.update} → {@code setValues} case 0), so every value here
 * is reachable from live radio data.
 *
 * <p>The class documents its own S-meter scale (header: {@code 0000=S0,
 * 0120=S9, 0242=S9+60dB}) and carries the inverse mapping
 * {@link X6100Meters#getMeters(float)}. Both fix the scale as
 * <ul>
 *   <li>{@code value=0}   → S0  = -129 dBm,
 *   <li>{@code value=120} → S9  =  -75 dBm,
 *   <li>{@code value=242} → S9+60 = -15 dBm.
 * </ul>
 * The upper branch (S9…S9+60) must therefore be continuous with the middle
 * branch at S9 = -75 dBm. It previously dropped the -75 dBm base offset, so a
 * signal above S9 read 75 dB too high — reporting physically impossible
 * <em>positive</em> dBm. These assertions pin the corrected, continuous scale.
 *
 * <p>{@code getMeter_dBm} is a pure static method, so no Robolectric runner is
 * needed.
 */
public class X6100MetersDbmTest {

    private static final float TOL = 0.1f;

    // ---- lower clamp ---------------------------------------------------------

    @Test
    public void zero_isFloorMinus150() {
        assertThat(X6100Meters.getMeter_dBm(0f)).isWithin(TOL).of(-150f);
    }

    @Test
    public void negative_isFloorMinus150() {
        assertThat(X6100Meters.getMeter_dBm(-5f)).isWithin(TOL).of(-150f);
    }

    // ---- S0..S9 branch (documented anchors) ----------------------------------

    @Test
    public void sZero_isMinus129dBm() {
        // value just above 0 approaches S0 = -129 dBm.
        assertThat(X6100Meters.getMeter_dBm(1f)).isWithin(0.5f).of(-129f);
    }

    @Test
    public void sNine_isMinus75dBm() {
        // value=120 is S9; middle branch => -75 dBm.
        assertThat(X6100Meters.getMeter_dBm(120f)).isWithin(TOL).of(-75f);
    }

    // ---- S9..S9+60 branch (the fix) ------------------------------------------

    @Test
    public void justAboveS9_staysContinuousWithMinus75Base() {
        // Regression: value=121 must be a hair above S9 (-75 dBm), NOT jump to ~0.
        // Correct: (121-120)*60/122 - 75 = -74.51 dBm.
        assertThat(X6100Meters.getMeter_dBm(121f)).isWithin(TOL).of(-74.51f);
    }

    @Test
    public void midUpperBranch_appliesMinus75Base() {
        // An S9+~30 signal (value=180), routine on HF.
        // Correct: (180-120)*60/122 - 75 = -45.49 dBm (buggy code returned +29.5).
        assertThat(X6100Meters.getMeter_dBm(180f)).isWithin(TOL).of(-45.49f);
    }

    @Test
    public void topOfUpperBranch_isS9Plus60() {
        // value=241 approaches S9+60 = -15 dBm.
        // Correct: (241-120)*60/122 - 75 = -15.49 dBm.
        assertThat(X6100Meters.getMeter_dBm(241f)).isWithin(TOL).of(-15.49f);
    }

    @Test
    public void upperBranchNeverReturnsPositiveDbm() {
        // A receiver S-meter can never legitimately report a positive dBm signal
        // on this scale (S9+60 == -15 dBm is the ceiling). Sweep the whole live
        // range and assert every reading stays below 0.
        for (float v = 1f; v < 242f; v += 1f) {
            assertThat(X6100Meters.getMeter_dBm(v)).isLessThan(0f);
        }
    }

    // ---- upper clamp ---------------------------------------------------------

    @Test
    public void atOrAbove242_isZeroSentinel() {
        // value>=242 falls through to the 0 sentinel (out-of-scale marker).
        assertThat(X6100Meters.getMeter_dBm(242f)).isEqualTo(0f);
        assertThat(X6100Meters.getMeter_dBm(300f)).isEqualTo(0f);
    }
}
