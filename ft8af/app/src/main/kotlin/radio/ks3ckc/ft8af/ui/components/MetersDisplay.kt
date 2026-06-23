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

/** Visual zone for a meter reading, mapped to a concrete color in the UI layer. */
enum class MeterZone { IDLE, GOOD, CAUTION, DANGER }

/** Whether a displayed value is live (TX in progress), held from the last TX, or absent. */
enum class MeterFreshness { LIVE, LAST_TX, NONE }

internal const val METER_MAX = 255

/** Fraction 0f..1f of a full-scale bar for a normalized 0..255 meter value. */
internal fun meterBarFraction(normalized: Int): Float =
    normalized.coerceIn(0, METER_MAX) / METER_MAX.toFloat()

/** ALC as a 0..100 percent for the readout. */
internal fun alcPercent(normalized: Int): Int =
    Math.round(meterBarFraction(normalized) * 100f)

/**
 * ALC zone. Below the target window the rig is under-driven (CAUTION — there is
 * headroom to push harder); inside the window is ideal (GOOD); above it the
 * stage is overdriven and the signal distorts (DANGER). A non-positive reading
 * means no drive at all (IDLE). The window matches the auto-volume controller's
 * target so the HUD and the protection logic agree on what "good" means.
 */
internal fun alcZone(normalized: Int, targetLow: Int, targetHigh: Int): MeterZone {
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
internal fun swrZone(normalized: Int, haltThreshold: Int): MeterZone {
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
internal fun meterFreshness(isTransmitting: Boolean, hasData: Boolean): MeterFreshness = when {
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
internal fun shouldOpenFromEdgeDrag(totalDy: Float, thresholdPx: Float): Boolean =
    totalDy >= thresholdPx
