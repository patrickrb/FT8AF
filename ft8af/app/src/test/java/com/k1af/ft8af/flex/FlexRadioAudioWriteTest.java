package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link FlexRadio#writeAudio(byte[])} — the crash fix for
 * the Flex DAX RX-audio use-after-release race on disconnect.
 *
 * <p>Audio frames arrive on the UDP stream read thread
 * ({@link RadioUdpClient.ReceiveRunnable#run()}), whose loop catches only
 * {@code IOException}. {@link FlexRadio#closeAudio()} releases and nulls the
 * playback track from the disconnect thread, and
 * {@code FlexConnector.disconnect()} calls {@code closeAudio()} <em>before</em>
 * stopping the stream port — so the receive thread is still delivering frames
 * while the track is torn down. The old inline {@code doReceiveAudio} did an
 * unguarded {@code audioTrack.write(...)} after a plain null-check, so a track
 * released between the check and the write threw an uncaught
 * {@link IllegalStateException} on the receive thread and crashed the whole app.
 *
 * <p>{@link FlexRadio}'s no-arg constructor touches no Android framework beyond
 * {@code System.currentTimeMillis()}, and the {@code writeAudioToTrack} seam is
 * overridden here so no real {@link android.media.AudioTrack} is constructed —
 * hence no Robolectric runner is needed ({@code returnDefaultValues} covers the
 * {@code Log} call in {@code writeAudio}).
 */
public class FlexRadioAudioWriteTest {

    /** Records whether the seam ran, so we can assert the happy path still writes. */
    private static class RecordingFlexRadio extends FlexRadio {
        int writes = 0;

        @Override
        void writeAudioToTrack(byte[] data) {
            writes++;
        }
    }

    /** Simulates a track released between the null-check and the write. */
    private static class ReleasedTrackFlexRadio extends FlexRadio {
        @Override
        void writeAudioToTrack(byte[] data) {
            throw new IllegalStateException("play() called on uninitialized AudioTrack");
        }
    }

    /** Simulates any other unchecked failure that must NOT be swallowed. */
    private static class BadArgFlexRadio extends FlexRadio {
        @Override
        void writeAudioToTrack(byte[] data) {
            throw new IllegalArgumentException("bad audio buffer");
        }
    }

    @Test
    public void writeAudio_swallowsIllegalStateFromReleasedTrack() {
        // The disconnect-while-streaming crash: pre-fix this IllegalStateException
        // escaped doReceiveAudio and killed the UDP receive thread / app.
        FlexRadio radio = new ReleasedTrackFlexRadio();
        radio.writeAudio(new byte[16]); // must not throw
    }

    @Test(expected = IllegalArgumentException.class)
    public void writeAudio_doesNotSwallowOtherRuntimeExceptions() {
        // The catch must be narrow: only the release race is tolerated, so a
        // genuine programming error still surfaces.
        FlexRadio radio = new BadArgFlexRadio();
        radio.writeAudio(new byte[16]);
    }

    @Test
    public void writeAudio_deliversFrameOnHappyPath() {
        // When the track is healthy the frame is still written exactly once.
        RecordingFlexRadio radio = new RecordingFlexRadio();
        radio.writeAudio(new byte[16]);
        assertThat(radio.writes).isEqualTo(1);
    }

    @Test
    public void writeAudioToTrack_isNoOpWhenAudioClosed() {
        // Real seam with no track open (audioTrack == null): must not throw and
        // must not attempt a write. Exercises the null-guard directly.
        FlexRadio radio = new FlexRadio();
        radio.writeAudioToTrack(new byte[16]); // audio never opened -> no-op
    }
}
