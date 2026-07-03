package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link UsbAudioDevice#resolveRateAfterSetCurFailure(int, int, int[])} — the rate
 * reported for the input stream when the SAMPLING_FREQ SET_CUR control transfer fails.
 *
 * <p>Background (PR #389 review): {@code activateInput()} used to set
 * {@code inputSampleRate} to the negotiated rate unconditionally, even when the device
 * NAKed SET_CUR. The downstream resampling ratio is derived from that field, so a wrong
 * assumption here re-creates the mislabeled-stream failure of issue #364 (every FT8 tone
 * shifted off the 6.25 Hz grid). The resolution order is: trust a plausible GET_CUR
 * readback from the device, else a single-rate alt setting's sole rate (such endpoints
 * run it regardless — SET_CUR is often unimplemented precisely because there is nothing
 * to choose), else keep the negotiated assumption and log loudly.
 */
public class UsbAudioRateResolutionTest {

    private static final int[] NO_RATES = new int[0];

    // --- 1) a plausible GET_CUR readback wins ---------------------------------

    @Test
    public void getCurReadback_trusted() {
        // Device says it's running 44.1 kHz even though we asked for 48 kHz: believe it.
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, 44100, new int[] {44100, 48000})).isEqualTo(44100);
    }

    @Test
    public void getCurReadback_trustedEvenWithoutDescriptorRates() {
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, 44100, NO_RATES)).isEqualTo(44100);
    }

    @Test
    public void getCurReadback_beatsSingleDescriptorRate() {
        // A live answer from the device outranks descriptor inference.
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, 48000, new int[] {44100})).isEqualTo(48000);
    }

    // --- 2) failed/garbage GET_CUR falls through ------------------------------

    @Test
    public void failedGetCur_singleRateAltSetting_usesThatRate() {
        // GET_CUR failed (-1). The alt setting supports exactly one rate, so the device
        // must be running it no matter what SET_CUR said.
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, -1, new int[] {44100})).isEqualTo(44100);
    }

    @Test
    public void garbageGetCur_isNotMistakenForARate() {
        // Zero, tiny, and absurd readbacks are failed/garbage control transfers.
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, 0, new int[] {44100})).isEqualTo(44100);
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, 100, new int[] {44100})).isEqualTo(44100);
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, 10_000_000, new int[] {44100})).isEqualTo(44100);
    }

    // --- 3) genuinely unknown: keep the negotiated assumption ------------------

    @Test
    public void failedGetCur_multiRateAltSetting_keepsNegotiatedRate() {
        // Multiple candidate rates and no readback: the truth is unknown; the negotiated
        // rate at least came from the device's own descriptors.
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, -1, new int[] {44100, 48000})).isEqualTo(48000);
    }

    @Test
    public void failedGetCur_noDescriptorRates_keepsNegotiatedRate() {
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, -1, NO_RATES)).isEqualTo(48000);
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, -1, null)).isEqualTo(48000);
    }

    @Test
    public void bogusSingleDescriptorRate_isIgnored() {
        // A single descriptor rate that isn't a plausible audio rate must not be trusted
        // just because it's alone in the table.
        assertThat(UsbAudioDevice.resolveRateAfterSetCurFailure(
                48000, -1, new int[] {0})).isEqualTo(48000);
    }

    // --- isPlausibleRate bounds ------------------------------------------------

    @Test
    public void plausibleRate_bounds() {
        assertThat(UsbAudioDevice.isPlausibleRate(7999)).isFalse();
        assertThat(UsbAudioDevice.isPlausibleRate(8000)).isTrue();   // telephony codecs
        assertThat(UsbAudioDevice.isPlausibleRate(44100)).isTrue();
        assertThat(UsbAudioDevice.isPlausibleRate(48000)).isTrue();
        assertThat(UsbAudioDevice.isPlausibleRate(768000)).isTrue(); // top-end UAC DACs
        assertThat(UsbAudioDevice.isPlausibleRate(768001)).isFalse();
        assertThat(UsbAudioDevice.isPlausibleRate(-1)).isFalse();
    }
}
