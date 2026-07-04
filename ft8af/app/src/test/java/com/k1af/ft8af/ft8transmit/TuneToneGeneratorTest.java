package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link TuneToneGenerator} — the pure-DSP sample source behind
 * the Tune carrier (issue #408). Plain JUnit: deterministic arithmetic, no
 * Android or native dependencies.
 */
public class TuneToneGeneratorTest {

    private static final int SR = 12000;
    private static final int RAMP = 60;

    private static float[] collect(TuneToneGenerator gen, int chunkLen, int chunks, float amp) {
        float[] all = new float[chunkLen * chunks];
        int pos = 0;
        float[] chunk = new float[chunkLen];
        for (int c = 0; c < chunks; c++) {
            int n = gen.nextChunk(chunk, chunkLen, amp);
            System.arraycopy(chunk, 0, all, pos, n);
            pos += n;
        }
        float[] out = new float[pos];
        System.arraycopy(all, 0, out, 0, pos);
        return out;
    }

    @Test
    public void steadyState_matchesSineWithinTolerance() {
        // Skip the ramp, then every sample must equal a*sin(2*pi*f*n/sr).
        TuneToneGenerator gen = new TuneToneGenerator(1500f, SR, RAMP);
        float[] samples = collect(gen, 300, 4, 0.8f);
        for (int n = RAMP; n < samples.length; n++) {
            double expected = 0.8 * Math.sin(2.0 * Math.PI * 1500.0 * n / SR);
            assertThat((double) samples[n]).isWithin(1e-4).of(expected);
        }
    }

    @Test
    public void phaseIsContinuousAcrossChunkBoundaries() {
        // Generate the same tone as one big chunk and as many small chunks;
        // identical output means no discontinuity/click at chunk boundaries.
        TuneToneGenerator one = new TuneToneGenerator(1000f, SR, RAMP);
        TuneToneGenerator many = new TuneToneGenerator(1000f, SR, RAMP);
        float[] whole = new float[1200];
        one.nextChunk(whole, whole.length, 1f);
        float[] pieces = collect(many, 100, 12, 1f);
        assertThat(pieces.length).isEqualTo(whole.length);
        for (int i = 0; i < whole.length; i++) {
            assertThat(pieces[i]).isEqualTo(whole[i]);
        }
    }

    @Test
    public void rampUp_isMonotonicEnvelope() {
        // Envelope check on a "frequency 3000 = sr/4" tone whose |sin| hits 1
        // every 4th sample: peaks inside the ramp must rise monotonically and
        // stay below full scale until the ramp ends.
        TuneToneGenerator gen = new TuneToneGenerator(3000f, SR, RAMP);
        float[] samples = collect(gen, RAMP * 2, 1, 1f);
        float lastPeak = 0f;
        for (int n = 1; n < RAMP; n += 4) { // |sin| == 1 at n = 1, 5, 9, ...
            float peak = Math.abs(samples[n]);
            assertThat(peak).isAtLeast(lastPeak);
            assertThat(peak).isLessThan(1.0f);
            lastPeak = peak;
        }
        // Past the ramp the same phase points sit at full amplitude.
        assertThat(Math.abs(samples[RAMP + 1])).isWithin(1e-4f).of(1.0f);
    }

    @Test
    public void requestStop_rampsToZeroAndFinishes() {
        TuneToneGenerator gen = new TuneToneGenerator(1500f, SR, RAMP);
        float[] chunk = new float[300];
        gen.nextChunk(chunk, chunk.length, 1f); // past the attack

        gen.requestStop();
        float[] tail = new float[300];
        int n = gen.nextChunk(tail, tail.length, 1f);
        // Exactly one ramp of samples remains, then the generator is done.
        assertThat(n).isEqualTo(RAMP);
        assertThat(gen.isFinished()).isTrue();
        assertThat(gen.nextChunk(tail, tail.length, 1f)).isEqualTo(0);
        // The very last emitted samples are at/near zero — no key-up click.
        assertThat(Math.abs(tail[n - 1])).isLessThan(0.01f);
    }

    @Test
    public void stopDuringAttack_stillTapersAndFinishes() {
        TuneToneGenerator gen = new TuneToneGenerator(1500f, SR, RAMP);
        float[] chunk = new float[RAMP / 2];
        gen.nextChunk(chunk, chunk.length, 1f); // mid-attack
        gen.requestStop();
        float[] tail = new float[RAMP * 2];
        int n = gen.nextChunk(tail, tail.length, 1f);
        assertThat(n).isEqualTo(RAMP);
        assertThat(gen.isFinished()).isTrue();
        for (int i = 0; i < n; i++) {
            // The product of the two half-envelopes can never exceed either one.
            assertThat(Math.abs(tail[i])).isLessThan(1.0f);
        }
    }

    @Test
    public void amplitudeIsClampedAndAppliedPerChunk() {
        TuneToneGenerator gen = new TuneToneGenerator(3000f, SR, 1);
        float[] chunk = new float[64];
        gen.nextChunk(chunk, chunk.length, 5f); // clamps to 1.0
        float peak = 0f;
        for (float s : chunk) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertThat(peak).isAtMost(1.0f);
        assertThat(peak).isWithin(1e-4f).of(1.0f);

        // Live level change: next chunk at 25% has a proportional peak.
        gen.nextChunk(chunk, chunk.length, 0.25f);
        peak = 0f;
        for (float s : chunk) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertThat(peak).isWithin(1e-4f).of(0.25f);
    }

    @Test
    public void degenerateInputs_areSane() {
        // Negative frequency clamps to 0 Hz: a silent (all-zero) carrier, not a crash.
        TuneToneGenerator dc = new TuneToneGenerator(-100f, SR, RAMP);
        float[] chunk = new float[64];
        assertThat(dc.nextChunk(chunk, chunk.length, 1f)).isEqualTo(64);
        for (float s : chunk) {
            assertThat(s).isEqualTo(0f);
        }
        // Zero-length requests and null buffers are no-ops.
        TuneToneGenerator gen = new TuneToneGenerator(1500f, SR, RAMP);
        assertThat(gen.nextChunk(chunk, 0, 1f)).isEqualTo(0);
        assertThat(gen.nextChunk(null, 10, 1f)).isEqualTo(0);
        // Invalid sample rate is a programming error and throws.
        try {
            new TuneToneGenerator(1500f, 0, RAMP);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
