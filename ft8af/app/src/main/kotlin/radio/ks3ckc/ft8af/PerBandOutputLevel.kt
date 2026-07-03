package radio.ks3ckc.ft8af

import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.database.DatabaseOpr
import com.k1af.ft8af.rigs.BaseRigOperation

/**
 * Per-band TX output level support (issue #355).
 *
 * When "Save output level per band" is enabled, the app remembers the TX
 * output level (0-100, same scale as the "volumeValue" config) separately
 * for each band and restores it when the operator switches bands. Bands
 * with no saved value fall back to the current global level, unchanged.
 *
 * Persistence follows the excludedBands convention: a single config row
 * holding a comma-separated list, here of "band=level" pairs
 * (e.g. "20m=60,40m=85") under the key [PER_BAND_OUTPUT_LEVELS_KEY].
 *
 * The decision logic below is pure (no Android types) so it can be
 * unit-tested directly; the two `*ForCurrentBand` helpers at the bottom
 * are thin wiring around GeneralVariables/DatabaseOpr.
 */

/** Config key for the feature toggle ("1" = enabled, default off). */
const val PER_BAND_OUTPUT_LEVEL_KEY = "perBandOutputLevel"

/** Config key for the serialized band-to-level map. */
const val PER_BAND_OUTPUT_LEVELS_KEY = "perBandOutputLevels"

/**
 * Parse a serialized "band=level" CSV ("20m=60,40m=85") into an ordered map.
 * Malformed entries (missing '=', non-numeric level, blank band) are skipped;
 * levels are clamped to 0..100. Null/blank input yields an empty map.
 */
internal fun parsePerBandOutputLevels(serialized: String?): LinkedHashMap<String, Int> {
    val levels = LinkedHashMap<String, Int>()
    if (serialized.isNullOrBlank()) return levels
    for (entry in serialized.split(',')) {
        val idx = entry.indexOf('=')
        if (idx <= 0) continue
        val band = entry.substring(0, idx).trim()
        if (band.isEmpty()) continue
        val level = entry.substring(idx + 1).trim().toIntOrNull() ?: continue
        levels[band] = level.coerceIn(0, 100)
    }
    return levels
}

/** Serialize a band-to-level map back to the "20m=60,40m=85" config format. */
internal fun serializePerBandOutputLevels(levels: Map<String, Int>): String =
    levels.entries.joinToString(",") { (band, level) -> "$band=$level" }

/**
 * Decide whether switching to [band] should restore a saved output level.
 *
 * @return the saved level (0-100) to apply, or null when nothing should
 *   change: feature disabled, unknown band, no saved value for the band
 *   (global fallback), or the saved value already equals [currentLevel].
 */
internal fun resolvePerBandOutputLevel(
    enabled: Boolean,
    band: String?,
    serialized: String?,
    currentLevel: Int,
): Int? {
    if (!enabled || band.isNullOrBlank()) return null
    val saved = parsePerBandOutputLevels(serialized)[band] ?: return null
    return if (saved == currentLevel) null else saved
}

/**
 * Decide whether an output-level adjustment to [level] on [band] changes the
 * persisted per-band map.
 *
 * @return the new serialized map to persist, or null when nothing needs
 *   persisting (feature disabled, unknown band, or the band already stores
 *   this exact level).
 */
internal fun recordPerBandOutputLevel(
    enabled: Boolean,
    band: String?,
    level: Int,
    serialized: String?,
): String? {
    if (!enabled || band.isNullOrBlank()) return null
    val clamped = level.coerceIn(0, 100)
    val levels = parsePerBandOutputLevels(serialized)
    if (levels[band] == clamped) return null
    levels[band] = clamped
    return serializePerBandOutputLevels(levels)
}

/**
 * Record [level] (0-100) as the saved output level for the current band and
 * persist the updated map. No-op when the per-band feature is disabled or
 * the value is unchanged. Call wherever the output level is adjusted.
 */
fun saveOutputLevelForCurrentBand(databaseOpr: DatabaseOpr, level: Int) {
    val band = BaseRigOperation.getMeterFromFreq(GeneralVariables.band)
    val serialized = recordPerBandOutputLevel(
        GeneralVariables.savePerBandOutputLevel,
        band,
        level,
        GeneralVariables.perBandOutputLevels,
    ) ?: return
    GeneralVariables.perBandOutputLevels = serialized
    databaseOpr.writeConfig(PER_BAND_OUTPUT_LEVELS_KEY, serialized, null)
}

/**
 * Look up the saved output level for [band], or null when the current level
 * should be kept (feature off, no saved value, or already at that level).
 * Pure lookup — the caller applies/persists/toasts the result.
 */
fun restoredOutputLevelForBand(band: String?): Int? = resolvePerBandOutputLevel(
    GeneralVariables.savePerBandOutputLevel,
    band,
    GeneralVariables.perBandOutputLevels,
    Math.round(GeneralVariables.volumePercent * 100),
)
