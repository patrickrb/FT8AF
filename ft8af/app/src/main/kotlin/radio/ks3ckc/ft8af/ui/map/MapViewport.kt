package radio.ks3ckc.ft8af.ui.map

import androidx.compose.ui.geometry.Offset

/**
 * How the equirectangular world is scaled into the canvas at zoom 1.0.
 *
 * COVER fills the whole canvas (the map overflows on whichever axis the canvas
 * is "too long" in, so there are never dead bands) — used for the full-screen
 * map. FIT letterboxes the map inside the canvas (kept for completeness/tests).
 */
internal enum class FitMode { COVER, FIT }

/**
 * Maps the pure [equirectProject] output (normalized [-1,1] on each axis) onto a
 * concrete canvas, edge-to-edge, with a user pinch-zoom and pan offset.
 *
 * The world's intrinsic aspect is 2:1 (360° lon by 180° lat). [baseUniform] is
 * the world's full height in pixels at zoom 1, so the world is `2*baseUniform`
 * wide by `baseUniform` tall (before [userScale]) — i.e. x is scaled to twice as
 * many pixels per normalized unit as y, which preserves the 2:1 aspect. COVER
 * picks `max(canvasW/2, canvasH)` so the world is at least as large as the canvas
 * on both axes (no dead bands).
 *
 * Previously the canvas was locked to a 2:1 box via `.aspectRatio(2f)` and the
 * projection assumed the unzoomed map exactly filled it (`pan max = size*(scale-1)/2`).
 * That left dead bands on a tall phone. COVER + [clampPan] (which floors the
 * allowed pan at 0 when the map is *smaller* than the canvas on an axis) is what
 * lets the map fill the screen without being draggable into empty space.
 */
internal class EquirectViewport(
    val canvasW: Float,
    val canvasH: Float,
    val userScale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val fit: FitMode = FitMode.COVER,
) {
    // COVER: worldPxW >= canvasW (=> baseUniform >= canvasW/2) AND
    //        worldPxH >= canvasH (=> baseUniform >= canvasH). Take the max so both hold.
    // FIT:   the opposite — the smaller scale, so the whole world fits inside.
    val baseUniform: Float = when (fit) {
        FitMode.COVER -> maxOf(canvasW / 2f, canvasH)
        FitMode.FIT -> minOf(canvasW / 2f, canvasH)
    }.coerceAtLeast(1f)

    val worldPxW: Float = baseUniform * 2f * userScale
    val worldPxH: Float = baseUniform * 1f * userScale

    /** Project a normalized point (from [equirectProject]) to canvas pixels. */
    fun project(p: ProjectedPoint): Offset {
        val cx = canvasW / 2f
        val cy = canvasH / 2f
        return Offset(
            x = cx + p.x * (worldPxW / 2f) + panX,
            y = cy + p.y * (worldPxH / 2f) + panY,
        )
    }

    /** Convenience: project geographic lat/lon straight to canvas pixels. */
    fun projectLatLon(lat: Double, lon: Double): Offset = project(equirectProject(lat, lon))

    /** Max pan on each axis = half the overflow of the (zoomed) world past the canvas, floored at 0. */
    fun maxPanX(): Float = ((worldPxW - canvasW) / 2f).coerceAtLeast(0f)
    fun maxPanY(): Float = ((worldPxH - canvasH) / 2f).coerceAtLeast(0f)

    /**
     * Clamp a candidate pan so the map can't be dragged off into empty space.
     * On an axis where the map is smaller than the canvas the allowed pan is 0
     * (the map stays centered) — this is the load-bearing fix vs. the old
     * `size*(scale-1)/2` formula, which silently assumed the map always filled
     * the canvas.
     */
    fun clampPan(px: Float, py: Float): Offset {
        val mx = maxPanX()
        val my = maxPanY()
        return Offset(px.coerceIn(-mx, mx), py.coerceIn(-my, my))
    }
}
