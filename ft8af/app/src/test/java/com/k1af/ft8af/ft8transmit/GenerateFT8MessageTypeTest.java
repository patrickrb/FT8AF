package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Exercise the pure static helpers in {@link GenerateFT8} that classify a
 * callsign into FT8's i3 message-type buckets and recognise the standard
 * amateur-radio callsign shape.
 *
 * Runs under Robolectric (rather than plain JUnit) only because the GenerateFT8
 * class's static initialiser calls {@code System.loadLibrary("ft8af")}; the
 * Robolectric runner no-ops that call so we can hit the Java-side methods
 * without the native library on the JVM library path.
 */
@RunWith(RobolectricTestRunner.class)
public class GenerateFT8MessageTypeTest {

    // i3 codes used by FT8 (see GenerateFT8.checkI3ByCallsign):
    //   0 = free text / empty
    //   1 = standard callsign
    //   2 = /P suffix (portable, short callsign)
    //   4 = non-standard / hashed

    @Test
    public void standardCallsign_returnsI3_1() {
        assertThat(GenerateFT8.checkI3ByCallsign("K1ABC")).isEqualTo(1);
        assertThat(GenerateFT8.checkI3ByCallsign("VE3XY")).isEqualTo(1);
    }

    @Test
    public void portableSuffix_returnsI3_2_whenShort() {
        // /P on a callsign <=8 chars total is the standard portable form.
        assertThat(GenerateFT8.checkI3ByCallsign("K1ABC/P")).isEqualTo(2);
    }

    @Test
    public void rovingSuffix_returnsI3_1_whenShort() {
        // /R is treated as i3=1 by the encoder (see line 53-55).
        assertThat(GenerateFT8.checkI3ByCallsign("K1ABC/R")).isEqualTo(1);
    }

    @Test
    public void longPortableCallsign_returnsI3_4_nonstandard() {
        // A /P call longer than 8 chars overflows the standard slot and must
        // be encoded as a non-standard (hashed) callsign.
        assertThat(GenerateFT8.checkI3ByCallsign("VE2ABCD/P")).isEqualTo(4);
    }

    @Test
    public void slashInCallsign_returnsI3_4() {
        // Anything with a '/' that isn't /P or /R is non-standard.
        assertThat(GenerateFT8.checkI3ByCallsign("DL/W1AW")).isEqualTo(4);
    }

    @Test
    public void overlyLongCallsign_returnsI3_4() {
        // >6 chars (without /P or /R) is also non-standard.
        assertThat(GenerateFT8.checkI3ByCallsign("ABCDEFG")).isEqualTo(4);
    }

    @Test
    public void emptyCallsign_returnsZero_freeText() {
        // Length < 2 short-circuits to 0 (free-text).
        assertThat(GenerateFT8.checkI3ByCallsign("")).isEqualTo(0);
        assertThat(GenerateFT8.checkI3ByCallsign("X")).isEqualTo(0);
    }

    @Test
    public void nullCallsign_returnsZero() {
        assertThat(GenerateFT8.checkI3ByCallsign(null)).isEqualTo(0);
    }

    @Test
    public void checkIsStandardCallsign_acceptsCanonicalShapes() {
        // ITU-shape examples; regex requires prefix(1-2 alphanumeric, includes digit)
        // + digit + 1-3 letter suffix.
        assertThat(GenerateFT8.checkIsStandardCallsign("K1ABC")).isTrue();
        assertThat(GenerateFT8.checkIsStandardCallsign("VE3XY")).isTrue();
        assertThat(GenerateFT8.checkIsStandardCallsign("W1AW")).isTrue();
    }

    @Test
    public void checkIsStandardCallsign_stripsPortableRoverSuffix() {
        // /P and /R are stripped before the regex check (line 99-103).
        assertThat(GenerateFT8.checkIsStandardCallsign("K1ABC/P")).isTrue();
        assertThat(GenerateFT8.checkIsStandardCallsign("K1ABC/R")).isTrue();
    }

    @Test
    public void checkIsStandardCallsign_rejectsNonStandard() {
        assertThat(GenerateFT8.checkIsStandardCallsign("DL/W1AW")).isFalse();
        assertThat(GenerateFT8.checkIsStandardCallsign("123")).isFalse();
        assertThat(GenerateFT8.checkIsStandardCallsign("LONGCALL")).isFalse();
    }
}
