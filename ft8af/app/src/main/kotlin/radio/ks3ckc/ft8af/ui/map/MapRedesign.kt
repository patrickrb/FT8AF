package radio.ks3ckc.ft8af.ui.map

/**
 * Pure, unit-testable logic for the redesigned Map tab (concept 4c): the layer
 * chips (Decodes / PSK spots / Worked), the header counts, and the gray-line
 * info pill. The Composable in [MapScreen] stays a thin wrapper over these.
 */

/**
 * Whether a station marker is drawn given the active layer chips. Worked stations
 * belong to the "Worked" layer; every other decode belongs to the "Decodes"
 * layer — so the two chips partition the markers with no double-draw.
 */
internal fun isStationVisible(isWorked: Boolean, decodesOn: Boolean, workedOn: Boolean): Boolean =
    if (isWorked) workedOn else decodesOn

/**
 * Minutes until the operator at [opLat]/[opLon] next crosses the gray line (the
 * day/night terminator), scanning forward minute-by-minute from [nowMillis].
 * Returns null when no crossing falls within [maxHours] (e.g. polar day/night).
 */
internal fun minutesToGrayLine(
    opLat: Double,
    opLon: Double,
    nowMillis: Long,
    maxHours: Int = 24,
): Long? {
    val startNight = isNight(opLat, opLon, subsolarPoint(nowMillis))
    val end = nowMillis + maxHours.toLong() * 3_600_000L
    var t = nowMillis
    while (t < end) {
        t += 60_000L
        if (isNight(opLat, opLon, subsolarPoint(t)) != startNight) {
            return (t - nowMillis) / 60_000L
        }
    }
    return null
}

/** Compact "1h 12m" / "12m" formatting for a minute count. */
internal fun formatHoursMinutes(totalMin: Long): String {
    val m = totalMin.coerceAtLeast(0)
    val h = m / 60
    val rem = m % 60
    return if (h > 0) "${h}h ${rem}m" else "${rem}m"
}
