package com.k1af.ft8af.x6100;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link X6100Radio#parseAudioFrames(String, int)} and
 * {@link X6100Radio#parseAudioPeriodMs(String, int)} — the validation of the
 * audio parameters the Xiegu X6100 reports in its network "audio get all" reply.
 *
 * <p>These values are consumed by {@code X6100Radio.sendWaveData} as an array
 * size ({@code new short[frames]}) and a busy-wait duration. Before this guard a
 * non-positive / garbled {@code frames=} crashed the TX thread with
 * {@code NegativeArraySizeException} (or spun it forever on {@code frames=0}),
 * and a {@code period} under 1000 µs truncated to a 0 ms wait (a CPU spin) — all
 * reachable from untrusted network input, since the CAT read loop
 * ({@link com.k1af.ft8af.flex.RadioTcpClient}) catches only
 * {@code SocketException}/{@code IOException} and {@code sendWaveData} catches
 * only {@code UnknownHostException}.
 *
 * <p>{@code android.util.Log} returns defaults under plain JUnit
 * (unitTests.returnDefaultValues), and the helpers are pure, so no Robolectric
 * runner is needed.
 */
public class X6100RadioAudioInfoTest {

    private static final int FRAMES_DEFAULT = 768; // the field's initial value
    private static final int PERIOD_DEFAULT = 64;  // ms

    // ---- parseAudioFrames ----------------------------------------------------

    @Test
    public void frames_validValue_isApplied() {
        // A normal X6100 reports frames=768 (64 ms at 12 kHz).
        assertThat(X6100Radio.parseAudioFrames("768", FRAMES_DEFAULT)).isEqualTo(768);
    }

    @Test
    public void frames_otherValidValue_isApplied() {
        assertThat(X6100Radio.parseAudioFrames("1024", FRAMES_DEFAULT)).isEqualTo(1024);
    }

    @Test
    public void frames_maxBoundary_isApplied() {
        assertThat(X6100Radio.parseAudioFrames(
                Integer.toString(X6100Radio.MAX_AUDIO_FRAMES), FRAMES_DEFAULT))
                .isEqualTo(X6100Radio.MAX_AUDIO_FRAMES);
    }

    @Test
    public void frames_zero_keepsDefault_avoidsSendWaveDataSpin() {
        // frames==0 makes sendWaveData's copy loop never advance -> infinite spin.
        assertThat(X6100Radio.parseAudioFrames("0", FRAMES_DEFAULT)).isEqualTo(FRAMES_DEFAULT);
    }

    @Test
    public void frames_negative_keepsDefault_avoidsNegativeArraySize() {
        // frames<0 -> new short[frames] -> NegativeArraySizeException (uncaught crash).
        assertThat(X6100Radio.parseAudioFrames("-5", FRAMES_DEFAULT)).isEqualTo(FRAMES_DEFAULT);
    }

    @Test
    public void frames_absurdlyLarge_keepsDefault_avoidsOom() {
        // A multi-GB short[] would OutOfMemoryError; reject values past the cap.
        assertThat(X6100Radio.parseAudioFrames("999999999", FRAMES_DEFAULT))
                .isEqualTo(FRAMES_DEFAULT);
        assertThat(X6100Radio.parseAudioFrames(
                Integer.toString(X6100Radio.MAX_AUDIO_FRAMES + 1), FRAMES_DEFAULT))
                .isEqualTo(FRAMES_DEFAULT);
    }

    @Test
    public void frames_nonNumeric_keepsDefault() {
        assertThat(X6100Radio.parseAudioFrames("abc", FRAMES_DEFAULT)).isEqualTo(FRAMES_DEFAULT);
        assertThat(X6100Radio.parseAudioFrames("", FRAMES_DEFAULT)).isEqualTo(FRAMES_DEFAULT);
        assertThat(X6100Radio.parseAudioFrames(null, FRAMES_DEFAULT)).isEqualTo(FRAMES_DEFAULT);
    }

    @Test
    public void frames_whitespaceIsTolerated() {
        assertThat(X6100Radio.parseAudioFrames("  768  ", FRAMES_DEFAULT)).isEqualTo(768);
    }

    // ---- parseAudioPeriodMs --------------------------------------------------

    @Test
    public void period_validValue_isConvertedToMs() {
        // A normal X6100 reports period in microseconds (64000 -> 64 ms).
        assertThat(X6100Radio.parseAudioPeriodMs("64000", PERIOD_DEFAULT)).isEqualTo(64);
    }

    @Test
    public void period_belowOneMs_keepsDefault_avoidsBusyWaitSpin() {
        // Under 1000 µs truncates to 0 ms on the /1000 -> the packet-pacing
        // busy-wait becomes a tight CPU spin. Keep the sane default instead.
        assertThat(X6100Radio.parseAudioPeriodMs("500", PERIOD_DEFAULT)).isEqualTo(PERIOD_DEFAULT);
    }

    @Test
    public void period_zero_keepsDefault() {
        assertThat(X6100Radio.parseAudioPeriodMs("0", PERIOD_DEFAULT)).isEqualTo(PERIOD_DEFAULT);
    }

    @Test
    public void period_negative_keepsDefault() {
        assertThat(X6100Radio.parseAudioPeriodMs("-1000", PERIOD_DEFAULT)).isEqualTo(PERIOD_DEFAULT);
    }

    @Test
    public void period_nonNumeric_keepsDefault() {
        assertThat(X6100Radio.parseAudioPeriodMs("xyz", PERIOD_DEFAULT)).isEqualTo(PERIOD_DEFAULT);
        assertThat(X6100Radio.parseAudioPeriodMs(null, PERIOD_DEFAULT)).isEqualTo(PERIOD_DEFAULT);
    }

    @Test
    public void period_maxBoundary_isApplied() {
        // Exactly the cap (1 s == 1_000_000 µs) is still accepted.
        assertThat(X6100Radio.parseAudioPeriodMs(
                Integer.toString(X6100Radio.MAX_AUDIO_PERIOD_MS * 1000), PERIOD_DEFAULT))
                .isEqualTo(X6100Radio.MAX_AUDIO_PERIOD_MS);
    }

    @Test
    public void period_absurdlyLarge_keepsDefault_avoidsBusyWaitHang() {
        // period=2147483647 µs -> ~35 min busy-wait per packet on the TX thread.
        // Anything past MAX_AUDIO_PERIOD_MS must fall back to the sane default.
        assertThat(X6100Radio.parseAudioPeriodMs(
                Integer.toString(Integer.MAX_VALUE), PERIOD_DEFAULT)).isEqualTo(PERIOD_DEFAULT);
        assertThat(X6100Radio.parseAudioPeriodMs(
                Integer.toString((X6100Radio.MAX_AUDIO_PERIOD_MS + 1) * 1000), PERIOD_DEFAULT))
                .isEqualTo(PERIOD_DEFAULT);
    }

    @Test
    public void frames_maxCapMatchesWholeTxCycle() {
        // The cap equals a whole 15 s TX cycle at 12 kHz (180 000 samples), so the
        // documented rationale and the constant agree.
        assertThat(X6100Radio.MAX_AUDIO_FRAMES).isEqualTo(180_000);
    }
}
