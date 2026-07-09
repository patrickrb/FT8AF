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
}
