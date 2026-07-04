package com.k1af.ft8af.spectrum;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.spectrum.AudioInputLevel.Reading;
import com.k1af.ft8af.spectrum.AudioInputLevel.Status;

import org.junit.Test;

/**
 * Pure-logic coverage for the receive input-level meter. No Android types are touched, so no
 * Robolectric runner is needed.
 */
public class AudioInputLevelTest {

    private static float[] constant(float magnitude, int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            // Alternate sign so it looks like an AC signal; |sample| == magnitude either way.
            a[i] = (i % 2 == 0) ? magnitude : -magnitude;
        }
        return a;
    }

    @Test
    public void nullOrEmpty_isSilent() {
        for (float[] empty : new float[][]{null, new float[0]}) {
            Reading r = AudioInputLevel.compute(empty);
            assertThat(r.status).isEqualTo(Status.SILENT);
            assertThat(r.peak).isEqualTo(0f);
            assertThat(r.peakDbfs).isEqualTo(AudioInputLevel.MIN_DBFS);
            assertThat(r.rmsDbfs).isEqualTo(AudioInputLevel.MIN_DBFS);
        }
    }

    // ---- fromPeakRms (issue #405: the shared entry point for windowed meters) ----

    @Test
    public void fromPeakRms_agreesWithComputeOnTheSameAudio() {
        // The Compose strip feeds window peak/RMS pairs through fromPeakRms;
        // classification must be identical to compute() over the raw buffer.
        float[][] buffers = {
                constant(0.001f, 256), // LOW
                constant(0.1f, 256),   // GOOD
                constant(0.9f, 256),   // HIGH
                constant(1.0f, 64),    // CLIPPING
        };
        for (float[] buffer : buffers) {
            Reading direct = AudioInputLevel.compute(buffer);
            Reading viaPair = AudioInputLevel.fromPeakRms(direct.peak, direct.rms);
            assertThat(viaPair.status).isEqualTo(direct.status);
            assertThat(viaPair.peakDbfs).isEqualTo(direct.peakDbfs);
            assertThat(viaPair.rmsDbfs).isEqualTo(direct.rmsDbfs);
        }
    }

    @Test
    public void fromPeakRms_zeroPair_isSilent() {
        Reading r = AudioInputLevel.fromPeakRms(0f, 0f);
        assertThat(r.status).isEqualTo(Status.SILENT);
        assertThat(r.peakDbfs).isEqualTo(AudioInputLevel.MIN_DBFS);
        assertThat(r.rmsDbfs).isEqualTo(AudioInputLevel.MIN_DBFS);
    }

    @Test
    public void fromPeakRms_negativeInputs_clampToSilence() {
        // Defensive: a corrupted snapshot must not produce NaN dBFS.
        Reading r = AudioInputLevel.fromPeakRms(-0.5f, -0.5f);
        assertThat(r.peak).isEqualTo(0f);
        assertThat(r.rms).isEqualTo(0f);
        assertThat(r.status).isEqualTo(Status.SILENT);
    }

    @Test
    public void fromPeakRms_nonFiniteInputs_sanitizeToSilence() {
        // Math.max(0f, NaN) is NaN, and every classify() comparison against NaN
        // is false — which would misreport GOOD with NaN dB values. Non-finite
        // inputs (corrupted upstream math) must read as digital silence instead.
        float[] poison = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float bad : poison) {
            Reading r = AudioInputLevel.fromPeakRms(bad, bad);
            assertThat(r.peak).isEqualTo(0f);
            assertThat(r.rms).isEqualTo(0f);
            assertThat(r.peakDbfs).isEqualTo(AudioInputLevel.MIN_DBFS);
            assertThat(r.rmsDbfs).isEqualTo(AudioInputLevel.MIN_DBFS);
            assertThat(r.status).isEqualTo(Status.SILENT);
        }
        // One poisoned field doesn't corrupt the other's value.
        Reading mixed = AudioInputLevel.fromPeakRms(Float.NaN, 0.05f);
        assertThat(mixed.peak).isEqualTo(0f);
        assertThat(mixed.rms).isWithin(1e-6f).of(0.05f);
    }

    @Test
    public void peakAndRms_ofConstantMagnitude() {
        // |sample| == 0.1 everywhere, so peak == rms == 0.1, i.e. -20 dBFS.
        Reading r = AudioInputLevel.compute(constant(0.1f, 256));
        assertThat(r.peak).isWithin(1e-6f).of(0.1f);
        assertThat(r.rms).isWithin(1e-6f).of(0.1f);
        assertThat(r.peakDbfs).isWithin(0.01f).of(-20f);
        assertThat(r.rmsDbfs).isWithin(0.01f).of(-20f);
        assertThat(r.status).isEqualTo(Status.GOOD);
    }

    @Test
    public void fullScale_clips() {
        Reading r = AudioInputLevel.compute(constant(1.0f, 64));
        assertThat(r.peak).isEqualTo(1.0f);
        assertThat(r.peakDbfs).isWithin(0.001f).of(0f);
        assertThat(r.status).isEqualTo(Status.CLIPPING);
    }

    @Test
    public void singleHotPeak_clipsEvenIfQuietOverall() {
        // Mostly quiet, one sample pinned at full scale: peak-driven clipping must win over the
        // low RMS.
        float[] a = constant(0.001f, 256);
        a[100] = 1.0f;
        Reading r = AudioInputLevel.compute(a);
        assertThat(r.status).isEqualTo(Status.CLIPPING);
    }

    @Test
    public void hotPeak_belowClip_isHigh() {
        // Quiet floor with a 0.8 transient: peak -1.9 dBFS >= the -3 dB "hot" line, but not
        // clipping. Peak path drives HIGH regardless of the low RMS.
        float[] a = constant(0.001f, 256);
        a[10] = 0.8f;
        Reading r = AudioInputLevel.compute(a);
        assertThat(r.status).isEqualTo(Status.HIGH);
    }

    @Test
    public void quietSignal_isLow() {
        // |sample| == 0.005 -> ~ -46 dBFS, below the -45 dB "too quiet" line but above silence.
        Reading r = AudioInputLevel.compute(constant(0.005f, 256));
        assertThat(r.status).isEqualTo(Status.LOW);
    }

    @Test
    public void essentiallyNoSignal_isSilent() {
        // |sample| == 0.0001 -> -80 dBFS, below the -75 dB silence line.
        Reading r = AudioInputLevel.compute(constant(0.0001f, 256));
        assertThat(r.status).isEqualTo(Status.SILENT);
    }

    @Test
    public void toDbfs_handlesZeroAndUnity() {
        assertThat(AudioInputLevel.toDbfs(0f)).isEqualTo(AudioInputLevel.MIN_DBFS);
        assertThat(AudioInputLevel.toDbfs(1f)).isWithin(0.001f).of(0f);
        assertThat(AudioInputLevel.toDbfs(0.5f)).isWithin(0.01f).of(-6.02f);
    }
}
