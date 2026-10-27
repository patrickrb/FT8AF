package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * {@link FT8TransmitSignal#buildTxAudioTrack} on either side of API 26, where
 * {@code AudioTrack} performance modes appeared. minSdk is 23: calling
 * {@code setPerformanceMode}/{@code getPerformanceMode} unguarded threw
 * {@code NoSuchMethodError} on Android 6.0-7.1, an Error the TX paths don't
 * catch, so every sound-card over and TUNE crashed the app there. Robolectric
 * runs each test on that API level's real framework classes, so the API 25
 * case would throw the same way if the guard went missing.
 */
@RunWith(RobolectricTestRunner.class)
public class TxAudioTrackApiLevelTest {

    private static AudioTrack build() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(12000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        return FT8TransmitSignal.buildTxAudioTrack(attributes, format, 48000, "test");
    }

    @Test
    @Config(sdk = 25)
    public void android7_1_buildsWithoutAPerformanceMode() {
        AudioTrack track = build();
        assertThat(track).isNotNull();
        track.release();
    }

    @Test
    @Config(sdk = 26)
    public void android8_0_buildsWithTheLowLatencyRequest() {
        AudioTrack track = build();
        assertThat(track).isNotNull();
        track.release();
    }

    @Test
    public void currentRuntime_buildsWithTheLowLatencyRequest() {
        AudioTrack track = build();
        assertThat(track).isNotNull();
        track.release();
    }
}
