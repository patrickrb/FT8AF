package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards {@link GeneralVariables#getShortCallsign(String)} against an
 * {@link ArrayIndexOutOfBoundsException} on all-slash callsigns.
 *
 * <p>{@code "/".split("/")} and {@code "//".split("/")} both return a zero-length
 * array (Java strips trailing empty tokens), yet the method unconditionally read
 * {@code temp[max_index]} — index 0 into an empty array. This is reached with the
 * operator's own persisted callsign on the config-load background thread
 * ({@code DatabaseOpr} ReadConfig) and on every decode cycle via
 * {@code checkIsMyCallsign(...)}, so a stray-slash callsign turned into an
 * uncaught crash that recurred on every launch.
 *
 * <p>Robolectric because {@link GeneralVariables} carries Android LiveData statics
 * that must initialize before the pure string helper can be exercised.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesShortCallsignTest {

    @Test
    public void plainCallsign_returnedUnchanged() {
        assertThat(GeneralVariables.getShortCallsign("W1AW")).isEqualTo("W1AW");
    }

    @Test
    public void compoundCallsign_returnsLongestSegment() {
        // prefix and suffix forms both collapse to the base callsign
        assertThat(GeneralVariables.getShortCallsign("DL/W1AW")).isEqualTo("W1AW");
        assertThat(GeneralVariables.getShortCallsign("W1AW/P")).isEqualTo("W1AW");
        assertThat(GeneralVariables.getShortCallsign("W1AW/QRP")).isEqualTo("W1AW");
    }

    @Test
    public void allSlashCallsign_doesNotThrow_returnsOriginal() {
        // Regression: these previously threw ArrayIndexOutOfBoundsException.
        assertThat(GeneralVariables.getShortCallsign("/")).isEqualTo("/");
        assertThat(GeneralVariables.getShortCallsign("//")).isEqualTo("//");
        assertThat(GeneralVariables.getShortCallsign("///")).isEqualTo("///");
    }

    @Test
    public void leadingSlash_returnsLongestNonEmptySegment() {
        // "/A".split("/") keeps the leading empty token -> ["", "A"]; longest is "A".
        assertThat(GeneralVariables.getShortCallsign("/W1AW")).isEqualTo("W1AW");
    }
}
