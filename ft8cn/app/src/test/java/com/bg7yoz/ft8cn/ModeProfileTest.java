package com.bg7yoz.ft8cn;

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
        assertThat(m.slotTenths).isEqualTo(150);
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
        assertThat(m.slotTenths).isEqualTo(75);
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
        assertThat(m.slotMillis).isEqualTo(3_800);
        assertThat(m.slotTenths).isEqualTo(38);
        // FT2 reuses FT4's 105-symbol layout (same encoder/tones).
        assertThat(m.numTones).isEqualTo(105);
        // FT2 decodes as FT4-family (isFt8 false), but receives on the from-source decoder.
        assertThat(m.isFt8).isFalse();
        // Half FT4's symbol period -> double baud.
        assertThat(m.symbolPeriod).isEqualTo(0.024f);
        // 105 * 0.024 * 1000 = 2520ms audio; slack = 3800 - 2520.
        assertThat(m.audioMillis).isEqualTo(2_520);
        assertThat(m.audioSlackMillis).isEqualTo(1_280);
    }

    @Test
    public void usesFt2Decoder_onlyTrueForFt2() {
        assertThat(ModeProfile.FT8.usesFt2Decoder()).isFalse();
        assertThat(ModeProfile.FT4.usesFt2Decoder()).isFalse();
        assertThat(ModeProfile.FT2.usesFt2Decoder()).isTrue();
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
