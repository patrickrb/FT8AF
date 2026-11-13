package com.k1af.ft8af.x6100;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link X6100Radio#parseXieguHandle(String, int)} — the
 * crash fix for the Xiegu {@code HANDLE} ("H" + 32-bit hex) response.
 *
 * <p>The parse runs on the TCP CAT read thread
 * ({@link com.k1af.ft8af.flex.RadioTcpClient}), whose read loop catches only
 * {@code SocketException}/{@code IOException}. The old unguarded
 * {@code Integer.parseInt(head.substring(1), 16)} therefore let a
 * {@link NumberFormatException} from a garbled/truncated frame — or from a
 * legitimate high-bit 32-bit handle that overflows a signed {@code int} —
 * escape and crash the whole app. These tests pin the guarded behaviour.
 *
 * <p>{@code android.util.Log} returns defaults under plain JUnit
 * (unitTests.returnDefaultValues), so no Robolectric runner is needed.
 */
public class X6100RadioHandleParseTest {

    private static final int KEEP = 0x1234; // sentinel "current handle" to preserve

    @Test
    public void parsesLowHandle() {
        assertThat(X6100Radio.parseXieguHandle("H1A2B", KEEP)).isEqualTo(0x1A2B);
    }

    @Test
    public void parsesFullEightDigitHandle() {
        assertThat(X6100Radio.parseXieguHandle("H0009ABCD", KEEP)).isEqualTo(0x0009ABCD);
    }

    @Test
    public void parsesHighBitHandle_wouldOverflowSignedIntParse() {
        // 0xFFFFFFFF is a valid 32-bit Xiegu handle but overflows Integer.parseInt,
        // which threw NumberFormatException and crashed the CAT read thread.
        assertThat(X6100Radio.parseXieguHandle("HFFFFFFFF", KEEP)).isEqualTo(-1);
    }

    @Test
    public void parsesHighBitHandle_minValue() {
        assertThat(X6100Radio.parseXieguHandle("H80000000", KEEP)).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    public void loneHandleLetter_keepsCurrent_doesNotCrash() {
        // Regression: "H" -> substring(1) == "" -> parseInt("", 16) threw.
        assertThat(X6100Radio.parseXieguHandle("H", KEEP)).isEqualTo(KEEP);
    }

    @Test
    public void nonHexTail_keepsCurrent_doesNotCrash() {
        // Regression: a truncated/garbled frame like "HELLO" -> parseInt("ELLO", 16) threw.
        assertThat(X6100Radio.parseXieguHandle("HELLO", KEEP)).isEqualTo(KEEP);
    }

    @Test
    public void lowercaseHex_isParsed() {
        // The handle tail keeps the frame's original case (only the header char is
        // upper-cased for dispatch), so lowercase hex is legitimate.
        assertThat(X6100Radio.parseXieguHandle("Hbeef", KEEP)).isEqualTo(0xBEEF);
    }

    @Test
    public void signedTail_keepsCurrent() {
        // Long.parseLong would accept the sign ("-1" -> -1); a handle is unsigned,
        // so a signed tail is malformed and must preserve the current handle.
        assertThat(X6100Radio.parseXieguHandle("H-1", KEEP)).isEqualTo(KEEP);
    }

    @Test
    public void overlongTail_keepsCurrent_noSilentTruncation() {
        // 9 hex digits exceed a 32-bit handle; parsing+narrowing would silently
        // truncate ("H100000000" -> 0), so reject and keep the current handle.
        assertThat(X6100Radio.parseXieguHandle("H100000000", KEEP)).isEqualTo(KEEP);
    }

    @Test
    public void whitespaceTail_keepsCurrent() {
        assertThat(X6100Radio.parseXieguHandle("H 1A", KEEP)).isEqualTo(KEEP);
    }

    @Test
    public void emptyString_keepsCurrent() {
        assertThat(X6100Radio.parseXieguHandle("", KEEP)).isEqualTo(KEEP);
    }

    @Test
    public void nullHead_keepsCurrent() {
        assertThat(X6100Radio.parseXieguHandle(null, KEEP)).isEqualTo(KEEP);
    }
}
