package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards {@link GeneralVariables#isInteger(String)} against reporting {@code true}
 * for digit strings that overflow {@code int}.
 *
 * <p>{@code isInteger} is used as the sole guard immediately before an
 * {@code Integer.parseInt} / Kotlin {@code toInt()} in the ICOM/Xiegu network
 * port fields ({@code ConnectionDialogs.IcomLoginDialog} and the legacy
 * {@code LoginIcomRadioDialog}), both of which run on the UI thread with no
 * try/catch. The previous implementation returned {@code true} for any all-digit
 * string via {@code "^[0-9]*$"}, so pasting/typing an over-{@code int} value such
 * as {@code "9999999999"} passed the guard and then crashed the app inside the
 * text-change handler. The guard must not lie about parseability.
 *
 * <p>Robolectric because {@link GeneralVariables} carries Android LiveData statics
 * that must initialize before the static helper can be exercised.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesIsIntegerTest {

    @Test
    public void plainInRangeInteger_isTrue() {
        assertThat(GeneralVariables.isInteger("0")).isTrue();
        assertThat(GeneralVariables.isInteger("50002")).isTrue();
        assertThat(GeneralVariables.isInteger("2147483647")).isTrue(); // Integer.MAX_VALUE
    }

    @Test
    public void leadingZeros_stillParse_isTrue() {
        assertThat(GeneralVariables.isInteger("007")).isTrue();
    }

    @Test
    public void overIntRangeDigits_isFalse_andDoNotCrashCallers() {
        // Regression: these are all digits yet overflow int, so the old
        // "^[0-9]*$" guard returned true and the caller's toInt()/parseInt() threw.
        assertThat(GeneralVariables.isInteger("2147483648")).isFalse(); // MAX_VALUE + 1
        assertThat(GeneralVariables.isInteger("9999999999")).isFalse();
        assertThat(GeneralVariables.isInteger("99999999999999999999")).isFalse();

        // The whole point of the guard: a value it accepts must be parseable.
        assertThat(Integer.parseInt("2147483647")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    public void nonDigitOrEmpty_isFalse() {
        assertThat(GeneralVariables.isInteger(null)).isFalse();
        assertThat(GeneralVariables.isInteger("")).isFalse();
        assertThat(GeneralVariables.isInteger("   ")).isFalse();
        assertThat(GeneralVariables.isInteger("12a")).isFalse();
        assertThat(GeneralVariables.isInteger("-5")).isFalse(); // sign rejected, as before
        assertThat(GeneralVariables.isInteger("1.0")).isFalse();
    }
}
