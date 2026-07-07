package radio.ks3ckc.ft8af.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Surface
import radio.ks3ckc.ft8af.ui.map.StateLabel

/**
 * Draws the car map directly onto an Android Auto [Surface]: land outlines →
 * station markers → operator→partner line → operator dot. All drawing is on the
 * caller's thread (main thread from the 1 Hz tick); the surface lock serialises
 * access.
 *
 * The map is the full-bleed background of a MapWithContentTemplate; the status
 * text is rendered by the host in the content card, so nothing but the map is
 * drawn here. Because drawing bypasses the template system, visual updates (e.g.
 * a new decode) never trigger `invalidate()` → `onGetTemplate()`, which
 * eliminates the dimming/flashing caused by full template rebuilds.
 */
internal class CarMapSurfaceRenderer {

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /**
     * The host's safe drawing insets. [stableArea] is the region never covered by
     * host chrome (action strip, clock); [visibleArea] is what's currently on-screen.
     * Both start empty and are filled by the SurfaceCallback. We anchor the status
     * panel and centre the zoomed map inside this box so the top isn't clipped.
     */
    private val stableArea = Rect()
    private val visibleArea = Rect()

    /** Cached land outline paths — rebuilt only when the projection changes. */
    private var landPaths: List<Path>? = null
    private var landPathsSignature: String? = null

    /** Cached country border paths — rebuilt only when the projection changes. */
    private var countryPaths: List<Path>? = null
    private var countryPathsSignature: String? = null

    /** Cached US state border paths — rebuilt only when the projection changes. */
    private var statePaths: List<Path>? = null
    private var statePathsSignature: String? = null

    // -- Paints (allocated once, mutated per-frame only for colour) --

    private val landFillPaint = Paint().apply { color = LAND_FILL; style = Paint.Style.FILL; isAntiAlias = true }
    private val landStrokePaint = Paint().apply {
        color = LAND_STROKE; style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true
    }
    private val countryStrokePaint = Paint().apply {
        color = COUNTRY_STROKE; style = Paint.Style.STROKE; strokeWidth = COUNTRY_STROKE_WIDTH; isAntiAlias = true
    }
    private val stateStrokePaint = Paint().apply {
        color = STATE_STROKE; style = Paint.Style.STROKE; strokeWidth = STATE_STROKE_WIDTH; isAntiAlias = true
    }
    private val stateLabelPaint = Paint().apply {
        color = STATE_LABEL_COLOR; isAntiAlias = true
        textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE
    }
    private val markerPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val pskPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = PSK_RING_WIDTH; isAntiAlias = true }
    private val linePaint = Paint().apply {
        color = LINE_COLOR; style = Paint.Style.STROKE; strokeWidth = LINE_WIDTH; isAntiAlias = true
    }
    private val panelPaint = Paint().apply { color = PANEL_COLOR; style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE }
    private val boldMono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

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
        countryPaths = null
        statePaths = null
    }

    // The host reports visible/stable insets for content layout. The map background
    // still fills the whole surface, but the status panel and the zoomed-in framing
    // are kept inside these bounds so nothing lands under the host's top chrome.
    @Synchronized
    fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea.set(visibleArea)
    }

    @Synchronized
    fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea.set(stableArea)
    }

    /**
     * The safe content box: the stable area when the host has reported a non-empty
     * one, else the visible area, else a conservative default inset off the surface.
     * The default matters because the host may not report an area before the first
     * frame — falling back to the full surface would let the panel clip under the
     * top/bottom chrome until the callback arrives. Callers get a defensive copy.
     */
    private fun contentRect(): Rect = when {
        !stableArea.isEmpty -> Rect(stableArea)
        !visibleArea.isEmpty -> Rect(visibleArea)
        else -> Rect(
            (surfaceWidth * DEFAULT_INSET_X).toInt(),
            (surfaceHeight * DEFAULT_INSET_TOP).toInt(),
            surfaceWidth - (surfaceWidth * DEFAULT_INSET_X).toInt(),
            surfaceHeight - (surfaceHeight * DEFAULT_INSET_BOTTOM).toInt(),
        )
    }

    // -- Frame drawing --

    /**
     * Draws a complete map frame for [state] onto the surface. Safe to call from
     * any thread; returns silently if the surface is not available.
     *
     * @param landRings  Pre-parsed GeoJSON land outline rings (lon/lat interleaved
     *                   FloatArrays) from [WorldOutlines.load]. Passed in so the
     *                   renderer doesn't need a Context.
     */
    @Synchronized
    fun drawFrame(
        state: CarSurfaceState,
        landRings: List<FloatArray>,
        countryRings: List<FloatArray> = emptyList(),
        stateRings: List<FloatArray> = emptyList(),
        stateLabels: List<StateLabel> = emptyList(),
        statusBarInset: Int = 0,
        navBarInset: Int = 0,
    ) {
        val surf = surface ?: return
        if (!surf.isValid) return
        val canvas: Canvas = try {
            surf.lockCanvas(null) ?: return
        } catch (_: IllegalArgumentException) {
            return
        }
        try {
            val content = contentRect()
            val projection = projectionFor(state, content)
            canvas.drawColor(BG_COLOR)
            drawLand(canvas, getOrBuildLandPaths(landRings, projection))
            drawBorders(canvas, getOrBuildCountryPaths(countryRings, projection), countryStrokePaint)
            drawBorders(canvas, getOrBuildStatePaths(stateRings, projection), stateStrokePaint)
            drawStateLabels(canvas, stateLabels, projection)
            drawStationMarkers(canvas, state.stations, projection)
            drawPskMarkers(canvas, state.pskStations, projection)
            drawConnection(canvas, state, projection)
            drawOperatorDot(canvas, state.opLat, state.opLon, projection)
            drawStatusPanel(canvas, state, content, statusBarInset, navBarInset)
        } finally {
            surf.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * Zooms in to frame the operator and their QSO partner (the two stations
     * communicating) inside the safe content box; otherwise shows the whole world.
     */
    private fun projectionFor(state: CarSurfaceState, content: Rect): CarMapProjection {
        val opKnown = !state.opLat.isNaN() && !state.opLon.isNaN()
        val partnerKnown = !state.partnerLat.isNaN() && !state.partnerLon.isNaN()
        if (!opKnown || !partnerKnown) return CarMapProjection.world(surfaceWidth, surfaceHeight)
        return CarMapProjection.fit(
            width = surfaceWidth,
            height = surfaceHeight,
            cx = content.exactCenterX(),
            cy = content.exactCenterY(),
            contentW = content.width().toFloat(),
            contentH = content.height().toFloat(),
            points = listOf(
                state.opLat to state.opLon,
                state.partnerLat to state.partnerLon,
            ),
        )
    }

    // -- Path building (shared by land, country borders, state borders) --

    /** Projects a set of lon/lat rings into closed screen-space [Path]s. */
    private fun buildPaths(rings: List<FloatArray>, projection: CarMapProjection): List<Path> =
        rings.map { ring ->
            Path().apply {
                for (i in 0 until ring.size / 2) {
                    val px = projection.x(ring[i * 2])
                    val py = projection.y(ring[i * 2 + 1])
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
        }

    // -- Layer 1: land outlines --

    private fun getOrBuildLandPaths(rings: List<FloatArray>, projection: CarMapProjection): List<Path> {
        landPaths?.let { if (landPathsSignature == projection.signature) return it }
        return buildPaths(rings, projection).also {
            landPaths = it
            landPathsSignature = projection.signature
        }
    }

    private fun drawLand(canvas: Canvas, paths: List<Path>) {
        for (p in paths) {
            canvas.drawPath(p, landFillPaint)
            canvas.drawPath(p, landStrokePaint)
        }
    }

    // -- Layer 1b: country + US state borders + state labels --

    private fun getOrBuildCountryPaths(rings: List<FloatArray>, projection: CarMapProjection): List<Path> {
        countryPaths?.let { if (countryPathsSignature == projection.signature) return it }
        return buildPaths(rings, projection).also {
            countryPaths = it
            countryPathsSignature = projection.signature
        }
    }

    private fun getOrBuildStatePaths(rings: List<FloatArray>, projection: CarMapProjection): List<Path> {
        statePaths?.let { if (statePathsSignature == projection.signature) return it }
        return buildPaths(rings, projection).also {
            statePaths = it
            statePathsSignature = projection.signature
        }
    }

    private fun drawBorders(canvas: Canvas, paths: List<Path>, paint: Paint) {
        for (p in paths) canvas.drawPath(p, paint)
    }

    /**
     * Draws the two-letter state abbreviation at each state's anchor, but only once
     * the map is zoomed in enough to be legible (a whole-world view would just be a
     * pile of overlapping labels), and only for anchors that fall on-screen.
     */
    private fun drawStateLabels(canvas: Canvas, labels: List<StateLabel>, projection: CarMapProjection) {
        if (projection.zoomLevel < STATE_LABEL_MIN_ZOOM || labels.isEmpty()) return
        stateLabelPaint.textSize = surfaceHeight * STATE_LABEL_SIZE_FRACTION
        // Centre the glyph vertically on the anchor.
        val yShift = -(stateLabelPaint.ascent() + stateLabelPaint.descent()) / 2f
        for (label in labels) {
            val x = projection.x(label.lon)
            val y = projection.y(label.lat)
            if (x < 0f || x > surfaceWidth || y < 0f || y > surfaceHeight) continue
            canvas.drawText(label.abbr, x, y + yShift, stateLabelPaint)
        }
    }

    // -- Layer 2: station markers --

    private fun drawStationMarkers(canvas: Canvas, stations: List<CarStationMarker>, projection: CarMapProjection) {
        for (s in stations) {
            markerPaint.color = s.colorInt
            canvas.drawCircle(projection.x(s.lon.toFloat()), projection.y(s.lat.toFloat()), STATION_RADIUS, markerPaint)
        }
    }

    // -- Layer 2b: PSK "who heard me" markers (hollow rings) --

    private fun drawPskMarkers(canvas: Canvas, stations: List<CarStationMarker>, projection: CarMapProjection) {
        for (s in stations) {
            pskPaint.color = s.colorInt
            canvas.drawCircle(projection.x(s.lon.toFloat()), projection.y(s.lat.toFloat()), PSK_RADIUS, pskPaint)
        }
    }

    // -- Layer 3: operator → partner line --

    private fun drawConnection(canvas: Canvas, state: CarSurfaceState, projection: CarMapProjection) {
        if (state.opLat.isNaN() || state.opLon.isNaN()) return
        if (state.partnerLat.isNaN() || state.partnerLon.isNaN()) return
        canvas.drawLine(
            projection.x(state.opLon.toFloat()), projection.y(state.opLat.toFloat()),
            projection.x(state.partnerLon.toFloat()), projection.y(state.partnerLat.toFloat()),
            linePaint,
        )
    }

    // -- Layer 4: operator dot --

    private fun drawOperatorDot(canvas: Canvas, lat: Double, lon: Double, projection: CarMapProjection) {
        if (lat.isNaN() || lon.isNaN()) return
        markerPaint.color = OPERATOR_COLOR
        canvas.drawCircle(projection.x(lon.toFloat()), projection.y(lat.toFloat()), OPERATOR_RADIUS, markerPaint)
    }

    // -- Layer 5: translucent status panel (map shows through it) --

    private data class PanelLine(val text: String, val color: Int, val size: Float, val bold: Boolean)

    /**
     * Splits the status into two full-width banners so the map centre stays clear:
     * the live QSO state (headline + slot) rides a top banner, the context (band +
     * POTA) a bottom banner. Both span the whole surface width but stay within the
     * safe area vertically so the host's top/bottom chrome can't clip them.
     */
    private fun drawStatusPanel(
        canvas: Canvas,
        state: CarSurfaceState,
        content: Rect,
        statusBarInset: Int,
        navBarInset: Int,
    ) {
        val h = surfaceHeight.toFloat()
        val headSize = h * 0.055f
        val detailSize = h * 0.042f

        val topLines = listOf(
            PanelLine(state.headlineText, TEXT_PRIMARY, headSize, bold = true),
            PanelLine(state.slotText, TEXT_MUTED, detailSize, bold = false),
        )
        val bottomLines = buildList {
            add(PanelLine(state.bandText, TEXT_MUTED, detailSize, bold = false))
            state.potaText?.let { add(PanelLine(it, TEXT_ACCENT, detailSize, bold = false)) }
        }

        // Anchor flush to the system bars when known (top banner just under the status
        // bar, bottom banner just above the nav bar), else fall back to the safe area.
        // Left-aligned text clears the top-right action strip, so the top banner can
        // ride up into that band without colliding with the Decodes button.
        val topAnchor = if (statusBarInset > 0) statusBarInset else content.top
        val bottomAnchor = if (navBarInset > 0) surfaceHeight - navBarInset else content.bottom

        drawStatusBanner(canvas, topLines, content, anchorY = topAnchor, anchorTop = true)
        drawStatusBanner(canvas, bottomLines, content, anchorY = bottomAnchor, anchorTop = false)
    }

    /** Draws one full-width translucent banner of stacked, left-aligned text lines. */
    private fun drawStatusBanner(
        canvas: Canvas,
        lines: List<PanelLine>,
        content: Rect,
        anchorY: Int,
        anchorTop: Boolean,
    ) {
        if (lines.isEmpty()) return
        val h = surfaceHeight.toFloat()
        val padding = h * 0.022f
        val gap = h * 0.012f

        var textH = 0f
        for ((i, l) in lines.withIndex()) {
            textH += l.size + if (i < lines.lastIndex) gap else 0f
        }
        val barH = textH + padding * 2
        val box = if (anchorTop) {
            carBannerBoxTop(surfaceWidth, anchorY, barH)
        } else {
            carBannerBoxBottom(surfaceWidth, anchorY, barH)
        }
        canvas.drawRect(box.left, box.top, box.right, box.bottom, panelPaint)

        // Keep text within the safe horizontal inset even though the bar is full-width.
        val textX = content.left + padding
        var y = box.top + padding
        for (l in lines) {
            textPaint.color = l.color
            textPaint.textSize = l.size
            textPaint.typeface = if (l.bold) boldMono else Typeface.MONOSPACE
            y += l.size
            canvas.drawText(l.text, textX, y, textPaint)
            y += gap
        }
    }

    private companion object {
        const val BG_COLOR = 0xFF07090F.toInt()

        // Conservative safe-area defaults used only until the host reports its areas.
        const val DEFAULT_INSET_X = 0.03f
        const val DEFAULT_INSET_TOP = 0.10f
        const val DEFAULT_INSET_BOTTOM = 0.14f

        const val LAND_FILL = 0x4094A3B8.toInt()
        const val LAND_STROKE = 0x9094A3B8.toInt()

        // National borders — a touch brighter than state lines, still under coastlines.
        const val COUNTRY_STROKE = 0x99A6B2C6.toInt()
        const val COUNTRY_STROKE_WIDTH = 1f

        // US state borders — thinner and dimmer than coastlines so they read as
        // internal divisions, not shoreline. Labels only appear once zoomed in.
        const val STATE_STROKE = 0x66B7C2D4.toInt()
        const val STATE_STROKE_WIDTH = 0.8f
        const val STATE_LABEL_COLOR = 0xB0CBD5E1.toInt()
        const val STATE_LABEL_MIN_ZOOM = 2f
        const val STATE_LABEL_SIZE_FRACTION = 0.032f

        const val STATION_RADIUS = 3.5f
        const val OPERATOR_RADIUS = 5f
        const val OPERATOR_COLOR = 0xFFFFAF5E.toInt()   // accent orange

        // PSK "who heard me" hollow rings
        const val PSK_RADIUS = 5f
        const val PSK_RING_WIDTH = 2f

        // Operator → partner connection line
        const val LINE_COLOR = 0xAA5CD6E8.toInt()   // cyan, slightly transparent
        const val LINE_WIDTH = 2f

        // Translucent status panel — ~55% black so the map reads through it.
        const val PANEL_COLOR = 0x8C000000.toInt()
        const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
        const val TEXT_MUTED = 0xFFC5CED6.toInt()
        const val TEXT_ACCENT = 0xFFFFAF5E.toInt()
    }
}
