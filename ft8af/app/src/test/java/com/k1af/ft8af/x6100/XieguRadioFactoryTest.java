package com.k1af.ft8af.x6100;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link XieguRadioFactory#getMacAddress(String)} — the
 * crash fix for a truncated/garbled Xiegu VITA discovery payload.
 *
 * <p>The discovery payload is parsed on the UDP discovery read thread
 * ({@code RadioUdpClient}). That receive loop catches only {@code IOException},
 * so any {@link RuntimeException} thrown while parsing escapes the loop, kills
 * the discovery thread, and crashes the whole app.
 *
 * <p>The old code matched {@code startsWith("mac")} and then did
 * {@code substring("mac".length() + 1)} == {@code substring(4)} unconditionally.
 * A bare {@code "mac"} token (length 3, case-insensitive) — as in a corrupt or
 * partial broadcast — made {@code substring(4)} throw
 * {@link StringIndexOutOfBoundsException}. Requiring the {@code "mac="} prefix
 * removes the crash and matches how {@code X6100Radio.getParameterStr} reads the
 * same field. This is the direct twin of the {@code FlexRadioFactory.getSerialNum}
 * fix (PR #501/#503).
 *
 * <p>{@code getMacAddress} touches no Android types, so no Robolectric runner is
 * needed.
 */
public class XieguRadioFactoryTest {

    /** A representative well-formed Xiegu discovery payload. */
    private static final String DISCOVERY =
            "ft8cn_server_version=1.0 model=X6100 mac=AA:BB:CC:DD:EE:FF "
                    + "control_port=50001 stream_port=50002 discovery_port=7001";

    @Test
    public void wellFormedDiscovery_returnsMac() {
        assertThat(XieguRadioFactory.getMacAddress(DISCOVERY))
                .isEqualTo("AA:BB:CC:DD:EE:FF");
    }

    @Test
    public void macAsFirstToken_returnsMac() {
        assertThat(XieguRadioFactory.getMacAddress("mac=11:22:33:44:55:66 model=X6100"))
                .isEqualTo("11:22:33:44:55:66");
    }

    @Test
    public void bareMacToken_doesNotCrash() {
        // Regression: token "mac" (length 3) -> substring(4) threw
        // StringIndexOutOfBoundsException on the UDP discovery thread.
        assertThat(XieguRadioFactory.getMacAddress("model=X6100 mac stream_port=50002"))
                .isEqualTo("");
    }

    @Test
    public void bareMacTokenUppercase_doesNotCrash() {
        // startsWith is applied to toLowerCase(), so "MAC" would also have matched.
        assertThat(XieguRadioFactory.getMacAddress("MAC")).isEqualTo("");
    }

    @Test
    public void machineToken_notMisreadAsMac() {
        // The old startsWith("mac") matched "machine=..." and returned "ine=...".
        // Requiring "mac=" makes it a non-match, so no bogus MAC is produced.
        assertThat(XieguRadioFactory.getMacAddress("machine=router model=X6100"))
                .isEqualTo("");
    }

    @Test
    public void macWithEmptyValue_returnsEmpty() {
        // "mac=" -> substring(4) == "" is valid; not a MAC, treated as absent.
        assertThat(XieguRadioFactory.getMacAddress("mac= model=X6100")).isEqualTo("");
    }

    @Test
    public void noMacToken_returnsEmpty() {
        assertThat(XieguRadioFactory.getMacAddress("model=X6100 stream_port=50002"))
                .isEqualTo("");
    }

    @Test
    public void emptyPayload_returnsEmpty() {
        assertThat(XieguRadioFactory.getMacAddress("")).isEqualTo("");
    }

    @Test
    public void nullPayload_isEmpty() {
        // new String(vita.payload) is never null in practice, but guard anyway
        // so a future caller can't trip a NullPointerException on the thread.
        assertThat(XieguRadioFactory.getMacAddress(null)).isEqualTo("");
    }

    @Test
    public void macTokenIsCaseInsensitive_valuePreserved() {
        // The prefix match lower-cases the token, but the returned value keeps
        // its original case.
        assertThat(XieguRadioFactory.getMacAddress("MAC=Ab:Cd:Ef:01:23:45"))
                .isEqualTo("Ab:Cd:Ef:01:23:45");
    }
}
