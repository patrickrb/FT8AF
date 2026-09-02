package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link MainViewModel#replyTxFrequencyHz} — the bound applied to the
 * (untrusted) audio frequency a WSJT-X companion asks us to answer on via an inbound
 * Reply request's {@code df} field. A plausible df is honored so we key up on the
 * requested tone; anything outside the same passband the tuning UI permits
 * ({@code [100, spectrumWidth - 100]}, matching {@code WaterfallView}'s click-to-tune
 * clamp) is ignored, so a malformed/garbled UDP datagram — or one asking for a tone the
 * user could never dial in by hand — can never push the TX marker off the visible band.
 */
public class ReplyTxFrequencyTest {

    // A representative in-range spectrum width (the settings default). getSpectrumWidth()
    // is always clamped to [MIN_SPECTRUM_WIDTH_HZ, MAX_SPECTRUM_WIDTH_HZ] = [2500, 5000].
    private static final int WIDTH = 3500;
    private static final int MIN = 100;          // lower tuning bound
    private static final int MAX = WIDTH - 100;   // upper tuning bound (3400)

    @Test
    public void midBandFrequency_isHonored() {
        assertThat(MainViewModel.replyTxFrequencyHz(1500, WIDTH)).isEqualTo(1500);
    }

    @Test
    public void lowestValidFrequency_isHonored() {
        // The UI's minimum tunable offset is 100 Hz.
        assertThat(MainViewModel.replyTxFrequencyHz(MIN, WIDTH)).isEqualTo(MIN);
    }

    @Test
    public void highestValidFrequency_isHonored() {
        // The UI's maximum tunable offset is spectrumWidth - 100.
        assertThat(MainViewModel.replyTxFrequencyHz(MAX, WIDTH)).isEqualTo(MAX);
    }

    @Test
    public void belowMin_keepsCurrent() {
        // Below the 100 Hz floor the tuning UI enforces — the user could never dial this in.
        assertThat(MainViewModel.replyTxFrequencyHz(99, WIDTH)).isEqualTo(-1);
        assertThat(MainViewModel.replyTxFrequencyHz(1, WIDTH)).isEqualTo(-1);
    }

    @Test
    public void zeroFrequency_keepsCurrent() {
        // df == 0 means "unspecified" in the WSJT-X message.
        assertThat(MainViewModel.replyTxFrequencyHz(0, WIDTH)).isEqualTo(-1);
    }

    @Test
    public void negativeFrequency_keepsCurrent() {
        // A hostile/garbled df decoded as a negative int must be ignored.
        assertThat(MainViewModel.replyTxFrequencyHz(-200, WIDTH)).isEqualTo(-1);
    }

    @Test
    public void aboveMax_keepsCurrent() {
        // Beyond spectrumWidth - 100 — outside the visible/tunable range; ignore it.
        assertThat(MainViewModel.replyTxFrequencyHz(MAX + 1, WIDTH)).isEqualTo(-1);
        assertThat(MainViewModel.replyTxFrequencyHz(50_000, WIDTH)).isEqualTo(-1);
    }
}
