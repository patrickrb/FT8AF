package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link UsbAudioDevice#channelsForMaxPacketSize(int, int)} — the channel count
 * implied by an isochronous endpoint's wMaxPacketSize at a given sample rate.
 *
 * <p>Background (issue #400): the old guess divided wMaxPacketSize by a hard-coded
 * 48 kHz per-channel frame size (mps / (48 * 2), i.e. mps / 96) with integer
 * truncation, so a 44.1 kHz stereo endpoint (~180-byte packets, 88.2 bytes per
 * channel-ms) counted as mono — 180 / 96 == 1.875 truncates to 1 — and the capture
 * loop de-interleaved garbage on exactly the 44.1 kHz device class issue #364 exists
 * to support. The helper is now rate-aware and rounds (a 44.1 kHz endpoint alternates
 * 44/45 samples per frame, so packets are not an exact multiple of the per-channel
 * rate).
 */
public class UsbAudioChannelCountTest {

    // --- 48 kHz (the historical assumption keeps working) ----------------------

    @Test
    public void mono48k() {
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(96, 48000)).isEqualTo(1);
    }

    @Test
    public void stereo48k() {
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(192, 48000)).isEqualTo(2);
    }

    @Test
    public void cm108StylePacketSize_countsStereo() {
        // CM108-family chips report ~200-byte max packets for 48 kHz stereo.
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(200, 48000)).isEqualTo(2);
    }

    // --- 44.1 kHz (the issue-#400 device class) --------------------------------

    @Test
    public void stereo44k1_atItsRealRate() {
        // 44.1 kHz stereo 16-bit: 176.4 bytes/frame nominal, 180 worst-case (45-sample
        // frames). Both must count as stereo.
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(176, 44100)).isEqualTo(2);
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(180, 44100)).isEqualTo(2);
    }

    @Test
    public void mono44k1_atItsRealRate() {
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(88, 44100)).isEqualTo(1);
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(90, 44100)).isEqualTo(1);
    }

    @Test
    public void stereo44k1_evenUnderTheLegacy48kGuess() {
        // The exact failure from the issue: 180-byte packets judged against the 48 kHz
        // frame size. Truncating division said 1 (mono); rounding says 2. The rate-aware
        // re-derivation in activateInput() is still the real fix, but the initial guess
        // no longer miscounts this device either.
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(180, 48000)).isEqualTo(2);
    }

    // --- other rates ------------------------------------------------------------

    @Test
    public void stereo32k_atItsRealRate() {
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(128, 32000)).isEqualTo(2);
    }

    @Test
    public void mono96k_atItsRealRate() {
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(192, 96000)).isEqualTo(1);
    }

    // --- clamping / degenerate inputs -------------------------------------------

    @Test
    public void tinyPacket_clampsToMono() {
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(0, 48000)).isEqualTo(1);
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(10, 48000)).isEqualTo(1);
    }

    @Test
    public void hugePacket_clampsToStereo() {
        // High-speed endpoints or multi-channel interfaces: clamp to the stereo this
        // pipeline handles rather than reporting 4+ channels.
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(1024, 48000)).isEqualTo(2);
    }

    @Test
    public void nonPositiveRate_fallsBackToMono() {
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(192, 0)).isEqualTo(1);
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(192, -1)).isEqualTo(1);
    }

    @Test
    public void implausibleRate_failsSafeToMono() {
        // A positive but garbage rate (malformed descriptor data) would make the
        // per-channel divisor tiny and round any packet size up to "stereo";
        // outside the plausible 8-768 kHz UAC range we fail safe to mono instead.
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(192, 5)).isEqualTo(1);
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(192, 7999)).isEqualTo(1);
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(192, 10_000_000)).isEqualTo(1);
        // The plausible-range floor itself still computes normally.
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(16, 8000)).isEqualTo(1);
        assertThat(UsbAudioDevice.channelsForMaxPacketSize(32, 8000)).isEqualTo(2);
    }
}
