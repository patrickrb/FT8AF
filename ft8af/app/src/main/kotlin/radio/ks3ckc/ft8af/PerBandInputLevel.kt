package radio.ks3ckc.ft8af

import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.database.DatabaseOpr
import com.k1af.ft8af.rigs.BaseRigOperation

/**
 * Per-band RX input volume support (issue #927, the receive-side twin of #355).
 *
 * When "Save input volume per band" is enabled, the app remembers the RX
 * input gain (0-200 percent, same scale as the "inputVolume" config, 100 =
 * unity) separately for each band and restores it when the operator switches
 * bands. Bands with no saved value keep the current (global) input volume.
 *
 * Its own toggle and backing store, but the exact parse/serialize/resolve/record
 * helpers from [PerBandOutputLevel] with a 0..[PER_BAND_INPUT_LEVEL_MAX] range,
 * so the TX and RX maps never clobber each other.
 *
 * The decision logic is pure and unit-tested; the `*ForCurrentBand` /
 * `*ForBand` helpers are thin wiring around GeneralVariables/DatabaseOpr.
 */

/** Config key for the feature toggle ("1" = enabled, default off). */
const val PER_BAND_INPUT_LEVEL_KEY = "perBandInputLevel"

/** Config key for the serialized band-to-input-level map ("20m=100,10m=160"). */
const val PER_BAND_INPUT_LEVELS_KEY = "perBandInputLevels"

/**
 * Upper bound of a stored per-band input level, in percent. Matches the
 * Input Volume slider (INPUT_VOLUME_MAX) and InputAudioLevel.MAX_GAIN.
 */
const val PER_BAND_INPUT_LEVEL_MAX = 200

/**
 * Convert the RX gain multiplier (1.0 = 100%) to the 0-200 integer percent
 * used by the "inputVolume" config and the per-band map. Rounds rather than
 * truncating for the same float-error reason as [outputLevelFromVolumePercent].
 */
fun inputLevelFromGainPercent(gainPercent: Float): Int =
    Math.round(gainPercent * 100).coerceIn(0, PER_BAND_INPUT_LEVEL_MAX)

/** Serializes per-band input-map read-modify-writes. */
private val perBandInputLevelLock = Any()

/**
 * Record [level] (0-200) as the saved input volume for the current band and
 * persist the updated map. No-op when the feature is disabled or the value is
 * unchanged. Call wherever the input volume is adjusted.
 */
fun saveInputLevelForCurrentBand(databaseOpr: DatabaseOpr, level: Int) {
    val band = BaseRigOperation.getMeterFromFreq(GeneralVariables.band)
    saveInputLevelForBand(band, level) { serialized ->
        databaseOpr.writeConfig(PER_BAND_INPUT_LEVELS_KEY, serialized, null)
    }
}

/**
 * Synchronized read-modify-write of GeneralVariables.perBandInputLevels.
 * [persist] runs inside the lock, only when the map actually changed.
 */
internal fun saveInputLevelForBand(band: String?, level: Int, persist: (String) -> Unit) {
    synchronized(perBandInputLevelLock) {
        val serialized = recordPerBandOutputLevel(
            GeneralVariables.savePerBandInputLevel,
            band,
            level,
            GeneralVariables.perBandInputLevels,
            PER_BAND_INPUT_LEVEL_MAX,
        ) ?: return
        GeneralVariables.perBandInputLevels = serialized
        persist(serialized)
    }
}

/**
 * Look up the saved input volume for [band], or null when the current input
 * volume should be kept (feature off, no saved value, or already at that
 * level). Pure lookup — the caller applies/persists/toasts the result.
 */
fun restoredInputLevelForBand(band: String?): Int? = resolvePerBandOutputLevel(
    GeneralVariables.savePerBandInputLevel,
    band,
    GeneralVariables.perBandInputLevels,
    inputLevelFromGainPercent(GeneralVariables.inputGainPercent),
    PER_BAND_INPUT_LEVEL_MAX,
)

/**
 * Band-change restore: when [band] has a saved input volume that differs from
 * the current one, apply it to the live RX gain (used on the next audio
 * buffer), hand it to [persist] (the "inputVolume" config write) and return it
 * for the caller's toast. Returns null and changes nothing otherwise.
 */
internal fun applyRestoredInputLevelForBand(band: String?, persist: (Int) -> Unit): Int? {
    val level = restoredInputLevelForBand(band) ?: return null
    GeneralVariables.inputGainPercent = level / 100f
    persist(level)
    return level
}

/**
 * Whether closing the Input Volume dialog should record the level for the
 * current band. Only when the operator actually moved it: a band change the
 * rig makes on its own (VFO knob, band button) doesn't restore the per-band
 * level, so saving an untouched dialog would overwrite the new band's saved
 * value with the previous band's gain.
 */
internal fun inputLevelChangedInDialog(levelAtOpen: Int, levelAtDismiss: Int): Boolean =
    levelAtOpen != levelAtDismiss
