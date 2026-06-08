package radio.ks3ckc.ft8us.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom icon set for FT8AF, drawn as stroked paths matching the design prototype.
 * All icons use a 24x24 coordinate space.
 */
object FT8USIcons {

    private fun strokeStyle(strokeWidth: Float = 1.6f) = Stroke(
        width = strokeWidth,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    @Composable
    fun Decode(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            // Three horizontal lines
            drawLine(tint, Offset(3.5f * s, 6f * s), Offset(20.5f * s, 6f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(3.5f * s, 12f * s), Offset(15.5f * s, 12f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(3.5f * s, 18f * s), Offset(20.5f * s, 18f * s), stroke.width, StrokeCap.Round)
            // Dot at right of middle line
            drawCircle(tint, 1.2f * s, Offset(19f * s, 12f * s))
        }
    }

    @Composable
    fun Globe(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            // Outer circle
            drawCircle(tint, 9f * s, Offset(12f * s, 12f * s), style = stroke)
            // Horizontal line
            drawLine(tint, Offset(3f * s, 12f * s), Offset(21f * s, 12f * s), stroke.width, StrokeCap.Round)
            // Vertical ellipses (meridians)
            val meridianPath1 = Path().apply {
                addOval(Rect(Offset(7f * s, 3f * s), Size(10f * s, 18f * s)))
            }
            drawPath(meridianPath1, tint, style = stroke)
        }
    }

    @Composable
    fun Waterfall(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            // Four vertical bars
            drawLine(tint, Offset(4f * s, 19f * s), Offset(4f * s, 9f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(9f * s, 19f * s), Offset(9f * s, 5f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(14f * s, 19f * s), Offset(14f * s, 11f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(19f * s, 19f * s), Offset(19f * s, 7f * s), stroke.width, StrokeCap.Round)
        }
    }

    @Composable
    fun Book(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            // Book body
            val bookPath = Path().apply {
                moveTo(5f * s, 4.5f * s)
                // Top-left rounded corner
                cubicTo(5f * s, 3.67f * s, 5.67f * s, 3f * s, 6.5f * s, 3f * s)
                lineTo(19f * s, 3f * s)
                lineTo(19f * s, 18f * s)
                lineTo(6.5f * s, 18f * s)
                cubicTo(5.67f * s, 18f * s, 5f * s, 18.67f * s, 5f * s, 19.5f * s)
                lineTo(5f * s, 4.5f * s)
            }
            drawPath(bookPath, tint, style = stroke)
            // Bottom spine
            val spinePath = Path().apply {
                moveTo(5f * s, 19.5f * s)
                cubicTo(5f * s, 20.33f * s, 5.67f * s, 21f * s, 6.5f * s, 21f * s)
                lineTo(19f * s, 21f * s)
            }
            drawPath(spinePath, tint, style = stroke)
            // Text lines
            drawLine(tint, Offset(9f * s, 8f * s), Offset(15f * s, 8f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(9f * s, 12f * s), Offset(13f * s, 12f * s), stroke.width, StrokeCap.Round)
        }
    }

    @Composable
    fun Cog(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            // Center circle
            drawCircle(tint, 3f * s, Offset(12f * s, 12f * s), style = stroke)
            // Spokes
            val spokes = listOf(
                Offset(12f, 2f) to Offset(12f, 5f),
                Offset(12f, 19f) to Offset(12f, 22f),
                Offset(2f, 12f) to Offset(5f, 12f),
                Offset(19f, 12f) to Offset(22f, 12f),
                Offset(4.9f, 4.9f) to Offset(7f, 7f),
                Offset(17f, 17f) to Offset(19.1f, 19.1f),
                Offset(4.9f, 19.1f) to Offset(7f, 17f),
                Offset(17f, 7f) to Offset(19.1f, 4.9f),
            )
            for ((start, end) in spokes) {
                drawLine(tint, Offset(start.x * s, start.y * s), Offset(end.x * s, end.y * s), stroke.width, StrokeCap.Round)
            }
        }
    }

    @Composable
    fun Search(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            drawCircle(tint, 6.5f * s, Offset(11f * s, 11f * s), style = stroke)
            drawLine(tint, Offset(16.5f * s, 16.5f * s), Offset(20f * s, 20f * s), stroke.width, StrokeCap.Round)
        }
    }

    @Composable
    fun Filter(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            drawLine(tint, Offset(4f * s, 5f * s), Offset(20f * s, 5f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(7f * s, 12f * s), Offset(17f * s, 12f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(10f * s, 19f * s), Offset(14f * s, 19f * s), stroke.width, StrokeCap.Round)
        }
    }

    @Composable
    fun Chevron(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            val path = Path().apply {
                moveTo(9f * s, 6f * s)
                lineTo(15f * s, 12f * s)
                lineTo(9f * s, 18f * s)
            }
            drawPath(path, tint, style = stroke)
        }
    }

    @Composable
    fun ChevronDown(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            val path = Path().apply {
                moveTo(6f * s, 9f * s)
                lineTo(12f * s, 15f * s)
                lineTo(18f * s, 9f * s)
            }
            drawPath(path, tint, style = stroke)
        }
    }

    @Composable
    fun Close(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            drawLine(tint, Offset(6f * s, 6f * s), Offset(18f * s, 18f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(18f * s, 6f * s), Offset(6f * s, 18f * s), stroke.width, StrokeCap.Round)
        }
    }

    @Composable
    fun Transmit(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            // Center dot
            drawCircle(tint, 2f * s, Offset(12f * s, 12f * s), style = stroke)
            // Inner arcs (simplified as partial circles)
            drawArc(tint, -135f, 90f, false, Offset(7f * s, 7f * s), Size(10f * s, 10f * s), style = stroke)
            drawArc(tint, -45f, 90f, false, Offset(7f * s, 7f * s), Size(10f * s, 10f * s), style = stroke)
            // Outer arcs
            drawArc(tint, -135f, 90f, false, Offset(3.5f * s, 3.5f * s), Size(17f * s, 17f * s), style = stroke)
            drawArc(tint, -45f, 90f, false, Offset(3.5f * s, 3.5f * s), Size(17f * s, 17f * s), style = stroke)
        }
    }

    @Composable
    fun Check(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            val path = Path().apply {
                moveTo(4f * s, 12f * s)
                lineTo(9f * s, 17f * s)
                lineTo(20f * s, 5f * s)
            }
            drawPath(path, tint, style = stroke)
        }
    }

    @Composable
    fun Plus(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            drawLine(tint, Offset(12f * s, 5f * s), Offset(12f * s, 19f * s), strokeWidth * s, StrokeCap.Round)
            drawLine(tint, Offset(5f * s, 12f * s), Offset(19f * s, 12f * s), strokeWidth * s, StrokeCap.Round)
        }
    }

    @Composable
    fun Info(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            drawCircle(tint, 9f * s, Offset(12f * s, 12f * s), style = stroke)
            drawLine(tint, Offset(12f * s, 11f * s), Offset(12f * s, 17f * s), stroke.width, StrokeCap.Round)
            drawCircle(tint, 0.6f * s, Offset(12f * s, 8f * s))
        }
    }

    @Composable
    fun Target(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            drawCircle(tint, 9f * s, Offset(12f * s, 12f * s), style = stroke)
            drawCircle(tint, 5f * s, Offset(12f * s, 12f * s), style = stroke)
            drawCircle(tint, 1.5f * s, Offset(12f * s, 12f * s)) // filled
        }
    }

    /** Three tightly-spaced horizontal lines — switch to compact decode list. */
    @Composable
    fun ViewCompact(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            drawLine(tint, Offset(4f * s, 8f * s), Offset(20f * s, 8f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(4f * s, 12f * s), Offset(20f * s, 12f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(4f * s, 16f * s), Offset(20f * s, 16f * s), stroke.width, StrokeCap.Round)
        }
    }

    /** Three widely-spaced horizontal lines — switch to expanded decode list. */
    @Composable
    fun ViewExpanded(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            drawLine(tint, Offset(4f * s, 5f * s), Offset(20f * s, 5f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(4f * s, 12f * s), Offset(20f * s, 12f * s), stroke.width, StrokeCap.Round)
            drawLine(tint, Offset(4f * s, 19f * s), Offset(20f * s, 19f * s), stroke.width, StrokeCap.Round)
        }
    }

    /**
     * Circular arrow — toggles "clear the decode list every cycle". A near-full
     * loop with an arrowhead conveys the per-slot refresh; tint with [Accent]
     * when the mode is on, [TextMuted] when off.
     */
    @Composable
    fun AutoClear(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            // Loop with a gap at the top-right where the arrowhead sits.
            drawArc(
                tint, -60f, 300f, false,
                Offset(5f * s, 5f * s), Size(14f * s, 14f * s), style = stroke,
            )
            // Arrowhead at the leading end of the arc (top-right), pointing tangentially.
            val head = Path().apply {
                moveTo(11.8f * s, 5.2f * s)
                lineTo(15.5f * s, 5.6f * s)
                lineTo(15.0f * s, 9.4f * s)
            }
            drawPath(head, tint, style = stroke)
        }
    }

    /** Triangular pine tree on a small trunk — POTA tab. */
    @Composable
    fun Tree(
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        size: Dp = 22.dp,
        strokeWidth: Float = 1.6f,
    ) {
        val tint = if (color == Color.Unspecified) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else color
        Canvas(modifier = modifier.then(Modifier.sizeOf(size))) {
            val s = this.size.width / 24f
            val stroke = strokeStyle(strokeWidth * s)
            // Trunk
            drawLine(tint, Offset(12f * s, 19f * s), Offset(12f * s, 17f * s), stroke.width, StrokeCap.Round)
            // Three stacked triangles (foliage)
            val crown = Path().apply {
                moveTo(12f * s, 3f * s)
                lineTo(6f * s, 11f * s)
                lineTo(9f * s, 11f * s)
                lineTo(5f * s, 17f * s)
                lineTo(19f * s, 17f * s)
                lineTo(15f * s, 11f * s)
                lineTo(18f * s, 11f * s)
                close()
            }
            drawPath(crown, tint, style = stroke)
        }
    }
}

// Extension to apply a dp size uniformly
private fun Modifier.sizeOf(dp: Dp): Modifier =
    this.then(Modifier.size(dp))
