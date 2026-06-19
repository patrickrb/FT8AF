package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import android.media.MediaRecorder;
import android.os.Build;

import org.junit.Test;

/**
 * Tests {@link MicRecorder#chooseAudioSource} — the recording-source selection
 * that keeps the OEM voice DSP (AGC / noise-suppression / multi-mic noise
 * cancellation) out of the FT8 RX path (issue #298). We must prefer UNPROCESSED
 * when the device supports it (API 24+) and otherwise fall back to
 * VOICE_RECOGNITION, never to the processed DEFAULT/MIC sources.
 */
public class MicRecorderAudioSourceTest {

    @Test
    public void api24_unprocessedSupported_usesUnprocessed() {
        assertThat(MicRecorder.chooseAudioSource(Build.VERSION_CODES.N, true))
                .isEqualTo(MediaRecorder.AudioSource.UNPROCESSED);
    }

    @Test
    public void api24_unprocessedUnsupported_fallsBackToVoiceRecognition() {
        assertThat(MicRecorder.chooseAudioSource(Build.VERSION_CODES.N, false))
                .isEqualTo(MediaRecorder.AudioSource.VOICE_RECOGNITION);
    }

    @Test
    public void belowApi24_usesVoiceRecognition_evenIfFlagTrue() {
        // UNPROCESSED constant only exists from API 24; on minSdk 23 we must not
        // request it regardless of the (meaningless) capability flag value.
        assertThat(MicRecorder.chooseAudioSource(Build.VERSION_CODES.M, true))
                .isEqualTo(MediaRecorder.AudioSource.VOICE_RECOGNITION);
    }

    @Test
    public void modernApi_supported_usesUnprocessed() {
        assertThat(MicRecorder.chooseAudioSource(Build.VERSION_CODES.TIRAMISU, true))
                .isEqualTo(MediaRecorder.AudioSource.UNPROCESSED);
    }

    @Test
    public void neverReturnsDefaultOrMic() {
        // Across the support/version matrix, the chosen source must always be one
        // of the two low-processing sources — never DEFAULT/MIC.
        int[] sdks = {Build.VERSION_CODES.M, Build.VERSION_CODES.N, Build.VERSION_CODES.TIRAMISU};
        boolean[] flags = {true, false};
        for (int sdk : sdks) {
            for (boolean flag : flags) {
                int src = MicRecorder.chooseAudioSource(sdk, flag);
                assertThat(src).isNotEqualTo(MediaRecorder.AudioSource.DEFAULT);
                assertThat(src).isNotEqualTo(MediaRecorder.AudioSource.MIC);
            }
        }
    }
}
