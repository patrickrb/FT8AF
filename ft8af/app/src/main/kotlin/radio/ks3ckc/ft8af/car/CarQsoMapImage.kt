package radio.ks3ckc.ft8af.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import radio.ks3ckc.ft8af.ui.map.WorldOutlines

/**
 * Renders a small static world-map bitmap for the [PaneTemplate] hero image.
 *
 * The map shows the operator's position and, when a QSO is active, the other
 * station's position with a connecting line. Uses a simple equirectangular
 * projection — accurate enough for an at-a-glance car display.
 *
 * Land-outline [Path] objects are cached and only rebuilt if the bitmap size
 * changes. The final [Bitmap] is cached by (opGrid, partnerCallsign) so
 * [onGetTemplate] doesn't re-render when nothing moved.
 */
internal object CarQsoMapImage {

    private const val WIDTH = 480
    private const val HEIGHT = 240

    // -- colours --
    private const val BG_COLOR       = 0xFF07090F.toInt()
    private const val LAND_FILL      = 0x4094A3B8.toInt()
    private const val LAND_STROKE    = 0x9094A3B8.toInt()
    private const val OP_DOT_COLOR   = 0xFFFFAF5E.toInt()   // accent orange
    private const val LINE_COLOR     = 0xAA5CD6E8.toInt()    // cyan, slightly transparent
    private const val STN_DOT_COLOR  = 0xFF5CD6E8.toInt()    // signal cyan

    // -- geometry --
    private const val DOT_RADIUS = 5f
    private const val LINE_WIDTH = 2f

    // -- paint pool (reused across calls) --
    private val fillPaint   = Paint().apply { color = LAND_FILL;   style = Paint.Style.FILL;   isAntiAlias = true }
    private val strokePaint = Paint().apply { color = LAND_STROKE; style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true }
    private val dotPaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val linePaint   = Paint().apply { color = LINE_COLOR;  style = Paint.Style.STROKE; strokeWidth = LINE_WIDTH; isAntiAlias = true }

    // -- caches --
    private var landPaths: List<Path>? = null

    /** Cache key for the last rendered bitmap so we skip work when nothing moved. */
    private var cacheKey: Triple<String?, String?, String?>? = null
    private var cachedBitmap: Bitmap? = null

    /**
     * Returns a [WIDTH]×[HEIGHT] bitmap with the world map, operator dot, and
     * (when in a QSO) a line + dot to the partner station.
     *
     * @param context      for loading [WorldOutlines]
     * @param opGrid       operator's Maidenhead grid, or null/empty if unknown
     * @param partnerCall  callsign of the QSO partner, or null when idle
     * @param partnerGrid  partner's Maidenhead grid, or null if unknown
     */
    fun render(
        context: Context,
        opGrid: String?,
        partnerCall: String?,
        partnerGrid: String?,
    ): Bitmap {
        // Fast path: return cached bitmap if inputs haven't changed.
        val key = Triple(opGrid, partnerCall, partnerGrid)
        cachedBitmap?.let { if (key == cacheKey) return it }

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Background
        canvas.drawColor(BG_COLOR)

        // 2. Land outlines
        val paths = landPaths ?: buildLandPaths(context).also { landPaths = it }
        for (p in paths) {
            canvas.drawPath(p, fillPaint)
            canvas.drawPath(p, strokePaint)
        }

        // Resolve positions
        val opLatLng = opGrid?.takeIf { it.isNotEmpty() }?.let { safeGridToLatLng(it) }
        val stnLatLng = partnerGrid?.takeIf { it.isNotEmpty() }?.let { safeGridToLatLng(it) }

        // 3. Connecting line (operator → partner)
        if (opLatLng != null && stnLatLng != null) {
            canvas.drawLine(
                lonToX(opLatLng.longitude), latToY(opLatLng.latitude),
                lonToX(stnLatLng.longitude), latToY(stnLatLng.latitude),
                linePaint,
            )
        }

        // 4. Operator dot
        if (opLatLng != null) {
            dotPaint.color = OP_DOT_COLOR
            canvas.drawCircle(lonToX(opLatLng.longitude), latToY(opLatLng.latitude), DOT_RADIUS, dotPaint)
        }

        // 5. Station dot
        if (stnLatLng != null) {
            dotPaint.color = STN_DOT_COLOR
            canvas.drawCircle(lonToX(stnLatLng.longitude), latToY(stnLatLng.latitude), DOT_RADIUS, dotPaint)
        }

        cacheKey = key
        cachedBitmap = bitmap
        return bitmap
    }

    // -- projection helpers --

    private fun lonToX(lon: Double): Float =
        (lon.toFloat() / 360f + 0.5f) * WIDTH

    private fun latToY(lat: Double): Float =
        (0.5f - lat.toFloat() / 180f) * HEIGHT

    private fun safeGridToLatLng(grid: String) = try {
        MaidenheadGrid.gridToLatLng(grid)
    } catch (_: Exception) { null }

    // -- land path builder --

    private fun buildLandPaths(context: Context): List<Path> {
        val rings = try { WorldOutlines.load(context) } catch (_: Exception) { return emptyList() }
        return rings.map { ring ->
            Path().apply {
                for (i in 0 until ring.size / 2) {
                    val px = lonToX(ring[i * 2].toDouble())
                    val py = latToY(ring[i * 2 + 1].toDouble())
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
        }
    }
}
