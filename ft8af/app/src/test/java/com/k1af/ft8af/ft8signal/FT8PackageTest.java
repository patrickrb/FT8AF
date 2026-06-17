package com.k1af.ft8af.ft8signal;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link FT8Package#getStdCall} — the routine that picks the
 * "standard" amateur callsign out of a compound (slash-bearing) callsign.
 *
 * Plain JUnit: FT8Package's static initialiser now swallows the
 * {@code System.loadLibrary("ft8af")} UnsatisfiedLinkError, so the class loads
 * on the bare JVM. Running without Robolectric also means JaCoCo's agent (which
 * instruments the system classloader) actually records this class's coverage.
 */
public class FT8PackageTest {

    @Test
    public void noSlash_returnsInputUnchanged() {
        assertThat(FT8Package.getStdCall("K1ABC")).isEqualTo("K1ABC");
    }

    @Test
    public void prefixedCallsign_extractsStandardPart() {
        // "DL" is a prefix with no digit, so the standard-callsign regex picks
        // the K1ABC segment.
        assertThat(FT8Package.getStdCall("DL/K1ABC")).isEqualTo("K1ABC");
    }

    @Test
    public void suffixedCallsign_extractsStandardPart() {
        // The standard segment can appear before the slash too.
        assertThat(FT8Package.getStdCall("VE3ABC/VE7")).isEqualTo("VE3ABC");
    }

    @Test
    public void noStandardSegment_fallsBackToLongest() {
        // Neither side matches the standard shape -> return the longest segment.
        assertThat(FT8Package.getStdCall("PY1/ZZ")).isEqualTo("PY1");
    }
}
