package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards {@link GeneralVariables#clampManualTimeCorrectionMs(int)} — the shared
 * clamp for the manual clock correction (ms) applied to {@code UtcTimer.delay}.
 *
 * <p>Regression: the live settings UI ({@code TimeSyncSettings.apply} →
 * {@code clampCorrectionMs}) clamps and persists this value to ±5000 ms, but the
 * config-hydration reload path ({@code DatabaseOpr}'s {@code timeCorrectionMs}
 * branch, run at every launch) used to clamp with a stale ±2000 ms bound. Any
 * correction the operator set beyond ±2 s was therefore silently truncated back
 * to 2 s on the next relaunch, leaving an offline (no-NTP) phone's clock offset
 * wrong by up to 3 s — and incorrect PC/phone time is the single most common
 * cause of FT8 decode failures. Centralizing the bound here and reusing it on the
 * reload path keeps the two paths in lock-step.
 *
 * <p>Robolectric because {@link GeneralVariables} carries Android LiveData statics
 * that must initialize before the static helper can be exercised.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesTimeCorrectionClampTest {

    @Test
    public void inRangeValues_areReturnedUnchanged() {
        // Byte-identical for every value the settings UI can produce.
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(0)).isEqualTo(0);
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(500)).isEqualTo(500);
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(-1500)).isEqualTo(-1500);
    }

    @Test
    public void correctionBeyondTwoSeconds_survivesTheReloadClamp() {
        // The load-bearing regression: these all used to be truncated to ±2000.
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(3000)).isEqualTo(3000);
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(-3000)).isEqualTo(-3000);
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(4500)).isEqualTo(4500);
    }

    @Test
    public void boundaryValues_areKept() {
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(
                GeneralVariables.MANUAL_TIME_CORRECTION_MAX_MS))
                .isEqualTo(GeneralVariables.MANUAL_TIME_CORRECTION_MAX_MS);
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(
                GeneralVariables.MANUAL_TIME_CORRECTION_MIN_MS))
                .isEqualTo(GeneralVariables.MANUAL_TIME_CORRECTION_MIN_MS);
    }

    @Test
    public void outOfRangeValues_areClampedToTheBounds() {
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(6000))
                .isEqualTo(GeneralVariables.MANUAL_TIME_CORRECTION_MAX_MS);
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(-6000))
                .isEqualTo(GeneralVariables.MANUAL_TIME_CORRECTION_MIN_MS);
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(Integer.MAX_VALUE))
                .isEqualTo(GeneralVariables.MANUAL_TIME_CORRECTION_MAX_MS);
        assertThat(GeneralVariables.clampManualTimeCorrectionMs(Integer.MIN_VALUE))
                .isEqualTo(GeneralVariables.MANUAL_TIME_CORRECTION_MIN_MS);
    }

    @Test
    public void bounds_matchTheLiveUiRangeAndAreSymmetric() {
        // Must equal TimeCorrection.kt's authoritative ±5 s range (kept in sync by
        // documentation). If a future edit narrows the reload bound again, this and
        // the truncation test above fail.
        assertThat(GeneralVariables.MANUAL_TIME_CORRECTION_MAX_MS).isEqualTo(5000);
        assertThat(GeneralVariables.MANUAL_TIME_CORRECTION_MIN_MS).isEqualTo(-5000);
    }
}
