package radio.ks3ckc.ft8af.ui.map

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Per-station solar / gray-line status.
 *
 * HF propagation is briefly enhanced along the "gray line" — the moving band of
 * sunrise/sunset — so a DX operator who knows a distant station is *at* their
 * sunrise/sunset (or how long until they are) can time a call for the best shot.
 * The map already draws the terminator; this file answers the same question for
 * a single point (the station's grid): are they in daylight, night, or on the
 * gray line right now, and when is their next sunrise/sunset?
 *
 * Everything here is a pure function of (lat, lon, UTC) built on the same
 * [subsolarPoint] / [solarElevationDeg] math as the map overlay, so the QSO
 * sheet stays a thin wrapper and the logic is unit-tested directly.
 */

/** Which way the Sun is about to cross the horizon at a point. */
internal enum class SolarEventKind { SUNRISE, SUNSET }

/**
 * A point's solar situation at an instant.
 *
 * @param elevationDeg the Sun's angle above the horizon (negative = below).
 * @param isDay whether the Sun is above the horizon.
 * @param onGrayLine whether the Sun is within the gray-line band of the horizon.
 * @param nextKind the next horizon crossing, or null in polar day/night where no
 *   crossing occurs within the scan window.
 * @param minutesToNext whole minutes until [nextKind]; 0 when [nextKind] is null.
 */
internal data class SolarSnapshot(
    val elevationDeg: Double,
    val isDay: Boolean,
    val onGrayLine: Boolean,
    val nextKind: SolarEventKind?,
    val minutesToNext: Int,
)

/** Half-width (degrees) of the gray-line band on either side of the horizon. */
internal const val GRAY_LINE_HALF_WIDTH_DEG: Double = 6.0

/** Step used when scanning forward for the next sunrise/sunset. */
private const val EVENT_SCAN_STEP_MS: Long = 2L * 60L * 1000L

/** How far ahead to scan; a little over a day guarantees at least one crossing
 *  outside the polar regions. */
private const val EVENT_SCAN_SPAN_MS: Long = 26L * 60L * 60L * 1000L

/**
 * The solar situation at [lat]/[lon] for the UTC instant [utcMillis].
 *
 * "Day" is elevation ≥ 0; the gray line is |elevation| ≤ [grayLineHalfWidthDeg].
 * The next crossing is found by scanning forward in [EVENT_SCAN_STEP_MS] steps
 * and linearly interpolating the exact horizon crossing between the two
 * bracketing samples — accurate to well under a minute, which is plenty for an
 * operating aid. Near the poles a full day can pass with the Sun never crossing
 * the horizon (midnight sun / polar night); there [nextKind] is null.
 */
internal fun solarSnapshot(
    lat: Double,
    lon: Double,
    utcMillis: Long,
    grayLineHalfWidthDeg: Double = GRAY_LINE_HALF_WIDTH_DEG,
): SolarSnapshot {
    val elevNow = solarElevationDeg(lat, lon, subsolarPoint(utcMillis))

    var prevElev = elevNow
    var t = utcMillis + EVENT_SCAN_STEP_MS
    val end = utcMillis + EVENT_SCAN_SPAN_MS
    var nextKind: SolarEventKind? = null
    var crossingMs = 0L
    while (t <= end) {
        val elev = solarElevationDeg(lat, lon, subsolarPoint(t))
        // A crossing happens when consecutive samples straddle the horizon.
        if (prevElev < 0.0 && elev >= 0.0) {
            nextKind = SolarEventKind.SUNRISE
        } else if (prevElev >= 0.0 && elev < 0.0) {
            nextKind = SolarEventKind.SUNSET
        }
        if (nextKind != null) {
            // Linear interpolation of the exact zero crossing within this step.
            val span = elev - prevElev
            val frac = if (span == 0.0) 0.0 else (0.0 - prevElev) / span
            val prevT = t - EVENT_SCAN_STEP_MS
            crossingMs = prevT + (frac * EVENT_SCAN_STEP_MS).roundToLong()
            break
        }
        prevElev = elev
        t += EVENT_SCAN_STEP_MS
    }

    val minutesToNext = if (nextKind == null) {
        0
    } else {
        ((crossingMs - utcMillis).coerceAtLeast(0L) / 60000L).toInt()
    }

    return SolarSnapshot(
        elevationDeg = elevNow,
        isDay = elevNow >= 0.0,
        onGrayLine = abs(elevNow) <= grayLineHalfWidthDeg,
        nextKind = nextKind,
        minutesToNext = minutesToNext,
    )
}

/** The three visual phases of the gray-line hint. */
internal enum class GrayLinePhase { GRAY_LINE, DAYLIGHT, NIGHT }

/** The secondary detail: the next event, or a polar no-event state. */
internal enum class GrayLineDetail { SUNRISE, SUNSET, MIDNIGHT_SUN, POLAR_NIGHT }

/**
 * What the gray-line hint should say, as resource-free tokens plus a preformatted
 * [countdown]. The QSO-sheet Composable maps [phase]/[detail] to localized strings
 * (so wording stays translatable) while this decision stays pure and unit-tested.
 * [countdown] is empty for the polar no-event details.
 */
internal data class GrayLineDisplay(
    val phase: GrayLinePhase,
    val detail: GrayLineDetail,
    val countdown: String,
)

/** Resolve a [SolarSnapshot] into the tokens the gray-line hint should display. */
internal fun grayLineDisplay(s: SolarSnapshot): GrayLineDisplay {
    val phase = when {
        s.onGrayLine -> GrayLinePhase.GRAY_LINE
        s.isDay -> GrayLinePhase.DAYLIGHT
        else -> GrayLinePhase.NIGHT
    }
    return when (s.nextKind) {
        SolarEventKind.SUNRISE ->
            GrayLineDisplay(phase, GrayLineDetail.SUNRISE, formatSolarCountdown(s.minutesToNext))
        SolarEventKind.SUNSET ->
            GrayLineDisplay(phase, GrayLineDetail.SUNSET, formatSolarCountdown(s.minutesToNext))
        null ->
            GrayLineDisplay(
                phase,
                if (s.isDay) GrayLineDetail.MIDNIGHT_SUN else GrayLineDetail.POLAR_NIGHT,
                "",
            )
    }
}

/**
 * Format a whole-minute countdown as a compact "1h 20m" / "45m" / "now" string.
 * Used for the "sunset in …" / "sunrise in …" gray-line hint. Kept pure (no
 * Android resources) so it is unit-tested directly; the surrounding "sunset
 * in {x}" wording is supplied by the caller via string resources.
 */
internal fun formatSolarCountdown(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    if (m < 1) return "now"
    val hours = m / 60
    val mins = m % 60
    return when {
        hours <= 0 -> "${mins}m"
        mins == 0 -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}
