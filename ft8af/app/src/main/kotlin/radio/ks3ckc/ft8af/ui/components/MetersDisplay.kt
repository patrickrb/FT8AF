package radio.ks3ckc.ft8af.ui.components

/**
 * Pure decision/geometry logic for the meters HUD, extracted so it can be unit
 * tested without Compose. The [MetersSheet] composable is a thin wrapper that
 * maps these results to bars, colors, and labels.
 *
 * Meter values are the normalized 0-255 scale that every CAT rig's meter path
 * reports (see MeterProtectionController). The rig only reports ALC/SWR while
 * keyed, so these values are live during TX and hold the last TX's reading
 * afterwards — [meterFreshness] captures that distinction.
 */

/**
 * Visual zone for a meter reading, mapped to a concrete color in the UI layer.
 * NEUTRAL is for purely informational meters (power, S-meter) that have no
 * good/bad judgement — they're shown in an accent color, not green/red.
 */
enum class MeterZone { IDLE, NEUTRAL, GOOD, CAUTION, DANGER }

/**
 * The meters the HUD can show. Ordinal order is the display order. Not every rig
 * reports every meter — the HUD adapts per rig (see the source adapters), and
 * [visibleMeters] intersects what's available with what the user enabled.
 */
enum class MeterType { SWR, ALC, POWER, SMETER, VOLTAGE, TEMP }

/**
 * One meter ready to render: a 0..1 bar [fraction], a formatted [text] readout,
 * and a [zone] for coloring. Built by the pure converters below from each rig's
 * native units, so the HUD itself stays source-agnostic.
 */
data class MeterSample(
    val type: MeterType,
    val fraction: Float,
    val text: String,
    val zone: MeterZone,
)

/** Whether a displayed value is live (TX in progress), held from the last TX, or absent. */
enum class MeterFreshness { LIVE, LAST_TX, NONE }

internal const val METER_MAX = 255

/** Fraction 0f..1f of a full-scale bar for a normalized 0..255 meter value. */
internal fun meterBarFraction(normalized: Int): Float = normalized.coerceIn(0, METER_MAX) / METER_MAX.toFloat()

/** ALC as a 0..100 percent for the readout. */
internal fun alcPercent(normalized: Int): Int = Math.round(meterBarFraction(normalized) * 100f)

/**
 * ALC zone. Below the target window the rig is under-driven (CAUTION — there is
 * headroom to push harder); inside the window is ideal (GOOD); above it the
 * stage is overdriven and the signal distorts (DANGER). A non-positive reading
 * means no drive at all (IDLE). The window matches the auto-volume controller's
 * target so the HUD and the protection logic agree on what "good" means.
 */
internal fun alcZone(
    normalized: Int,
    targetLow: Int,
    targetHigh: Int,
): MeterZone {
    if (normalized <= 0) return MeterZone.IDLE
    return when {
        normalized > targetHigh -> MeterZone.DANGER
        normalized < targetLow -> MeterZone.CAUTION
        else -> MeterZone.GOOD
    }
}

/**
 * SWR zone relative to the user's halt threshold. Comfortable below ~60% of the
 * threshold (GOOD), rising caution up to it (CAUTION), and danger at/above it —
 * the same point [com.k1af.ft8af.ft8transmit.MeterProtectionController] halts TX
 * (DANGER). A non-positive reading (≈1.0:1, a flat match) is IDLE. A zero/invalid
 * threshold can't define danger, so anything reads GOOD rather than false-alarm.
 */
internal fun swrZone(
    normalized: Int,
    haltThreshold: Int,
): MeterZone {
    if (normalized <= 0) return MeterZone.IDLE
    if (haltThreshold <= 0) return MeterZone.GOOD
    val frac = normalized.toFloat() / haltThreshold
    return when {
        frac >= 1.0f -> MeterZone.DANGER
        frac >= 0.6f -> MeterZone.CAUTION
        else -> MeterZone.GOOD
    }
}

/**
 * Live while transmitting and the rig is reporting meters; otherwise the shown
 * value is the last TX's reading if we have ever received meter data, else the
 * rig has reported nothing (unsupported rig, or no TX yet). Distinguishing
 * "never reported" from a legitimate 0 reading needs [hasData] — a normalized 0
 * is a valid value (ALC at no drive, SWR at 1.0:1), not "no data".
 */
internal fun meterFreshness(
    isTransmitting: Boolean,
    hasData: Boolean,
): MeterFreshness =
    when {
        isTransmitting && hasData -> MeterFreshness.LIVE
        hasData -> MeterFreshness.LAST_TX
        else -> MeterFreshness.NONE
    }

/**
 * Whether a top-edge drag should open the HUD: a downward drag ([totalDy] > 0 in
 * Compose's y-down coordinates) that has accumulated past [thresholdPx]. Pulled
 * out so the open gesture's commit rule is testable independent of pointer
 * plumbing.
 */
internal fun shouldOpenFromEdgeDrag(
    totalDy: Float,
    thresholdPx: Float,
): Boolean = totalDy >= thresholdPx

// ---------------------------------------------------------------------------
// Per-source meter converters → MeterSample. Each takes a rig's native units and
// produces a common bar fraction + readout + zone, so the HUD renders uniformly
// across serial / Flex / Xiegu sources. All pure and unit-tested.
// ---------------------------------------------------------------------------

/** Bar reaches full at a 3:1 reference; readout shows "∞" above ~9.5:1. */
internal fun swrSampleFromRatio(ratio: Float): MeterSample {
    val r = if (ratio < 1f) 1f else ratio
    val frac = ((r - 1f) / (3f - 1f)).coerceIn(0f, 1f)
    val zone =
        when {
            r < 1.5f -> MeterZone.GOOD
            r < 3.0f -> MeterZone.CAUTION
            else -> MeterZone.DANGER
        }
    val text = if (r >= 9.5f) "∞" else String.format(java.util.Locale.US, "%.1f:1", r)
    return MeterSample(MeterType.SWR, frac, text, zone)
}

/**
 * Serial rigs report SWR as a normalized 0-255 value; convert to a ratio (the
 * numeric inverse of MeterProtectionController.swrRatioToNormalized, matching its
 * piecewise breakpoints) and reuse [swrSampleFromRatio].
 */
internal fun swrRatioFromNormalized(normalized: Int): Float =
    when {
        normalized <= 0 -> 1.0f
        normalized >= 255 -> 10.0f
        normalized <= 60 -> 1.0f + normalized / 60f * 0.5f
        normalized <= 120 -> 1.5f + (normalized - 60) / 60f * 1.5f
        normalized <= 200 -> 3.0f + (normalized - 120) / 80f * 4.0f
        else -> 7.0f + (normalized - 200) / 55f * 3.0f
    }

internal fun swrSampleFromNormalized(normalized: Int): MeterSample = swrSampleFromRatio(swrRatioFromNormalized(normalized))

/** Serial ALC: normalized 0-255 with the user's target window (low is under-driven). */
internal fun alcSampleSerial(
    normalized: Int,
    targetLow: Int,
    targetHigh: Int,
): MeterSample =
    MeterSample(
        MeterType.ALC,
        meterBarFraction(normalized),
        "${alcPercent(normalized)}%",
        alcZone(normalized, targetLow, targetHigh),
    )

/**
 * Flex ALC is a dB reading (~ -150..+20); unlike the serial scale, LOW is good
 * (no ALC compression) and climbing toward/past 0 dB means overdrive. The bar
 * fills as drive rises so a pegging meter reads as a full red bar.
 */
internal fun alcSampleFlexDb(db: Float): MeterSample {
    val frac = ((db + 150f) / 170f).coerceIn(0f, 1f)
    val zone =
        when {
            db <= 0f -> MeterZone.GOOD
            db <= 10f -> MeterZone.CAUTION
            else -> MeterZone.DANGER
        }
    return MeterSample(MeterType.ALC, frac, "${Math.round(db)} dB", zone)
}

/** Xiegu ALC arrives as 0..120 (higher = more drive). */
internal fun alcSampleXiegu(alc: Float): MeterSample {
    val v = alc.coerceIn(0f, 120f)
    val frac = v / 120f
    val zone =
        when {
            v >= 80f -> MeterZone.DANGER
            v >= 40f -> MeterZone.CAUTION
            else -> MeterZone.GOOD
        }
    return MeterSample(MeterType.ALC, frac, "${Math.round(v)}", zone)
}

/** Output power in watts; informational (NEUTRAL), bar relative to [maxWatts]. */
internal fun powerSample(
    watts: Float,
    maxWatts: Float = 100f,
): MeterSample {
    val frac = if (maxWatts <= 0f) 0f else (watts / maxWatts).coerceIn(0f, 1f)
    return MeterSample(MeterType.POWER, frac, "${Math.round(watts)} W", MeterZone.NEUTRAL)
}

/** Receive signal strength in dBm; informational, bar spans roughly -120..-30 dBm. */
internal fun sMeterSampleDbm(dbm: Float): MeterSample {
    val frac = ((dbm + 120f) / 90f).coerceIn(0f, 1f)
    return MeterSample(MeterType.SMETER, frac, "${Math.round(dbm)} dBm", MeterZone.NEUTRAL)
}

/** Supply voltage; green in the normal 12V battery band, red at the extremes. */
internal fun voltSample(volts: Float): MeterSample {
    val frac = (volts / 16f).coerceIn(0f, 1f)
    val zone =
        when {
            volts < 10f || volts > 15.5f -> MeterZone.DANGER
            volts < 11.5f || volts > 14.8f -> MeterZone.CAUTION
            else -> MeterZone.GOOD
        }
    return MeterSample(MeterType.VOLTAGE, frac, String.format(java.util.Locale.US, "%.1f V", volts), zone)
}

/** PA temperature in °C; caution past 55°C, danger past 70°C. */
internal fun tempSample(celsius: Float): MeterSample {
    val frac = (celsius / 100f).coerceIn(0f, 1f)
    val zone =
        when {
            celsius >= 70f -> MeterZone.DANGER
            celsius >= 55f -> MeterZone.CAUTION
            else -> MeterZone.GOOD
        }
    return MeterSample(MeterType.TEMP, frac, "${Math.round(celsius)}°C", zone)
}

/** The set of meter types the user has enabled in settings (display order preserved). */
internal fun enabledMeterTypes(
    swr: Boolean,
    alc: Boolean,
    power: Boolean,
    smeter: Boolean,
    voltage: Boolean,
    temp: Boolean,
): Set<MeterType> {
    val set = linkedSetOf<MeterType>()
    if (swr) set.add(MeterType.SWR)
    if (alc) set.add(MeterType.ALC)
    if (power) set.add(MeterType.POWER)
    if (smeter) set.add(MeterType.SMETER)
    if (voltage) set.add(MeterType.VOLTAGE)
    if (temp) set.add(MeterType.TEMP)
    return set
}

/**
 * The meters to actually render: those the rig reports ([available]) intersected
 * with those the user enabled ([enabled]), in display (ordinal) order. This is
 * the "adapt per rig" rule — an enabled meter the rig doesn't report is dropped
 * rather than shown empty.
 */
internal fun visibleMeters(
    available: List<MeterSample>,
    enabled: Set<MeterType>,
): List<MeterSample> = available.filter { it.type in enabled }.sortedBy { it.type.ordinal }

/** Max settable TX power; the HUD slider uses a 0..[MAX_TX_POWER_WATTS] W range. */
const val MAX_TX_POWER_WATTS: Int = 100

/** Clamp a requested TX power to the valid 0..[MAX_TX_POWER_WATTS] W range. */
internal fun clampTxPowerWatts(watts: Int): Int = watts.coerceIn(0, MAX_TX_POWER_WATTS)
