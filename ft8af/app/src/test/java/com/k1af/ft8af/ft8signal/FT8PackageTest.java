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

    @Test
    public void allSlashCallsign_returnsInputInsteadOfCrashing() {
        // A string of only slashes splits to a zero-length array (Java strips
        // trailing empty tokens), so there is no segment to fall back to.
        // Must not throw ArrayIndexOutOfBoundsException; return the input as-is.
        assertThat(FT8Package.getStdCall("/")).isEqualTo("/");
        assertThat(FT8Package.getStdCall("//")).isEqualTo("//");
        assertThat(FT8Package.getStdCall("///")).isEqualTo("///");
    }

    @Test
    public void trailingSlash_stillReturnsSegment() {
        // Trailing slash is dropped by split but a real segment remains.
        assertThat(FT8Package.getStdCall("W1AW/")).isEqualTo("W1AW");
    }

    @Test
    public void leadingSlash_stillReturnsSegment() {
        // Leading empty token is preserved by split; the callsign segment wins.
        assertThat(FT8Package.getStdCall("/K1ABC")).isEqualTo("K1ABC");
    }
}
