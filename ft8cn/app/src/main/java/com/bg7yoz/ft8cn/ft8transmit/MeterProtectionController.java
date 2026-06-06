package com.bg7yoz.ft8cn.ft8transmit;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.GeneralVariables;

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

    // ALC auto-volume tuning constants
    private static final float VOLUME_STEP_DOWN = 0.02f;  // 2% per adjustment
    private static final float VOLUME_STEP_UP = 0.01f;    // 1% per adjustment
    private static final float VOLUME_MIN = 0.05f;        // never below 5%
    private static final float VOLUME_MAX = 1.0f;

    // ALC running-average window (last N samples within a TX cycle)
    private static final int ALC_WINDOW_SIZE = 5;
    private final int[] alcWindow = new int[ALC_WINDOW_SIZE];
    private int alcWindowIndex = 0;
    private int alcWindowCount = 0;

    // Track whether we adjusted down mid-TX this cycle (suppress up-adjust mid-TX)
    private boolean adjustedDownThisCycle = false;

    // Observable state for UI
    public final MutableLiveData<Boolean> swrLockout = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> lastAlc = new MutableLiveData<>(0);
    public final MutableLiveData<Integer> lastSwr = new MutableLiveData<>(0);
    // Holds the SWR ratio string (e.g. "3.2:1") that triggered lockout, for the banner.
    public final MutableLiveData<String> lockoutSwrRatio = new MutableLiveData<>("");

    public void setTransmitSignal(FT8TransmitSignal signal) {
        this.transmitSignal = signal;
    }

    /**
     * Called by each rig's showAlert() with normalized 0-255 meter values.
     * A value of -1 means the rig does not report that meter.
     */
    public void onMeterUpdate(int normalizedAlc, int normalizedSwr) {
        // Publish for optional UI display
        if (normalizedAlc >= 0) lastAlc.postValue(normalizedAlc);
        if (normalizedSwr >= 0) lastSwr.postValue(normalizedSwr);

        // --- SWR halt check ---
        if (normalizedSwr >= 0 && GeneralVariables.swrHaltEnabled
                && normalizedSwr > GeneralVariables.swrHaltThreshold) {
            haltForSwr(normalizedSwr);
            return; // no point adjusting volume if we just killed TX
        }

        // --- ALC auto-volume (only when enabled and we have a valid reading) ---
        if (normalizedAlc >= 0 && GeneralVariables.autoVolumeEnabled) {
            accumulateAlc(normalizedAlc);
            int avg = getAlcAverage();
            if (avg < 0) return; // not enough samples yet

            if (avg > GeneralVariables.alcTargetHigh) {
                // Too hot — reduce volume immediately (mid-TX for AudioTrack path)
                float newVol = GeneralVariables.volumePercent - VOLUME_STEP_DOWN;
                if (newVol < VOLUME_MIN) newVol = VOLUME_MIN;
                GeneralVariables.volumePercent = newVol;
                GeneralVariables.mutableVolumePercent.postValue(newVol);
                adjustedDownThisCycle = true;
                GeneralVariables.fileLog(String.format(
                        "MeterProtection: ALC high (avg=%d > %d), vol down to %.0f%%",
                        avg, GeneralVariables.alcTargetHigh, newVol * 100));
            }
            // Up-adjust is deferred to onTxCycleEnd() to avoid oscillation
        }
    }

    /**
     * Called after each TX cycle completes (from afterPlayAudio or onAfterTransmit).
     * Applies between-cycle ALC volume increase if ALC was consistently low.
     */
    public void onTxCycleEnd() {
        if (!GeneralVariables.autoVolumeEnabled) {
            resetAccumulators();
            return;
        }

        int avg = getAlcAverage();
        if (avg >= 0 && avg < GeneralVariables.alcTargetLow && !adjustedDownThisCycle) {
            // ALC consistently low — nudge volume up for next cycle
            float newVol = GeneralVariables.volumePercent + VOLUME_STEP_UP;
            if (newVol > VOLUME_MAX) newVol = VOLUME_MAX;
            GeneralVariables.volumePercent = newVol;
            GeneralVariables.mutableVolumePercent.postValue(newVol);
            GeneralVariables.fileLog(String.format(
                    "MeterProtection: ALC low (avg=%d < %d), vol up to %.0f%%",
                    avg, GeneralVariables.alcTargetLow, newVol * 100));
        }

        // Persist updated volume to DB (fire-and-forget)
        if (alcWindowCount > 0) {
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
        adjustedDownThisCycle = false;
    }

    private void persistVolume() {
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx == null) return;
            com.bg7yoz.ft8cn.database.DatabaseOpr db =
                    com.bg7yoz.ft8cn.database.DatabaseOpr.getInstance(ctx, "data.db");
            if (db != null) {
                int pct = Math.round(GeneralVariables.volumePercent * 100);
                db.writeConfig("volumeValue", String.valueOf(pct), null);
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
