package com.k1af.ft8af.ft8transmit;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.k1af.ft8af.GeneralVariables;

/**
 * Protocol-agnostic controller that receives normalized ALC and SWR meter
 * values during TX and takes protective action:
 *   - ALC auto-volume: adjusts {@link GeneralVariables#volumePercent} to keep
 *     ALC in a target window, reducing overdrive distortion.
 *   - SWR TX halt + lockout: immediately stops TX and prevents re-activation
 *     when SWR exceeds a threshold, protecting the PA/feedline.
 *
 * Rig classes call {@link #onMeterUpdate(int, int)} from their showAlert()
 * methods, passing values already normalized to 0-255.
 */
public class MeterProtectionController {
    private static final String TAG = "MeterProtection";

    // Reference to the transmit signal — set after construction
    private FT8TransmitSignal transmitSignal;

    // ALC auto-volume tuning. The TX audio buffer has its volume baked in once at
    // the start of each cycle (the USB-direct path writes the entire buffer in a
    // single blocking call, and AudioTrack volume is set at play() time), so a
    // volume change can only affect the NEXT cycle. We therefore make exactly one
    // proportional correction per TX cycle, in onTxCycleEnd() — adjusting mid-cycle
    // just reacts repeatedly to a reading that cannot change yet and overshoots.
    private static final float VOLUME_GAIN = 0.4f;        // volume change per unit ALC error (ALC on 0-1 scale)
    private static final float VOLUME_MAX_STEP = 0.08f;   // cap any single cycle's change to 8%
    private static final float VOLUME_MIN = 0.05f;        // never below 5%
    private static final float VOLUME_MAX = 1.0f;

    // ALC running-average window (last N samples within a TX cycle)
    private static final int ALC_WINDOW_SIZE = 5;
    private final int[] alcWindow = new int[ALC_WINDOW_SIZE];
    private int alcWindowIndex = 0;
    private int alcWindowCount = 0;

    // Observable state for UI
    public final MutableLiveData<Boolean> swrLockout = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> lastAlc = new MutableLiveData<>(0);
    public final MutableLiveData<Integer> lastSwr = new MutableLiveData<>(0);
    // Holds the SWR ratio string (e.g. "3.2:1") that triggered lockout, for the banner.
    public final MutableLiveData<String> lockoutSwrRatio = new MutableLiveData<>("");

    // Throttle for the per-reading SWR diagnostic log line (meter polls ~every 2s).
    private static final long SWR_LOG_THROTTLE_MS = 1500;
    private long lastSwrLogMs = 0;
    private int lastLoggedSwr = -1;

    public void setTransmitSignal(FT8TransmitSignal signal) {
        this.transmitSignal = signal;
    }

    /**
     * Whether SWR is high enough to halt TX: protection enabled, a valid reading, and over
     * the threshold. Pure so the rule is unit-tested independent of the rig meter plumbing.
     */
    public static boolean shouldHaltForSwr(int normalizedSwr, boolean enabled, int threshold) {
        return normalizedSwr >= 0 && enabled && normalizedSwr > threshold;
    }

    /**
     * Called by each rig's showAlert() with normalized 0-255 meter values.
     * A value of -1 means the rig does not report that meter.
     */
    public void onMeterUpdate(int normalizedAlc, int normalizedSwr) {
        // Publish for optional UI display
        if (normalizedAlc >= 0) lastAlc.postValue(normalizedAlc);
        if (normalizedSwr >= 0) lastSwr.postValue(normalizedSwr);

        // Diagnostics: when SWR protection is on, record each SWR reading we actually
        // receive so the debug.log shows whether meter data even reaches here during TX, the
        // values, and the halt decision. Without this a halt that never fires leaves no trace
        // — we can't tell "no meter data" from "value below threshold" from a wiring problem.
        // (Reported: "SWR protection not working".) Log whenever the SWR value CHANGES (so a
        // real SWR update isn't suppressed when ALC and SWR arrive as separate callbacks
        // close together), plus a periodic time-throttled line for a stable value.
        if (GeneralVariables.swrHaltEnabled && normalizedSwr >= 0) {
            long now = System.currentTimeMillis();
            boolean changed = normalizedSwr != lastLoggedSwr;
            if (changed || now - lastSwrLogMs >= SWR_LOG_THROTTLE_MS) {
                lastSwrLogMs = now;
                lastLoggedSwr = normalizedSwr;
                GeneralVariables.fileLog(String.format(
                        "MeterProtection: SWR reading swr=%d threshold=%d -> %s",
                        normalizedSwr, GeneralVariables.swrHaltThreshold,
                        shouldHaltForSwr(normalizedSwr, GeneralVariables.swrHaltEnabled,
                                GeneralVariables.swrHaltThreshold) ? "HALT" : "ok"));
            }
        }

        // --- SWR halt check ---
        if (shouldHaltForSwr(normalizedSwr, GeneralVariables.swrHaltEnabled,
                GeneralVariables.swrHaltThreshold)) {
            haltForSwr(normalizedSwr);
            return; // no point adjusting volume if we just killed TX
        }

        // --- ALC sampling (only when enabled and we have a valid reading) ---
        // We just accumulate here; the single per-cycle volume correction is made
        // in onTxCycleEnd(). Adjusting mid-cycle is pointless because the current
        // cycle's audio volume is already fixed, and reacting to the same reading
        // every poll caused a violent over-correction (see class comment).
        if (normalizedAlc >= 0 && GeneralVariables.autoVolumeEnabled) {
            accumulateAlc(normalizedAlc);
        }
    }

    /**
     * Called after each TX cycle completes (from afterPlayAudio or onAfterTransmit).
     * Makes a single proportional volume correction for the next cycle based on the
     * average ALC seen during this cycle. Doing exactly one correction per cycle —
     * rather than one per meter poll — is required because the transmitted audio's
     * volume is fixed for the duration of a cycle, so intra-cycle changes have no
     * effect and only cause the loop to overshoot.
     */
    public void onTxCycleEnd() {
        if (!GeneralVariables.autoVolumeEnabled) {
            resetAccumulators();
            return;
        }

        int avg = getAlcAverage();
        if (avg >= 0) {
            int low = GeneralVariables.alcTargetLow;
            int high = GeneralVariables.alcTargetHigh;
            // Only correct when ALC is outside the target window; aim for its midpoint.
            if (avg < low || avg > high) {
                int target = (low + high) / 2;
                float error = (target - avg) / 255f;      // -1..1 (negative = too hot)
                float step = error * VOLUME_GAIN;
                if (step > VOLUME_MAX_STEP) step = VOLUME_MAX_STEP;
                if (step < -VOLUME_MAX_STEP) step = -VOLUME_MAX_STEP;
                float newVol = GeneralVariables.volumePercent + step;
                if (newVol < VOLUME_MIN) newVol = VOLUME_MIN;
                if (newVol > VOLUME_MAX) newVol = VOLUME_MAX;
                if (newVol != GeneralVariables.volumePercent) {
                    GeneralVariables.volumePercent = newVol;
                    GeneralVariables.mutableVolumePercent.postValue(newVol);
                    GeneralVariables.fileLog(String.format(
                            "MeterProtection: ALC avg=%d target=%d-%d, vol %s to %.0f%%",
                            avg, low, high, step < 0 ? "down" : "up", newVol * 100));
                }
            }
            // Persist updated volume to DB (fire-and-forget)
            persistVolume();
        }

        resetAccumulators();
    }

    /**
     * Halt TX and set lockout due to high SWR.
     */
    private void haltForSwr(int normalizedSwr) {
        Log.w(TAG, "SWR halt triggered: normalized=" + normalizedSwr);
        GeneralVariables.fileLog("MeterProtection: SWR HALT triggered, swr=" + normalizedSwr);

        String ratio = normalizedSwrToRatio(normalizedSwr);
        lockoutSwrRatio.postValue(ratio);
        swrLockout.postValue(true);

        if (transmitSignal != null) {
            transmitSignal.setActivated(false);
        }
    }

    /**
     * User dismisses the SWR lockout banner, allowing TX again.
     */
    public void clearSwrLockout() {
        swrLockout.postValue(false);
        lockoutSwrRatio.postValue("");
        GeneralVariables.fileLog("MeterProtection: SWR lockout cleared by user");
    }

    /**
     * Check whether SWR lockout is currently active.
     */
    public boolean isSwrLocked() {
        Boolean locked = swrLockout.getValue();
        return locked != null && locked;
    }

    /**
     * Reset all accumulators. Called on rig disconnect or TX mode change.
     */
    public void reset() {
        resetAccumulators();
    }

    private void accumulateAlc(int value) {
        alcWindow[alcWindowIndex] = value;
        alcWindowIndex = (alcWindowIndex + 1) % ALC_WINDOW_SIZE;
        if (alcWindowCount < ALC_WINDOW_SIZE) alcWindowCount++;
    }

    /**
     * @return running average of ALC samples, or -1 if no samples yet.
     */
    private int getAlcAverage() {
        if (alcWindowCount == 0) return -1;
        int sum = 0;
        for (int i = 0; i < alcWindowCount; i++) {
            sum += alcWindow[i];
        }
        return sum / alcWindowCount;
    }

    private void resetAccumulators() {
        alcWindowIndex = 0;
        alcWindowCount = 0;
    }

    private void persistVolume() {
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx == null) return;
            com.k1af.ft8af.database.DatabaseOpr db =
                    com.k1af.ft8af.database.DatabaseOpr.getInstance(ctx, "data.db");
            if (db != null) {
                int pct = radio.ks3ckc.ft8af.PerBandOutputLevelKt
                        .outputLevelFromVolumePercent(GeneralVariables.volumePercent);
                db.writeConfig("volumeValue", String.valueOf(pct), null);
                // Keep the per-band map in step with a protective auto-reduction
                // (high SWR/ALC is band/antenna specific) so re-entering the band
                // doesn't restore the level that tripped protection. No-op when
                // per-band levels are disabled.
                radio.ks3ckc.ft8af.PerBandOutputLevelKt.saveOutputLevelForCurrentBand(db, pct);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist volume", e);
        }
    }

    /**
     * Approximate mapping of normalized 0-255 SWR value to a human-readable ratio string.
     * The mapping is intentionally coarse — the exact curve varies by rig manufacturer,
     * but the general shape (roughly quadratic) is similar across Yaesu/Icom/Kenwood:
     *   0 = 1.0:1, ~60 = 1.5:1, ~120 = 3.0:1, 200+ = high/infinity.
     */
    public static String normalizedSwrToRatio(int normalized) {
        if (normalized <= 0) return "1.0:1";
        if (normalized >= 255) return ">10:1";
        // Piecewise linear approximation
        float ratio;
        if (normalized <= 60) {
            ratio = 1.0f + (normalized / 60f) * 0.5f;       // 0→1.0, 60→1.5
        } else if (normalized <= 120) {
            ratio = 1.5f + ((normalized - 60) / 60f) * 1.5f; // 60→1.5, 120→3.0
        } else if (normalized <= 200) {
            ratio = 3.0f + ((normalized - 120) / 80f) * 4.0f; // 120→3.0, 200→7.0
        } else {
            ratio = 7.0f + ((normalized - 200) / 55f) * 3.0f; // 200→7.0, 255→10.0
        }
        return String.format("%.1f:1", ratio);
    }

    /**
     * Convert a human-readable SWR ratio (e.g. 3.0) back to the approximate normalized
     * 0-255 value. Used by the settings slider.
     */
    public static int swrRatioToNormalized(float ratio) {
        if (ratio <= 1.0f) return 0;
        if (ratio <= 1.5f) return Math.round((ratio - 1.0f) / 0.5f * 60);
        if (ratio <= 3.0f) return 60 + Math.round((ratio - 1.5f) / 1.5f * 60);
        if (ratio <= 7.0f) return 120 + Math.round((ratio - 3.0f) / 4.0f * 80);
        if (ratio <= 10.0f) return 200 + Math.round((ratio - 7.0f) / 3.0f * 55);
        return 255;
    }
}
