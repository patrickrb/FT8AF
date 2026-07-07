package radio.ks3ckc.ft8af.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Surface

/**
 * Draws the car screen contents directly onto an Android Auto [Surface]:
 * land outlines → station markers → operator dot → semi-transparent overlay →
 * status text → TX indicator dot. All drawing is on the caller's thread
 * (main thread from the 1 Hz tick); the surface lock serialises access.
 *
 * Because this bypasses the host's template system, visual updates (e.g. the
 * slot countdown) no longer trigger `invalidate()` → `onGetTemplate()`, which
 * eliminates the dimming/flashing caused by full template rebuilds.
 */
internal class CarMapSurfaceRenderer {

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var stableArea: Rect? = null

    /** Cached land outline paths — rebuilt only when surface size changes. */
    private var landPaths: List<Path>? = null
    private var landPathsWidth = 0
    private var landPathsHeight = 0

    // -- Paints (allocated once, mutated per-frame only for color/size) --

    private val bgPaint = Paint().apply { color = BG_COLOR; style = Paint.Style.FILL }
    private val landFillPaint = Paint().apply { color = LAND_FILL; style = Paint.Style.FILL; isAntiAlias = true }
    private val landStrokePaint = Paint().apply {
        color = LAND_STROKE; style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true
    }
    private val overlayPaint = Paint().apply { color = OVERLAY_COLOR; style = Paint.Style.FILL }
    private val markerPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE }
    private val indicatorPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    // -- Surface lifecycle --

    @Synchronized
    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        this.surface = surface
        this.surfaceWidth = width
        this.surfaceHeight = height
    }

    @Synchronized
    fun onSurfaceDestroyed() {
        surface = null
        landPaths = null
    }

    @Synchronized
    fun onVisibleAreaChanged(@Suppress("UNUSED_PARAMETER") visibleArea: Rect) {
        // We only use stableArea for text layout; visibleArea is noted but unused.
    }

    @Synchronized
    fun onStableAreaChanged(stable: Rect) {
        stableArea = stable
    }

    // -- Frame drawing --

    /**
     * Draws a complete frame for [state] onto the surface. Safe to call from any
     * thread; returns silently if the surface is not available.
     *
     * @param landRings  Pre-parsed GeoJSON land outline rings (lon/lat interleaved
     *                   FloatArrays) from [WorldOutlines.load]. Passed in so the
     *                   renderer doesn't need a Context.
     */
    @Synchronized
    fun drawFrame(state: CarSurfaceState, landRings: List<FloatArray>) {
        val surf = surface ?: return
        if (!surf.isValid) return
        val canvas: Canvas = try {
            surf.lockCanvas(null) ?: return
        } catch (_: IllegalArgumentException) {
            return
        }
        try {
            drawBackground(canvas)
            val paths = getOrBuildLandPaths(landRings)
            drawLand(canvas, paths)
            drawStationMarkers(canvas, state.stations)
            drawOperatorDot(canvas, state.opLat, state.opLon)
            drawOverlay(canvas)
            drawStatusText(canvas, state)
            drawTxIndicator(canvas, state.txState)
        } finally {
            surf.unlockCanvasAndPost(canvas)
        }
    }

    // -- Layer 1: background fill --

    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(BG_COLOR)
    }

    // -- Layer 2: land outlines --

    private fun getOrBuildLandPaths(rings: List<FloatArray>): List<Path> {
        landPaths?.let {
            if (landPathsWidth == surfaceWidth && landPathsHeight == surfaceHeight) return it
        }
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()
        val built = rings.map { ring ->
            Path().apply {
                for (i in 0 until ring.size / 2) {
                    val lon = ring[i * 2]
                    val lat = ring[i * 2 + 1]
                    val px = (lon / 180f) * (w / 2f) + (w / 2f)
                    val py = (-lat / 90f) * (h / 2f) + (h / 2f)
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
        }
        landPaths = built
        landPathsWidth = surfaceWidth
        landPathsHeight = surfaceHeight
        return built
    }

    private fun drawLand(canvas: Canvas, paths: List<Path>) {
        for (p in paths) {
            canvas.drawPath(p, landFillPaint)
            canvas.drawPath(p, landStrokePaint)
        }
    }

    // -- Layer 3: station markers --

    private fun drawStationMarkers(canvas: Canvas, stations: List<CarStationMarker>) {
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()
        for (s in stations) {
            val px = (s.lon.toFloat() / 180f) * (w / 2f) + (w / 2f)
            val py = (-s.lat.toFloat() / 90f) * (h / 2f) + (h / 2f)
            markerPaint.color = s.colorInt
            canvas.drawCircle(px, py, STATION_RADIUS, markerPaint)
        }
    }

    // -- Layer 4: operator dot --

    private fun drawOperatorDot(canvas: Canvas, lat: Double, lon: Double) {
        if (lat.isNaN() || lon.isNaN()) return
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()
        val px = (lon.toFloat() / 180f) * (w / 2f) + (w / 2f)
        val py = (-lat.toFloat() / 90f) * (h / 2f) + (h / 2f)
        markerPaint.color = ACCENT_COLOR
        canvas.drawCircle(px, py, OPERATOR_RADIUS, markerPaint)
    }

    // -- Layer 5: semi-transparent overlay --

    private fun drawOverlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), overlayPaint)
    }

    // -- Layer 6: status text --

    private fun drawStatusText(canvas: Canvas, state: CarSurfaceState) {
        val area = stableArea ?: Rect(
            (surfaceWidth * 0.05f).toInt(),
            (surfaceHeight * 0.05f).toInt(),
            (surfaceWidth * 0.95f).toInt(),
            (surfaceHeight * 0.95f).toInt(),
        )
        var y = area.top.toFloat() + HEADLINE_SIZE + TEXT_PADDING

        // Headline (24sp equivalent at screen density)
        textPaint.color = Color.WHITE
        textPaint.textSize = HEADLINE_SIZE
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(state.headlineText, area.left.toFloat(), y, textPaint)
        y += HEADLINE_SIZE + TEXT_PADDING

        // TX message
        if (state.txMessageText != null) {
            textPaint.color = ACCENT_COLOR
            textPaint.textSize = TX_MSG_SIZE
            textPaint.typeface = Typeface.MONOSPACE
            canvas.drawText(state.txMessageText, area.left.toFloat(), y, textPaint)
            y += TX_MSG_SIZE + TEXT_PADDING
        }

        // Sequence step
        textPaint.color = Color.WHITE
        textPaint.textSize = DETAIL_SIZE
        textPaint.typeface = Typeface.MONOSPACE
        if (state.seqText != null) {
            canvas.drawText(state.seqText, area.left.toFloat(), y, textPaint)
            y += DETAIL_SIZE + TEXT_PADDING
        }

        // Slot countdown
        canvas.drawText(state.slotText, area.left.toFloat(), y, textPaint)
        y += DETAIL_SIZE + TEXT_PADDING

        // Band line
        textPaint.color = MUTED_COLOR
        textPaint.textSize = BAND_SIZE
        canvas.drawText(state.bandText, area.left.toFloat(), y, textPaint)
        y += BAND_SIZE + TEXT_PADDING

        // POTA line (only when active)
        if (state.potaText != null) {
            textPaint.color = ACCENT_COLOR
            textPaint.textSize = BAND_SIZE
            canvas.drawText(state.potaText, area.left.toFloat(), y, textPaint)
        }
    }

    // -- Layer 7: TX indicator dot --

    private fun drawTxIndicator(canvas: Canvas, txState: CarTxState) {
        val area = stableArea
        val cx = if (area != null) area.right.toFloat() - INDICATOR_RADIUS - 4f
                 else surfaceWidth - INDICATOR_RADIUS - 8f
        val cy = if (area != null) area.top.toFloat() + INDICATOR_RADIUS + 4f
                 else INDICATOR_RADIUS + 8f
        indicatorPaint.color = when (txState) {
            CarTxState.TRANSMITTING -> TX_INDICATOR_COLOR
            CarTxState.ARMED_RX    -> RX_INDICATOR_COLOR
            CarTxState.OFF         -> OFF_INDICATOR_COLOR
        }
        canvas.drawCircle(cx, cy, INDICATOR_RADIUS, indicatorPaint)
    }

    private companion object {
        // Background
        const val BG_COLOR = 0xFF07090F.toInt()

        // Land outlines
        const val LAND_FILL = 0x4094A3B8.toInt()
        const val LAND_STROKE = 0x9094A3B8.toInt()

        // Overlay
        const val OVERLAY_COLOR = 0xC0000000.toInt()

        // Station markers
        const val STATION_RADIUS = 3.5f
        const val OPERATOR_RADIUS = 5f
        const val ACCENT_COLOR = 0xFF4FC3F7.toInt()   // Material Light Blue 300

        // Text sizes (px — car surfaces are typically ~800×480 to 1920×1080)
        const val HEADLINE_SIZE = 36f
        const val TX_MSG_SIZE = 28f
        const val DETAIL_SIZE = 24f
        const val BAND_SIZE = 20f
        const val TEXT_PADDING = 8f

        // Muted text colour
        const val MUTED_COLOR = 0xFFAAAAAA.toInt()

        // TX indicator dot
        const val INDICATOR_RADIUS = 8f
        const val TX_INDICATOR_COLOR = 0xFFFF9800.toInt()   // Orange
        const val RX_INDICATOR_COLOR = 0xFF00BCD4.toInt()   // Cyan
        const val OFF_INDICATOR_COLOR = 0xFF666666.toInt()  // Grey
    }
}
