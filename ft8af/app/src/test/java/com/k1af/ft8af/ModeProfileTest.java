package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for ModeProfile. The encode() dispatch needs the native lib so it
 * isn't exercised here; this verifies the descriptor table (timings, tone counts, derived
 * slack) and the id lookup that the rest of the app keys off.
 */
public class ModeProfileTest {

    @Test
    public void ft8_descriptorMatchesSpec() {
        ModeProfile m = ModeProfile.FT8;
        assertThat(m.id).isEqualTo(FT8Common.FT8_MODE);
        assertThat(m.displayName).isEqualTo("FT8");
        assertThat(m.slotMillis).isEqualTo(15_000);
        assertThat(m.numTones).isEqualTo(79);
        assertThat(m.isFt8).isTrue();
        // 79 * 0.160 * 1000 = 12640ms audio; slack = 15000 - 12640.
        assertThat(m.audioMillis).isEqualTo(12_640);
        assertThat(m.audioSlackMillis).isEqualTo(2_360);
    }

    @Test
    public void ft4_descriptorMatchesSpec() {
        ModeProfile m = ModeProfile.FT4;
        assertThat(m.id).isEqualTo(FT8Common.FT4_MODE);
        assertThat(m.displayName).isEqualTo("FT4");
        assertThat(m.slotMillis).isEqualTo(7_500);
        assertThat(m.numTones).isEqualTo(105);
        assertThat(m.isFt8).isFalse();
        // 105 * 0.048 * 1000 = 5040ms audio; slack = 7500 - 5040.
        assertThat(m.audioMillis).isEqualTo(5_040);
        assertThat(m.audioSlackMillis).isEqualTo(2_460);
    }

    @Test
    public void ft2_descriptorMatchesSpec() {
        ModeProfile m = ModeProfile.FT2;
        assertThat(m.id).isEqualTo(FT8Common.FT2_MODE);
        assertThat(m.displayName).isEqualTo("FT2");
        // FT2's slot is exactly half FT4's 7.5s, so 3750ms.
        assertThat(m.slotMillis).isEqualTo(3_750);
        assertThat(m.slotMillis).isEqualTo(ModeProfile.FT4.slotMillis / 2);
        // FT2 reuses FT4's 105-symbol layout (same encoder/tones).
        assertThat(m.numTones).isEqualTo(105);
        // FT2 decodes as FT4-family (isFt8 false), but receives on the from-source decoder.
        assertThat(m.isFt8).isFalse();
        // Half FT4's symbol period -> double baud.
        assertThat(m.symbolPeriod).isEqualTo(0.024f);
        // 105 * 0.024 * 1000 = 2520ms audio; slack = 3750 - 2520.
        assertThat(m.audioMillis).isEqualTo(2_520);
        assertThat(m.audioSlackMillis).isEqualTo(1_230);
    }

    @Test
    public void usesFromSourceDecoder_trueForFt2AndFt4() {
        // FT8 stays on the prebuilt; FT2 (no prebuilt protocol) and FT4 (prebuilt SNR is
        // miscalibrated) both run on the from-source decoder.
        assertThat(ModeProfile.FT8.usesFromSourceDecoder()).isFalse();
        assertThat(ModeProfile.FT4.usesFromSourceDecoder()).isTrue();
        assertThat(ModeProfile.FT2.usesFromSourceDecoder()).isTrue();
    }

    @Test
    public void ftxProtocol_matchesNativeEnumOrder() {
        // ftx_protocol_t in constants.h: FT4 = 0, FT8 = 1, FT2 = 2.
        assertThat(ModeProfile.FT4.ftxProtocol()).isEqualTo(0);
        assertThat(ModeProfile.FT8.ftxProtocol()).isEqualTo(1);
        assertThat(ModeProfile.FT2.ftxProtocol()).isEqualTo(2);
    }

    @Test
    public void deepDecodeBudget_scalesWithSlotAndHasFloor() {
        // 0.75x the slot, so the subtract loop uses most of the cycle without spilling into
        // the next decode. FT8: 0.75 * 15000 = 11250; FT4: 0.75 * 7500 = 5625.
        assertThat(ModeProfile.FT8.deepDecodeBudgetMillis()).isEqualTo(11_250);
        assertThat(ModeProfile.FT4.deepDecodeBudgetMillis()).isEqualTo(5_625);
        // FT2: 0.75 * 3750 = 2812.5 -> below the 2500 floor it would round to 2813, which is
        // still above the floor, so the computed value wins here.
        assertThat(ModeProfile.FT2.deepDecodeBudgetMillis()).isEqualTo(2_813);
        // Every mode's budget must stay under its own slot so deep decode can't overrun the
        // cycle (the old flat 7000ms was ~2x FT2's 3750ms slot).
        for (ModeProfile m : ModeProfile.values()) {
            assertThat(m.deepDecodeBudgetMillis()).isAtLeast(2_500);
            assertThat(m.deepDecodeBudgetMillis()).isLessThan(m.slotMillis);
        }
    }

    @Test
    public void fromId_resolvesKnownIds() {
        assertThat(ModeProfile.fromId(FT8Common.FT8_MODE)).isEqualTo(ModeProfile.FT8);
        assertThat(ModeProfile.fromId(FT8Common.FT4_MODE)).isEqualTo(ModeProfile.FT4);
        assertThat(ModeProfile.fromId(FT8Common.FT2_MODE)).isEqualTo(ModeProfile.FT2);
    }

    @Test
    public void fromId_unknownId_fallsBackToFt8() {
        // Forward-compat: a future build's persisted mode id (e.g. 2 for "FT2") must
        // not crash an older build.
        assertThat(ModeProfile.fromId(99)).isEqualTo(ModeProfile.FT8);
    }
}
