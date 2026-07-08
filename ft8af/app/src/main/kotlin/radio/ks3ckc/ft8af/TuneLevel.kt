package radio.ks3ckc.ft8af

import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.database.DatabaseOpr
import com.k1af.ft8af.rigs.BaseRigOperation

/**
 * Tune audio level resolution (issue #408).
 *
 * The tune carrier's drive is settable independently of the FT8 TX drive: a
 * 100% duty-cycle carrier is far more thermally stressful than intermittent
 * FT8, and tuning at reduced power into a deliberately bad match is standard
 * practice. Two modes:
 *
 *  - "Same as TX drive" (default): the tone uses whatever the FT8 path would
 *    use right now — the live volume level. When "Save output level per band"
 *    is on, that level is already the per-band FT8 level (band changes restore
 *    it into the live volume), so no extra lookup is needed.
 *  - "Independent": a distinct tune level stored under its own config key,
 *    never touching the FT8 drive. When "Save output level per band" is on,
 *    the tune level is remembered per band in a parallel map
 *    ([PER_BAND_TUNE_LEVELS_KEY]) reusing the exact parse/serialize/resolve
 *    helpers from [PerBandOutputLevel] — same toggle, separate backing store,
 *    so the two maps never clobber each other.
 *
 * Pure decision logic up top (unit-tested); thin GeneralVariables/DatabaseOpr
 * wiring at the bottom.
 */

/** Config key: tune max-on time in seconds (clamped by TuneController). */
const val TUNE_MAX_ON_SECONDS_KEY = "tuneMaxOnSeconds"

/** Config key: "1" = independent tune level, else same-as-TX-drive (default). */
const val TUNE_LEVEL_INDEPENDENT_KEY = "tuneLevelIndependent"

/** Config key: the global independent tune level, 0..100. */
const val TUNE_LEVEL_KEY = "tuneLevel"

/** Config key: the per-band independent tune levels ("20m=25,40m=40"). */
const val PER_BAND_TUNE_LEVELS_KEY = "perBandTuneLevels"

/** Config key: tune method — TuneMethod.AUTOMATIC/INTERNAL/TONE (issue #425). */
const val TUNE_METHOD_KEY = "tuneMethod"

/**
 * Resolve the level (0..100) the tune tone should play at.
 *
 * @param independent      independent-tune-level mode is enabled
 * @param globalTuneLevel  the stored global tune level (0..100)
 * @param perBandEnabled   the existing "Save output level per band" toggle
 * @param band             current band label (e.g. "20m"), null when unknown
 * @param serializedTuneLevels the persisted per-band tune map
 * @param txLevel          the live FT8 TX level (0..100)
 */
internal fun resolveTuneLevel(
    independent: Boolean,
    globalTuneLevel: Int,
    perBandEnabled: Boolean,
    band: String?,
    serializedTuneLevels: String?,
    txLevel: Int,
): Int {
    if (!independent) return txLevel.coerceIn(0, 100)
    if (perBandEnabled && !band.isNullOrBlank()) {
        parsePerBandOutputLevels(serializedTuneLevels)[band]?.let { return it }
    }
    return globalTuneLevel.coerceIn(0, 100)
}

/** Serializes per-band tune-map read-modify-writes (settings UI thread today). */
private val perBandTuneLevelLock = Any()

/**
 * Record [level] (0..100) as the saved independent tune level for the current
 * band and persist the updated map. No-op when the per-band feature is
 * disabled or the value is unchanged. Never touches the FT8 per-band map.
 */
fun saveTuneLevelForCurrentBand(databaseOpr: DatabaseOpr, level: Int) {
    val band = BaseRigOperation.getMeterFromFreq(GeneralVariables.band)
    synchronized(perBandTuneLevelLock) {
        val serialized = recordPerBandOutputLevel(
            GeneralVariables.savePerBandOutputLevel,
            band,
            level,
            GeneralVariables.perBandTuneLevels,
        ) ?: return
        GeneralVariables.perBandTuneLevels = serialized
        databaseOpr.writeConfig(PER_BAND_TUNE_LEVELS_KEY, serialized, null)
    }
}

/**
 * The level (0..100) the tune tone should play at right now, from live config.
 * Called per audio chunk, so a level change mid-tune takes effect immediately.
 */
fun currentTuneLevel(): Int = resolveTuneLevel(
    independent = GeneralVariables.tuneLevelIndependent,
    globalTuneLevel = GeneralVariables.tuneLevel,
    perBandEnabled = GeneralVariables.savePerBandOutputLevel,
    band = BaseRigOperation.getMeterFromFreq(GeneralVariables.band),
    serializedTuneLevels = GeneralVariables.perBandTuneLevels,
    txLevel = outputLevelFromVolumePercent(GeneralVariables.volumePercent),
)
