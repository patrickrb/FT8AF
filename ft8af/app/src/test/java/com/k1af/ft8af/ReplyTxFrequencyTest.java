package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link MainViewModel#replyTxFrequencyHz} — the bound applied to the
 * (untrusted) audio frequency a WSJT-X companion asks us to answer on via an inbound
 * Reply request's {@code df} field. A plausible df is honored so we key up on the
 * requested tone; anything non-positive or beyond the audio passband is ignored so a
 * malformed/garbled UDP datagram can never push the TX tone off the band.
 */
public class ReplyTxFrequencyTest {

    private static final int MAX = GeneralVariables.MAX_SPECTRUM_WIDTH_HZ; // 5000

    @Test
    public void midBandFrequency_isHonored() {
        assertThat(MainViewModel.replyTxFrequencyHz(1500, MAX)).isEqualTo(1500);
    }

    @Test
    public void lowestValidFrequency_isHonored() {
        assertThat(MainViewModel.replyTxFrequencyHz(1, MAX)).isEqualTo(1);
    }

    @Test
    public void maxFrequency_isHonored() {
        assertThat(MainViewModel.replyTxFrequencyHz(MAX, MAX)).isEqualTo(MAX);
    }

    @Test
    public void zeroFrequency_keepsCurrent() {
        // df == 0 means "unspecified" in the WSJT-X message.
        assertThat(MainViewModel.replyTxFrequencyHz(0, MAX)).isEqualTo(-1);
    }

    @Test
    public void negativeFrequency_keepsCurrent() {
        // A hostile/garbled df decoded as a negative int must be ignored.
        assertThat(MainViewModel.replyTxFrequencyHz(-200, MAX)).isEqualTo(-1);
    }

    @Test
    public void aboveMax_keepsCurrent() {
        // Beyond the widest possible audio passband — can't be tuned; ignore it.
        assertThat(MainViewModel.replyTxFrequencyHz(MAX + 1, MAX)).isEqualTo(-1);
        assertThat(MainViewModel.replyTxFrequencyHz(50_000, MAX)).isEqualTo(-1);
    }
}
