package com.k1af.ft8af.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure logic for the "save output level per band" feature (issue #355).
 *
 * The per-band output levels are persisted in the app's {@code config} table as a
 * single JSON object under the key {@code perBandOutputLevels}, mapping a
 * wavelength band name (e.g. {@code "20m"}, as returned by
 * {@code BaseRigOperation.getMeterFromFreq}) to an output level percentage
 * (0-100). The effectful wiring (reading the current band, pushing the level to
 * the rig, persisting) lives in {@code MainViewModel}; everything here is a pure
 * function so it can be unit-tested without Android.
 */
public final class PerBandVolume {

    private PerBandVolume() {
    }

    /**
     * Parse the stored JSON map. Robust to null/empty/malformed input and to
     * non-numeric values — anything unparseable is skipped, yielding (at worst)
     * an empty map rather than throwing. Percentages are clamped to 0-100.
     */
    @NonNull
    public static Map<String, Integer> parse(@Nullable String json) {
        TreeMap<String, Integer> levels = new TreeMap<>();
        if (json == null || json.trim().isEmpty()) {
            return levels;
        }
        JSONObject obj;
        try {
            obj = new JSONObject(json);
        } catch (JSONException e) {
            return levels;
        }
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String band = keys.next();
            if (band == null || band.trim().isEmpty()) {
                continue;
            }
            int value = obj.optInt(band, Integer.MIN_VALUE);
            if (value == Integer.MIN_VALUE) {
                continue; // non-numeric / missing
            }
            levels.put(band, clampPercent(value));
        }
        return levels;
    }

    /**
     * Serialize the map back to a deterministic JSON string (keys sorted, which a
     * {@link TreeMap} gives us for free) so exports/backups are diff-friendly.
     */
    @NonNull
    public static String serialize(@NonNull Map<String, Integer> levels) {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Integer> e : new TreeMap<>(levels).entrySet()) {
            if (e.getKey() == null || e.getKey().trim().isEmpty() || e.getValue() == null) {
                continue;
            }
            try {
                obj.put(e.getKey(), clampPercent(e.getValue()));
            } catch (JSONException ignored) {
                // A well-formed String key can't actually throw here.
            }
        }
        return obj.toString();
    }

    /**
     * The saved level for {@code band}, or {@code null} if none is stored (the
     * caller should then keep the current/global level as the fallback).
     */
    @Nullable
    public static Integer lookup(@NonNull Map<String, Integer> levels, @Nullable String band) {
        if (band == null || band.trim().isEmpty()) {
            return null;
        }
        return levels.get(band);
    }

    /**
     * Return a copy of {@code levels} with {@code band} set to {@code percent}
     * (clamped to 0-100). A blank band name is a no-op — we never want to key an
     * entry under "" for an unknown/out-of-range frequency.
     */
    @NonNull
    public static Map<String, Integer> put(
            @NonNull Map<String, Integer> levels, @Nullable String band, int percent) {
        TreeMap<String, Integer> updated = new TreeMap<>(levels);
        if (band != null && !band.trim().isEmpty()) {
            updated.put(band, clampPercent(percent));
        }
        return updated;
    }

    private static int clampPercent(int percent) {
        if (percent < 0) {
            return 0;
        }
        if (percent > 100) {
            return 100;
        }
        return percent;
    }
}
