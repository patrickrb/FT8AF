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

/** A framed view of the equirectangular map: zoom + pan (canvas pixels). */
internal data class MapViewFit(val scale: Float, val panX: Float, val panY: Float)

/**
 * Frame the equirectangular map so the given lat/lon [points] fill the canvas with
 * a [padFrac] margin — the map zooms into the region that actually has markers
 * instead of showing a mostly-empty world. Returns null when there is nothing to
 * frame or the canvas hasn't been measured yet. Pure: it mirrors
 * [EquirectViewport]'s COVER projection so the result lines up with the canvas.
 */
internal fun fitEquirectView(
    points: List<Pair<Double, Double>>,
    canvasW: Float,
    canvasH: Float,
    minZoom: Float,
    maxZoom: Float,
    padFrac: Float = 0.14f,
): MapViewFit? {
    if (points.isEmpty() || canvasW <= 0f || canvasH <= 0f) return null

    var nxMin = Float.MAX_VALUE
    var nxMax = -Float.MAX_VALUE
    var nyMin = Float.MAX_VALUE
    var nyMax = -Float.MAX_VALUE
    for ((lat, lon) in points) {
        val nx = (lon / 180.0).toFloat()
        val ny = (-lat / 90.0).toFloat()
        if (nx < nxMin) nxMin = nx
        if (nx > nxMax) nxMax = nx
        if (ny < nyMin) nyMin = ny
        if (ny > nyMax) nyMax = ny
    }
    // Floor the span so a single station (or a tight cluster) frames a region, not
    // a pinpoint that would slam to max zoom.
    val minSpan = 0.22f
    val spanX = maxOf(nxMax - nxMin, minSpan)
    val spanY = maxOf(nyMax - nyMin, minSpan)
    val cx = (nxMin + nxMax) / 2f
    val cy = (nyMin + nyMax) / 2f

    val baseUniform = maxOf(canvasW / 2f, canvasH).coerceAtLeast(1f)
    val usableW = canvasW * (1f - 2f * padFrac)
    val usableH = canvasH * (1f - 2f * padFrac)
    // Pixels per normalized unit at scale s: x -> baseUniform*s, y -> baseUniform*s/2.
    val sX = usableW / (spanX * baseUniform)
    val sY = usableH / (spanY * (baseUniform / 2f))
    val scale = minOf(sX, sY).coerceIn(minZoom, maxZoom)

    val vp = EquirectViewport(canvasW, canvasH, scale)
    val pan = vp.clampPan(-cx * (vp.worldPxW / 2f), -cy * (vp.worldPxH / 2f))
    return MapViewFit(scale, pan.x, pan.y)
}
