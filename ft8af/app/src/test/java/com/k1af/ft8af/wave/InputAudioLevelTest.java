package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Arrays;

/**
 * Tests {@link InputAudioLevel} — the RX input-gain multiply and the
 * sliding-window peak/RMS meter behind the input-level indicator (issue #356).
 */
public class InputAudioLevelTest {

    // ------------------------------------------------------------------
    // applyGain
    // ------------------------------------------------------------------

    @Test
    public void applyGain_multipliesSamples() {
        float[] data = {0.1f, -0.2f, 0.5f};
        InputAudioLevel.applyGain(data, data.length, 2.0f);
        assertThat(data[0]).isWithin(1e-7f).of(0.2f);
        assertThat(data[1]).isWithin(1e-7f).of(-0.4f);
        assertThat(data[2]).isWithin(1e-7f).of(1.0f);
    }

    @Test
    public void applyGain_unityGainIsExactNoOp() {
        float[] data = {0.1f, -0.30000001f, 0.7f};
        float[] before = Arrays.copyOf(data, data.length);
        InputAudioLevel.applyGain(data, data.length, 1.0f);
        // Bit-identical, not just approximately equal: default 100% must not
        // perturb the audio path at all.
        assertThat(data).isEqualTo(before);
    }

    @Test
    public void applyGain_respectsLength() {
        float[] data = {0.5f, 0.5f, 0.5f};
        InputAudioLevel.applyGain(data, 2, 0.5f);
        assertThat(data[0]).isWithin(1e-7f).of(0.25f);
        assertThat(data[1]).isWithin(1e-7f).of(0.25f);
        assertThat(data[2]).isWithin(1e-7f).of(0.5f); // untouched past len
    }

    @Test
    public void applyGain_lengthBeyondArrayIsClamped() {
        float[] data = {0.5f};
        InputAudioLevel.applyGain(data, 100, 2.0f); // must not throw
        assertThat(data[0]).isWithin(1e-7f).of(1.0f);
    }

    @Test
    public void applyGain_nullBufferIsNoOp() {
        InputAudioLevel.applyGain(null, 10, 2.0f); // must not throw
    }

    @Test
    public void applyGain_zeroGainSilences() {
        float[] data = {0.9f, -0.9f};
        InputAudioLevel.applyGain(data, data.length, 0f);
        assertThat(data[0]).isEqualTo(0f);
        assertThat(data[1]).isEqualTo(-0f);
    }

    // ------------------------------------------------------------------
    // process (windowed peak/RMS meter)
    // ------------------------------------------------------------------

    @Test
    public void process_returnsNullUntilWindowFills() {
        InputAudioLevel meter = new InputAudioLevel();
        float[] data = new float[InputAudioLevel.WINDOW_SAMPLES - 1];
        assertThat(meter.process(data, data.length)).isNull();
    }

    @Test
    public void process_emitsPeakAndRmsWhenWindowCompletes() {
        InputAudioLevel meter = new InputAudioLevel();
        float[] data = new float[InputAudioLevel.WINDOW_SAMPLES];
        Arrays.fill(data, 0.5f);
        data[7] = -0.8f; // peak is max ABSOLUTE value

        InputAudioLevel.Levels levels = meter.process(data, data.length);
        assertThat(levels).isNotNull();
        assertThat(levels.peak).isWithin(1e-6f).of(0.8f);
        // RMS of N-1 samples at 0.5 plus one at 0.8 is just above 0.5.
        double expectedRms = Math.sqrt(
                ((InputAudioLevel.WINDOW_SAMPLES - 1) * 0.25 + 0.64)
                        / InputAudioLevel.WINDOW_SAMPLES);
        assertThat(levels.rms).isWithin(1e-6f).of((float) expectedRms);
    }

    @Test
    public void process_accumulatesAcrossSmallBuffers() {
        InputAudioLevel meter = new InputAudioLevel();
        float[] chunk = new float[InputAudioLevel.WINDOW_SAMPLES / 2];
        Arrays.fill(chunk, 0.4f);
        assertThat(meter.process(chunk, chunk.length)).isNull();
        InputAudioLevel.Levels levels = meter.process(chunk, chunk.length);
        assertThat(levels).isNotNull();
        assertThat(levels.peak).isWithin(1e-6f).of(0.4f);
        assertThat(levels.rms).isWithin(1e-6f).of(0.4f);
    }

    @Test
    public void process_resetsBetweenWindows() {
        InputAudioLevel meter = new InputAudioLevel();
        float[] loud = new float[InputAudioLevel.WINDOW_SAMPLES];
        Arrays.fill(loud, 0.9f);
        InputAudioLevel.Levels first = meter.process(loud, loud.length);
        assertThat(first.peak).isWithin(1e-6f).of(0.9f);

        // A quiet second window must not inherit the first window's peak.
        float[] quiet = new float[InputAudioLevel.WINDOW_SAMPLES];
        Arrays.fill(quiet, 0.1f);
        InputAudioLevel.Levels second = meter.process(quiet, quiet.length);
        assertThat(second.peak).isWithin(1e-6f).of(0.1f);
        assertThat(second.rms).isWithin(1e-6f).of(0.1f);
    }

    @Test
    public void process_respectsLengthArgument() {
        InputAudioLevel meter = new InputAudioLevel();
        float[] data = new float[InputAudioLevel.WINDOW_SAMPLES + 500];
        Arrays.fill(data, 0.2f);
        data[InputAudioLevel.WINDOW_SAMPLES - 1] = 1.5f; // inside len

        // Only feed WINDOW_SAMPLES of the larger buffer.
        InputAudioLevel.Levels levels =
                meter.process(data, InputAudioLevel.WINDOW_SAMPLES);
        assertThat(levels).isNotNull();
        assertThat(levels.peak).isWithin(1e-6f).of(1.5f);
        // The 500 samples past len were not consumed: the next window starts
        // empty, so a fresh full window of 0.2 reads exactly 0.2 peak.
        float[] next = new float[InputAudioLevel.WINDOW_SAMPLES];
        Arrays.fill(next, 0.2f);
        InputAudioLevel.Levels levels2 = meter.process(next, next.length);
        assertThat(levels2.peak).isWithin(1e-6f).of(0.2f);
    }

    @Test
    public void process_nullBufferReturnsNull() {
        InputAudioLevel meter = new InputAudioLevel();
        assertThat(meter.process(null, 100)).isNull();
    }

    @Test
    public void process_oneBufferSpanningMultipleWindows_emitsLastWindow() {
        InputAudioLevel meter = new InputAudioLevel();
        float[] data = new float[InputAudioLevel.WINDOW_SAMPLES * 2];
        Arrays.fill(data, 0, InputAudioLevel.WINDOW_SAMPLES, 0.9f);
        Arrays.fill(data, InputAudioLevel.WINDOW_SAMPLES, data.length, 0.3f);
        InputAudioLevel.Levels levels = meter.process(data, data.length);
        // The snapshot reflects the most recently completed window.
        assertThat(levels).isNotNull();
        assertThat(levels.peak).isWithin(1e-6f).of(0.3f);
    }
}
