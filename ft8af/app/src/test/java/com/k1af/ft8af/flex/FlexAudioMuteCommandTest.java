package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link FlexRadio#audioMuteCommand(int, boolean)}, the pure
 * builder for the SmartSDR slice audio-mute command behind the "Mute radio
 * speaker" setting. The wire format must match the radio's API exactly
 * ({@code slice s <n> audio_mute=<0|1>}), so the exact strings are asserted.
 */
public class FlexAudioMuteCommandTest {

    @Test
    public void mute_slice0() {
        assertThat(FlexRadio.audioMuteCommand(0, true)).isEqualTo("slice s 0 audio_mute=1");
    }

    @Test
    public void unmute_slice0() {
        assertThat(FlexRadio.audioMuteCommand(0, false)).isEqualTo("slice s 0 audio_mute=0");
    }

    @Test
    public void mute_otherSliceIndex() {
        assertThat(FlexRadio.audioMuteCommand(2, true)).isEqualTo("slice s 2 audio_mute=1");
    }
}
