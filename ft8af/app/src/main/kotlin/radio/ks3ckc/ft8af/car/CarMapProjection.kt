package radio.ks3ckc.ft8af.car

/**
 * Maps geographic lon/lat to surface pixels for the car map, with an optional
 * zoom that frames a set of points (the operator and their QSO partner) close up.
 *
 * The base projection is plain equirectangular over the whole globe — identical to
 * the legacy fixed [world] view. A [fit] view additionally scales and re-centres
 * around the midpoint of the supplied points so they sit inside a content box with
 * padding, letting the driver actually see the operator→partner connection instead
 * of two specks on a world map.
 *
 * Pure geometry with no Android dependencies so it can be unit-tested directly; the
 * renderer owns all Canvas/Surface work.
 */
internal class CarMapProjection(
    private val contentCenterX: Float,
    private val contentCenterY: Float,
    private val midBaseX: Float,
    private val midBaseY: Float,
    private val zoom: Float,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
) {
    /** Longitude (degrees) → surface x (pixels). */
    fun x(lon: Float): Float = contentCenterX + (baseX(lon, surfaceWidth) - midBaseX) * zoom

    /** Latitude (degrees) → surface y (pixels). */
    fun y(lat: Float): Float = contentCenterY + (baseY(lat, surfaceHeight) - midBaseY) * zoom

    /** Zoom multiplier over the world view (1 = whole globe). */
    val zoomLevel: Float get() = zoom

    /**
     * Identity key for the projection. Land outline paths are expensive to build,
     * so the renderer caches them and only rebuilds when this changes.
     */
    val signature: String
        get() = "$contentCenterX|$contentCenterY|$midBaseX|$midBaseY|$zoom|$surfaceWidth|$surfaceHeight"

    companion object {
        /** Never zoom in past this, so two stations in the same grid stay sane. */
        const val MAX_ZOOM = 12f

        /** Fraction of the content box kept clear around the fitted points. */
        const val PADDING_FRACTION = 0.28f

        /** Floor on the fitted span (px) so coincident points don't divide by zero. */
        const val MIN_SPAN_PX = 1f

        /** Base equirectangular projection (whole world fills the surface). */
        fun baseX(lon: Float, width: Int): Float = (lon / 180f) * (width / 2f) + (width / 2f)
        fun baseY(lat: Float, height: Int): Float = (-lat / 90f) * (height / 2f) + (height / 2f)

        /** Full-world view, centred on the surface — matches the legacy fixed projection. */
        fun world(width: Int, height: Int): CarMapProjection =
            CarMapProjection(width / 2f, height / 2f, width / 2f, height / 2f, 1f, width, height)

        /**
         * A view zoomed + re-centred so every point in [points] (each `lat` to `lon`,
         * degrees) fits inside the content box — centre ([cx], [cy]), size
         * [contentW]×[contentH] px — with [PADDING_FRACTION] breathing room. The zoom
         * is clamped to `[1, MAX_ZOOM]`: it never zooms *out* past the world view, and
         * never in past [MAX_ZOOM]. Degenerate inputs fall back to [world].
         */
        fun fit(
            width: Int,
            height: Int,
            cx: Float,
            cy: Float,
            contentW: Float,
            contentH: Float,
            points: List<Pair<Double, Double>>,
        ): CarMapProjection {
            if (width <= 0 || height <= 0 || contentW <= 0f || contentH <= 0f || points.isEmpty()) {
                return world(width, height)
            }
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for ((lat, lon) in points) {
                val bx = baseX(lon.toFloat(), width)
                val by = baseY(lat.toFloat(), height)
                minX = minOf(minX, bx)
                maxX = maxOf(maxX, bx)
                minY = minOf(minY, by)
                maxY = maxOf(maxY, by)
            }
            val spanX = maxOf(maxX - minX, MIN_SPAN_PX)
            val spanY = maxOf(maxY - minY, MIN_SPAN_PX)
            val availW = contentW * (1f - PADDING_FRACTION)
            val availH = contentH * (1f - PADDING_FRACTION)
            val zoom = minOf(availW / spanX, availH / spanY).coerceIn(1f, MAX_ZOOM)
            return CarMapProjection(
                contentCenterX = cx,
                contentCenterY = cy,
                midBaseX = (minX + maxX) / 2f,
                midBaseY = (minY + maxY) / 2f,
                zoom = zoom,
                surfaceWidth = width,
                surfaceHeight = height,
            )
        }
    }
}
