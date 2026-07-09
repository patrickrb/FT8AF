package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link FlexRadioFactory#getSerialNum(String)} — the
 * crash fix for a truncated/garbled FlexRadio discovery broadcast.
 *
 * <p>The discovery payload is parsed from a UDP packet received on the
 * {@link RadioUdpClient} receive thread. That read loop catches only
 * {@code IOException}, so any {@link RuntimeException} thrown while parsing
 * escapes, kills the receive thread, and crashes the whole app.
 *
 * <p>The old code did {@code token.substring("serial".length() + 1)} guarded
 * only by {@code startsWith("serial")}: a bare {@code "serial"} token (6 chars,
 * no {@code =value}) made {@code substring(7)} throw
 * {@link StringIndexOutOfBoundsException}. These tests pin the guarded
 * behaviour and confirm well-formed payloads still parse.
 *
 * <p>{@code getSerialNum} touches no Android types, so no Robolectric runner is
 * needed.
 */
public class FlexRadioFactoryTest {

    /** A representative well-formed discovery payload. */
    private static final String DISCOVERY =
            "discovery_protocol_version=3.0.0.1 model=FLEX-6400 "
                    + "serial=1234-5678-9012-3456 version=3.2.39.12345 "
                    + "nickname=Shack callsign=K1AF status=Available";

    @Test
    public void parsesSerialFromWellFormedPayload() {
        assertThat(FlexRadioFactory.getSerialNum(DISCOVERY))
                .isEqualTo("1234-5678-9012-3456");
    }

    @Test
    public void bareSerialToken_doesNotCrash() {
        // Regression: a "serial" token with no "=value" (6 chars) made
        // substring(7) throw StringIndexOutOfBoundsException on the receive
        // thread. It must now be skipped and yield the empty serial.
        assertThat(FlexRadioFactory.getSerialNum("model=FLEX-6400 serial"))
                .isEqualTo("");
    }

    @Test
    public void emptySerialValue_isEmpty() {
        // "serial=" (7 chars) -> substring(7) == "" must not throw.
        assertThat(FlexRadioFactory.getSerialNum("model=FLEX serial= version=1"))
                .isEqualTo("");
    }

    @Test
    public void payloadWithoutSerial_isEmpty() {
        assertThat(FlexRadioFactory.getSerialNum("model=FLEX-6400 version=3.2"))
                .isEqualTo("");
    }

    @Test
    public void nullPayload_isEmpty() {
        // new String(vita.payload) is never null in practice, but guard anyway
        // so a future caller can't trip a NullPointerException on the thread.
        assertThat(FlexRadioFactory.getSerialNum(null)).isEqualTo("");
    }

    @Test
    public void serialTokenIsCaseInsensitive_valuePreserved() {
        // The prefix match lower-cases the token, but the returned value keeps
        // its original case.
        assertThat(FlexRadioFactory.getSerialNum("SERIAL=AB12-CD34"))
                .isEqualTo("AB12-CD34");
    }
}
