package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link FlexRadioFactory#getSerialNum(String)} — the crash
 * fix for a truncated/garbled FlexRadio VITA discovery payload.
 *
 * <p>The discovery payload is parsed on the UDP discovery read thread
 * ({@link RadioUdpClient}). That receive loop catches only {@code IOException},
 * so any {@link RuntimeException} thrown while parsing escapes the loop, kills
 * the discovery thread, and crashes the whole app.
 *
 * <p>The old code matched {@code startsWith("serial")} and then did
 * {@code substring("serial".length() + 1)} == {@code substring(7)}
 * unconditionally. A bare {@code "serial"} token (length 6, case-insensitive) —
 * as in a corrupt or partial broadcast — made {@code substring(7)} throw
 * {@link StringIndexOutOfBoundsException}. Requiring the {@code "serial="} prefix
 * removes the crash and matches how {@code FlexRadio.getParameterStr} reads the
 * same field. These tests pin both the guarded behaviour and correct parsing.
 *
 * <p>{@code getSerialNum} touches no Android types, so no Robolectric runner is
 * needed.
 */
public class FlexRadioFactoryTest {

    /** A representative well-formed Flex discovery payload. */
    private static final String DISCOVERY =
            "discovery_protocol_version=3.0.0.2 model=FLEX-6400 "
                    + "serial=1418-6579-6400-0461 version=3.3.32.8203 nickname=FlexRADIO "
                    + "callsign=FlexRADIO ip=192.168.3.86 port=4992 status=Available";

    @Test
    public void wellFormedDiscovery_returnsSerial() {
        assertThat(FlexRadioFactory.getSerialNum(DISCOVERY))
                .isEqualTo("1418-6579-6400-0461");
    }

    @Test
    public void serialAsFirstToken_returnsSerial() {
        assertThat(FlexRadioFactory.getSerialNum("serial=1234-5678 model=FLEX-6600"))
                .isEqualTo("1234-5678");
    }

    @Test
    public void bareSerialToken_doesNotCrash() {
        // Regression: token "serial" (length 6) -> substring(7) threw
        // StringIndexOutOfBoundsException on the UDP discovery thread.
        assertThat(FlexRadioFactory.getSerialNum("model=FLEX-6400 serial status=Available"))
                .isEqualTo("");
    }

    @Test
    public void bareSerialTokenUppercase_doesNotCrash() {
        // startsWith is applied to toLowerCase(), so "SERIAL" would also have matched.
        assertThat(FlexRadioFactory.getSerialNum("SERIAL")).isEqualTo("");
    }

    @Test
    public void serialWithEmptyValue_returnsEmpty() {
        // "serial=" -> substring(7) == "" is valid; not a serial, treated as absent.
        assertThat(FlexRadioFactory.getSerialNum("serial= model=FLEX-6400")).isEqualTo("");
    }

    @Test
    public void noSerialToken_returnsEmpty() {
        assertThat(FlexRadioFactory.getSerialNum("model=FLEX-6400 status=Available"))
                .isEqualTo("");
    }

    @Test
    public void emptyPayload_returnsEmpty() {
        assertThat(FlexRadioFactory.getSerialNum("")).isEqualTo("");
    }
}
