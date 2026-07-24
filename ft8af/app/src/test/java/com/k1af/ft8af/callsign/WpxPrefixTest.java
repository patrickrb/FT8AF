package com.k1af.ft8af.callsign;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link WpxPrefix#of(String)} — the CQ WPX prefix extractor that
 * backs the decode list's "New Prefix" highlight/filter. Pure logic, no runner.
 */
public class WpxPrefixTest {

    @Test
    public void simpleCalls() {
        assertThat(WpxPrefix.of("W1AW")).isEqualTo("W1");
        assertThat(WpxPrefix.of("K5XYZ")).isEqualTo("K5");
        assertThat(WpxPrefix.of("VE3ABC")).isEqualTo("VE3");
        assertThat(WpxPrefix.of("EA8XYZ")).isEqualTo("EA8");
        assertThat(WpxPrefix.of("PY2AA")).isEqualTo("PY2");
        assertThat(WpxPrefix.of("KH6ABC")).isEqualTo("KH6");
    }

    @Test
    public void leadingDigitPrefixes() {
        // Countries whose prefix starts with a digit keep every char up to and
        // including the last prefix numeral.
        assertThat(WpxPrefix.of("9A1AA")).isEqualTo("9A1");
        assertThat(WpxPrefix.of("4X4AAA")).isEqualTo("4X4");
        assertThat(WpxPrefix.of("3DA0RS")).isEqualTo("3DA0");
    }

    @Test
    public void caseAndWhitespaceInsensitive() {
        assertThat(WpxPrefix.of("  ve3abc  ")).isEqualTo("VE3");
    }

    @Test
    public void noNumeralCall_getsZero() {
        // Historic calls with no digit: first two letters + 0.
        assertThat(WpxPrefix.of("RAEM")).isEqualTo("RA0");
    }

    @Test
    public void portableNumber_replacesPrefixDigit() {
        assertThat(WpxPrefix.of("W1AW/4")).isEqualTo("W4");
        assertThat(WpxPrefix.of("VE3ABC/7")).isEqualTo("VE7");
        assertThat(WpxPrefix.of("PY2AA/0")).isEqualTo("PY0");
    }

    @Test
    public void portablePrefix_designatorWins() {
        assertThat(WpxPrefix.of("DL/W1AW")).isEqualTo("DL0");
        assertThat(WpxPrefix.of("PJ4/K1ABC")).isEqualTo("PJ4");
        assertThat(WpxPrefix.of("PA3/G4XYZ")).isEqualTo("PA3");
        // Home call listed first, portable region second — still the region wins.
        assertThat(WpxPrefix.of("W1AW/KH6")).isEqualTo("KH6");
    }

    @Test
    public void operationalSuffixesIgnored() {
        assertThat(WpxPrefix.of("G4XYZ/P")).isEqualTo("G4");
        assertThat(WpxPrefix.of("W1AW/M")).isEqualTo("W1");
        assertThat(WpxPrefix.of("VK2ABC/QRP")).isEqualTo("VK2");
        assertThat(WpxPrefix.of("K1ABC/MM")).isEqualTo("K1");
        // Suffix combined with a portable number: number still applies.
        assertThat(WpxPrefix.of("W1AW/4/QRP")).isEqualTo("W4");
    }

    @Test
    public void nonCallsignTokens_returnNull() {
        assertThat(WpxPrefix.of(null)).isNull();
        assertThat(WpxPrefix.of("")).isNull();
        assertThat(WpxPrefix.of("   ")).isNull();
        assertThat(WpxPrefix.of("73")).isNull();
        assertThat(WpxPrefix.of("RR73")).isNull();
        assertThat(WpxPrefix.of("599")).isNull();
        assertThat(WpxPrefix.of("<...>")).isNull();
        assertThat(WpxPrefix.of("R-12")).isNull();
        // A Maidenhead grid ends in its digits, so it has no letter suffix and is
        // correctly rejected as a callsign.
        assertThat(WpxPrefix.of("FN42")).isNull();
        // Bare prefix with no suffix isn't a whole callsign.
        assertThat(WpxPrefix.of("W1")).isNull();
    }
}
