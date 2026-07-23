package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tests for {@link DatabaseOpr#parseConfigInt} (PR #429 review): the FFT
 * display knobs are hydrated from config values that may come from
 * hand-edited or stale backups, so a non-numeric string must fall back to
 * the default instead of throwing NumberFormatException and crashing startup
 * hydration. Robolectric because DatabaseOpr extends SQLiteOpenHelper.
 *
 * <p>The {@code audioRate}, {@code dataBits}, {@code stopBits} and
 * {@code parityBits} hydration keys were later migrated to this same helper
 * (they were the last hydration parses with no guard at all — a raw
 * {@code Integer.parseInt(result)} that crashed on both an empty and a
 * non-numeric value from an imported backup, bricking the app on every
 * relaunch until its data was cleared). The cases below pin the fallback
 * contract for each of their documented defaults.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprParseConfigIntTest {

    @Test
    public void parsesValidIntegers() {
        assertThat(DatabaseOpr.parseConfigInt("0", 9)).isEqualTo(0);
        assertThat(DatabaseOpr.parseConfigInt("4", 9)).isEqualTo(4);
        assertThat(DatabaseOpr.parseConfigInt("-2", 9)).isEqualTo(-2);
        assertThat(DatabaseOpr.parseConfigInt(" 3 ", 9)).isEqualTo(3); // tolerates stray whitespace
    }

    @Test
    public void emptyAndNullFallBack() {
        assertThat(DatabaseOpr.parseConfigInt("", 7)).isEqualTo(7);
        assertThat(DatabaseOpr.parseConfigInt(null, 7)).isEqualTo(7);
    }

    @Test
    public void nonNumericFallsBackInsteadOfThrowing() {
        assertThat(DatabaseOpr.parseConfigInt("hann", 1)).isEqualTo(1);
        assertThat(DatabaseOpr.parseConfigInt("2.5", 0)).isEqualTo(0);
        assertThat(DatabaseOpr.parseConfigInt("99999999999999999999", 0)).isEqualTo(0); // overflow
    }

    /**
     * audioRate/dataBits/stopBits/parityBits: a valid stored value is honored
     * exactly as the old raw parse did (no behavior change on good input)...
     */
    @Test
    public void serialAndAudioRateKeys_honorValidStoredValues() {
        assertThat(DatabaseOpr.parseConfigInt("48000", 12000)).isEqualTo(48000); // audioRate
        assertThat(DatabaseOpr.parseConfigInt("7", 8)).isEqualTo(7);             // dataBits
        assertThat(DatabaseOpr.parseConfigInt("2", 1)).isEqualTo(2);             // stopBits
        assertThat(DatabaseOpr.parseConfigInt("1", 0)).isEqualTo(1);             // parityBits
    }

    /**
     * ...while an empty or non-numeric value (as an imported/hand-edited backup
     * can carry) now falls back to each key's documented default instead of
     * throwing NumberFormatException out of hydration.
     */
    @Test
    public void serialAndAudioRateKeys_fallBackToDefaultsOnBadInput() {
        // empty (the value that made these keys crash where their guarded
        // siblings did not)
        assertThat(DatabaseOpr.parseConfigInt("", 12000)).isEqualTo(12000); // audioRate default
        assertThat(DatabaseOpr.parseConfigInt("", 8)).isEqualTo(8);         // dataBits default
        assertThat(DatabaseOpr.parseConfigInt("", 1)).isEqualTo(1);         // stopBits default
        assertThat(DatabaseOpr.parseConfigInt("", 0)).isEqualTo(0);         // parityBits default
        // non-numeric garbage
        assertThat(DatabaseOpr.parseConfigInt("48k", 12000)).isEqualTo(12000);
        assertThat(DatabaseOpr.parseConfigInt("eight", 8)).isEqualTo(8);
        assertThat(DatabaseOpr.parseConfigInt("none", 0)).isEqualTo(0);
    }

    // --- Radix / long / float overloads --------------------------------------
    // The remaining empty-guarded hydration parses (civ hex, bandFreq long,
    // volumeValue float, and ~20 more decimal-int keys) were migrated to these
    // same crash-safe helpers: they threw on a non-empty, non-numeric imported
    // backup value where the fixed-4 already crashed on empty. Each overload
    // keeps the empty/non-numeric -> fallback contract.

    @Test
    public void radixOverload_parsesHexAndFallsBack() {
        assertThat(DatabaseOpr.parseConfigInt("a4", 0xa4, 16)).isEqualTo(0xa4); // civ default value
        assertThat(DatabaseOpr.parseConfigInt("5e", 0xa4, 16)).isEqualTo(0x5e); // valid hex honored
        assertThat(DatabaseOpr.parseConfigInt(" 5e ", 0xa4, 16)).isEqualTo(0x5e); // tolerates whitespace
        assertThat(DatabaseOpr.parseConfigInt("", 0xa4, 16)).isEqualTo(0xa4);   // empty -> fallback
        assertThat(DatabaseOpr.parseConfigInt(null, 0xa4, 16)).isEqualTo(0xa4); // null -> fallback
        assertThat(DatabaseOpr.parseConfigInt("zz", 0xa4, 16)).isEqualTo(0xa4); // non-hex -> fallback
    }

    @Test
    public void longOverload_parsesAndFallsBack() {
        assertThat(DatabaseOpr.parseConfigLong("14074000", 14074000L)).isEqualTo(14074000L);
        assertThat(DatabaseOpr.parseConfigLong("7074000", 14074000L)).isEqualTo(7074000L);
        assertThat(DatabaseOpr.parseConfigLong(" 7074000 ", 14074000L)).isEqualTo(7074000L);
        // A value that overflows int but is a valid long must be honored (why long, not int).
        assertThat(DatabaseOpr.parseConfigLong("2400000000", 14074000L)).isEqualTo(2400000000L);
        assertThat(DatabaseOpr.parseConfigLong("", 14074000L)).isEqualTo(14074000L);     // empty
        assertThat(DatabaseOpr.parseConfigLong(null, 14074000L)).isEqualTo(14074000L);   // null
        assertThat(DatabaseOpr.parseConfigLong("14.074MHz", 14074000L)).isEqualTo(14074000L); // garbage
    }

    @Test
    public void floatOverload_parsesAndFallsBack() {
        assertThat(DatabaseOpr.parseConfigFloat("50", 100f)).isEqualTo(50f);
        assertThat(DatabaseOpr.parseConfigFloat("100", 100f)).isEqualTo(100f);
        assertThat(DatabaseOpr.parseConfigFloat(" 50 ", 100f)).isEqualTo(50f);
        assertThat(DatabaseOpr.parseConfigFloat("", 100f)).isEqualTo(100f);   // empty -> fallback
        assertThat(DatabaseOpr.parseConfigFloat(null, 100f)).isEqualTo(100f); // null -> fallback
        assertThat(DatabaseOpr.parseConfigFloat("loud", 100f)).isEqualTo(100f); // garbage -> fallback
    }

    /**
     * volumeValue hydrates as {@code parseConfigFloat(result, 100f) / 100f}: the
     * pre-scaling 100f fallback must land the empty/garbage case on unity (1.0f).
     */
    @Test
    public void volumeValue_scalingKeepsUnityFallback() {
        assertThat(DatabaseOpr.parseConfigFloat("", 100f) / 100f).isEqualTo(1.0f);     // empty
        assertThat(DatabaseOpr.parseConfigFloat("loud", 100f) / 100f).isEqualTo(1.0f); // garbage
        assertThat(DatabaseOpr.parseConfigFloat("50", 100f) / 100f).isEqualTo(0.5f);   // valid honored
    }

    /**
     * The "iaruRegion" config key is hydrated as
     * {@code regionFromNumber(parseConfigInt(value, 2)).number}. Pin that composition:
     * any stored value — absent, junk, or out of the 1..3 range — must land on a legal
     * region rather than throwing or leaving the field with a nonsense number that the
     * QSY band gating would then read.
     */
    @Test
    public void iaruRegionHydrationAlwaysYieldsALegalRegion() {
        assertThat(hydrateIaruRegion("1")).isEqualTo(1);
        assertThat(hydrateIaruRegion("3")).isEqualTo(3);
        // Unset / junk / out of range all degrade to region 2 (the Americas).
        assertThat(hydrateIaruRegion("")).isEqualTo(2);
        assertThat(hydrateIaruRegion(null)).isEqualTo(2);
        assertThat(hydrateIaruRegion("region one")).isEqualTo(2);
        assertThat(hydrateIaruRegion("0")).isEqualTo(2);
        assertThat(hydrateIaruRegion("7")).isEqualTo(2);
        assertThat(hydrateIaruRegion("-1")).isEqualTo(2);
    }

    /** Mirrors the "iaruRegion" branch of DatabaseOpr's config hydration loop. */
    private static int hydrateIaruRegion(String stored) {
        return com.k1af.ft8af.message.SpecialMessage
                .regionFromNumber(DatabaseOpr.parseConfigInt(stored, 2)).number;
    }
}
