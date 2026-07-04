package com.k1af.ft8af.spectrum;

/**
 * Pure audio input-level metering for the receive level indicator.
 *
 * <p>A user comparing the app to WSJT-X often has the radio's USB audio set too quiet (weak
 * signals fall below the decode threshold) or hot enough to clip (intermodulation wrecks the
 * decode). Unlike WSJT-X the app gave no level feedback, so the gain was set blind. This turns
 * a raw 12&nbsp;kHz mono float buffer (samples in roughly [-1, 1]) into a peak/RMS reading plus
 * a coarse {@link Status} the UI can colour, so the operator can set gain the way WSJT-X's
 * meter lets them.
 *
 * <p>All thresholds are expressed in dBFS (decibels relative to full scale, so 0&nbsp;dB is a
 * sample magnitude of 1.0 and every value here is &le; 0). Clipping is judged from the peak;
 * "too quiet" from the RMS, which tracks the noise/signal energy rather than a lone spike.
 *
 * <p>This is deliberately a plain class with a static entry point so it is unit-testable
 * without Android; the fragment is a thin wrapper that maps {@link Status} to a colour.
 */
public final class AudioInputLevel {

    private AudioInputLevel() {}

    /** Coarse health of the input level, worst-to-best ordering aside. */
    public enum Status {
        /** Essentially no input — disconnected cable, wrong source, or muted radio. */
        SILENT,
        /** Audible but too quiet; weak signals will sit below the decode threshold. */
        LOW,
        /** In the healthy window — what the operator should aim for. */
        GOOD,
        /** Hot: peaks are close to full scale, raise nothing further. */
        HIGH,
        /** Peaks pinned at full scale; clipping distortion will block decodes. */
        CLIPPING
    }

    /** Floor we report instead of -infinity for a digitally silent buffer. */
    public static final float MIN_DBFS = -120f;

    // Peak at/above this linear magnitude is treated as clipping (~ -0.13 dBFS).
    static final float CLIP_PEAK = 0.985f;
    // Peak dBFS at/above this is "hot" (clipping risk on transients).
    static final float HIGH_PEAK_DBFS = -3f;
    // Below this RMS the buffer carries no usable signal.
    static final float SILENT_RMS_DBFS = -75f;
    // RMS below this (but above SILENT) is too quiet for reliable weak-signal decode.
    static final float LOW_RMS_DBFS = -45f;

    /** Immutable result of metering one buffer. */
    public static final class Reading {
        /** Largest sample magnitude in the buffer, linear (0..~1). */
        public final float peak;
        /** Root-mean-square sample magnitude, linear. */
        public final float rms;
        /** {@link #peak} in dBFS, clamped at {@link #MIN_DBFS}. */
        public final float peakDbfs;
        /** {@link #rms} in dBFS, clamped at {@link #MIN_DBFS}. */
        public final float rmsDbfs;
        /** Coarse health used to colour the meter. */
        public final Status status;

        Reading(float peak, float rms, float peakDbfs, float rmsDbfs, Status status) {
            this.peak = peak;
            this.rms = rms;
            this.peakDbfs = peakDbfs;
            this.rmsDbfs = rmsDbfs;
            this.status = status;
        }
    }

    /** A reading for an empty/absent buffer: digital silence. */
    private static final Reading SILENCE =
            new Reading(0f, 0f, MIN_DBFS, MIN_DBFS, Status.SILENT);

    /**
     * Meter one buffer of mono float samples.
     *
     * @param samples PCM samples in roughly [-1, 1]; null or empty yields a {@link Status#SILENT}
     *                reading rather than throwing, so the caller can meter unconditionally.
     */
    public static Reading compute(float[] samples) {
        if (samples == null || samples.length == 0) {
            return SILENCE;
        }
        float peak = 0f;
        double sumSquares = 0d;
        for (float s : samples) {
            float a = Math.abs(s);
            if (a > peak) {
                peak = a;
            }
            sumSquares += (double) s * (double) s;
        }
        float rms = (float) Math.sqrt(sumSquares / samples.length);
        return fromPeakRms(peak, rms);
    }

    /**
     * Meter from an already-accumulated linear peak/RMS pair — e.g. the 250&nbsp;ms
     * window snapshots {@code InputAudioLevel} produces for the Compose waterfall
     * strip. This is the single classification entry point for every RX level UI
     * (issue #405): the spectrum fragment's per-buffer {@link #compute} and the
     * windowed Compose meter both land here, so the "healthy gain window" lives in
     * exactly one set of thresholds.
     */
    public static Reading fromPeakRms(float peak, float rms) {
        float p = Math.max(0f, peak);
        float r = Math.max(0f, rms);
        float peakDbfs = toDbfs(p);
        float rmsDbfs = toDbfs(r);
        return new Reading(p, r, peakDbfs, rmsDbfs, classify(p, peakDbfs, rmsDbfs));
    }

    /** Linear magnitude (>=0) to dBFS, with a {@link #MIN_DBFS} floor for silence. */
    static float toDbfs(float linear) {
        if (linear <= 0f) {
            return MIN_DBFS;
        }
        float db = (float) (20.0 * Math.log10(linear));
        return db < MIN_DBFS ? MIN_DBFS : db;
    }

    static Status classify(float peak, float peakDbfs, float rmsDbfs) {
        if (peak >= CLIP_PEAK) {
            return Status.CLIPPING;
        }
        if (peakDbfs >= HIGH_PEAK_DBFS) {
            return Status.HIGH;
        }
        if (rmsDbfs < SILENT_RMS_DBFS) {
            return Status.SILENT;
        }
        if (rmsDbfs < LOW_RMS_DBFS) {
            return Status.LOW;
        }
        return Status.GOOD;
    }
}
