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
}
