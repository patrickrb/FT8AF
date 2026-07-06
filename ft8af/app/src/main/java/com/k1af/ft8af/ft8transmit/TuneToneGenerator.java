package com.k1af.ft8af.ft8transmit;

/**
 * Chunked, phase-continuous single-tone generator for the Tune function
 * (issue #408): a pure sinusoid at the TX audio offset, held for as long as
 * the operator tunes, with a raised-cosine ramp at start and stop so keying
 * never clicks/splatters.
 *
 * <p>Unlike the FT8 waveform (generated whole by the native library), the tune
 * tone is open-ended, so it is produced in short chunks; the caller polls
 * "still tuning?" between chunks and requests the final ramp-down via
 * {@link #requestStop()}. The phase accumulator carries across chunks, so
 * there is no discontinuity at chunk boundaries.
 *
 * <p>Pure arithmetic with injected frequency/sample-rate/amplitude — no
 * Android, no native code — so it is deterministic and unit-testable
 * (CLAUDE.md testing rule).
 */
public final class TuneToneGenerator {

    /** Ramp length: ~5 ms at 48 kHz. Long enough to kill the key click. */
    public static final int DEFAULT_RAMP_SAMPLES = 240;

    private final double phaseIncrement;
    private final int rampSamples;

    private double phase = 0.0;
    /** Samples produced so far (drives the ramp-up envelope). */
    private long produced = 0;
    /** >=0 once stop was requested: samples of ramp-down already emitted. */
    private int rampDownEmitted = -1;
    private boolean finished = false;

    /**
     * @param frequencyHz tone frequency; non-positive values are clamped to 0
     *                    (silence at constant phase) rather than throwing
     * @param sampleRate  output sample rate in Hz (must be positive)
     * @param rampSamples raised-cosine ramp length in samples (clamped to >= 1)
     */
    public TuneToneGenerator(float frequencyHz, int sampleRate, int rampSamples) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        float f = Math.max(0f, frequencyHz);
        this.phaseIncrement = 2.0 * Math.PI * f / sampleRate;
        this.rampSamples = Math.max(1, rampSamples);
    }

    /** Begin the raised-cosine ramp-down; the generator finishes one ramp later. */
    public void requestStop() {
        if (rampDownEmitted < 0) {
            rampDownEmitted = 0;
        }
    }

    /** True once the ramp-down has fully played out; no more samples will come. */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Fill {@code out[0..len)} with the next chunk of the tone at the given
     * amplitude (clamped to [0, 1]; read fresh per chunk so a live level change
     * takes effect within one chunk, like the FT8 volume slider).
     *
     * @return the number of samples written: {@code len} in steady state,
     *     possibly fewer on the final ramp-down chunk, 0 when finished.
     */
    public int nextChunk(float[] out, int len, float amplitude) {
        if (finished || out == null || len <= 0) {
            return 0;
        }
        int n = Math.min(len, out.length);
        float a = Math.max(0f, Math.min(1f, amplitude));
        int written = 0;
        for (int i = 0; i < n; i++) {
            double env = envelope();
            if (finished) {
                break;
            }
            out[i] = (float) (a * env * Math.sin(phase));
            phase += phaseIncrement;
            if (phase >= 2.0 * Math.PI) {
                phase -= 2.0 * Math.PI;
            }
            produced++;
            if (rampDownEmitted >= 0) {
                rampDownEmitted++;
            }
            written++;
        }
        return written;
    }

    /**
     * Envelope for the sample about to be produced. Ramp-up and ramp-down are
     * multiplied so a stop during the attack tapers cleanly from wherever the
     * attack had reached.
     */
    private double envelope() {
        double env = 1.0;
        if (produced < rampSamples) {
            env *= 0.5 * (1.0 - Math.cos(Math.PI * produced / (double) rampSamples));
        }
        if (rampDownEmitted >= 0) {
            if (rampDownEmitted >= rampSamples) {
                finished = true;
                return 0.0;
            }
            env *= 0.5 * (1.0 + Math.cos(Math.PI * rampDownEmitted / (double) rampSamples));
        }
        return env;
    }

    /** Current phase in radians — exposed for the continuity unit test. */
    double currentPhase() {
        return phase;
    }
}
