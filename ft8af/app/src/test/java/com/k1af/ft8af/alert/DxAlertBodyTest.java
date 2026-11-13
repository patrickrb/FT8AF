package com.k1af.ft8af.alert;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;

import org.junit.Test;

/**
 * Pure-JVM tests for the {@link DxAlertNotifier#defaultBody} and
 * {@link DxAlertNotifier#cqReplyBody} notification-body formatting. The interesting
 * case is an unknown SNR: a valid decode can
 * reach the alert path with {@code snr == Ft8Message.SNR_UNKNOWN} (the decoder does not always
 * set it — FT8SignalListener logs "SNR not set by decoder" and still lists the message), and the
 * DX/watchlist/New-DXCC/New-State body must not render the {@link Integer#MIN_VALUE} sentinel.
 */
public class DxAlertBodyTest {

    private static Ft8Message msg(String from, String grid, int snr) {
        Ft8Message m = new Ft8Message(0);
        m.callsignFrom = from;
        m.maidenGrid = grid;
        m.snr = snr;
        return m;
    }

    @Test
    public void defaultBody_includesSnrWhenKnown() {
        String body = DxAlertNotifier.defaultBody(msg("JA1ABC", "PM95", -12));
        assertThat(body).isEqualTo("JA1ABC  PM95  -12 dB");
    }

    @Test
    public void defaultBody_omitsSnrWhenUnknown() {
        String body = DxAlertNotifier.defaultBody(msg("JA1ABC", "PM95", Ft8Message.SNR_UNKNOWN));
        // Before the fix this appended "-2147483648 dB"; the sentinel must never surface.
        assertThat(body).isEqualTo("JA1ABC  PM95");
        assertThat(body).doesNotContain("dB");
        assertThat(body).doesNotContain(String.valueOf(Integer.MIN_VALUE));
    }

    @Test
    public void defaultBody_omitsGridWhenEmpty() {
        assertThat(DxAlertNotifier.defaultBody(msg("JA1ABC", "", 3))).isEqualTo("JA1ABC  3 dB");
        assertThat(DxAlertNotifier.defaultBody(msg("JA1ABC", null, 3))).isEqualTo("JA1ABC  3 dB");
    }

    @Test
    public void defaultBody_callsignOnlyWhenSnrUnknownAndGridMissing() {
        assertThat(DxAlertNotifier.defaultBody(msg("JA1ABC", null, Ft8Message.SNR_UNKNOWN)))
                .isEqualTo("JA1ABC");
    }

    @Test
    public void cqReplyBody_alsoOmitsUnknownSnr_forParity() {
        Ft8Message m = new Ft8Message(0, 0, "MYCALL", "JA1ABC", "RR73");
        m.snr = Ft8Message.SNR_UNKNOWN;
        String body = DxAlertNotifier.cqReplyBody(m);
        assertThat(body).doesNotContain("dB");
        assertThat(body).doesNotContain(String.valueOf(Integer.MIN_VALUE));
    }
}
