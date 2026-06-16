package radio.ks3ckc.ft8us.ui.pota

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bg7yoz.ft8cn.GeneralVariables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8us.theme.Accent
import radio.ks3ckc.ft8us.theme.BgApp
import radio.ks3ckc.ft8us.theme.BgSurface
import radio.ks3ckc.ft8us.theme.Signal
import radio.ks3ckc.ft8us.ui.map.UsStateOutlines
import radio.ks3ckc.ft8us.ui.map.WorldOutlines

/**
 * Compact equirectangular map auto-zoomed to frame the operator's location and
 * every plottable contact for a POTA activation, with a dashed line from the
 * operator out to each contact. Mirrors the QSO-distance map (`QsoPathMap` in
 * QsoSheet) but for N contacts instead of one.
 *
 * Renders nothing when there are no contacts (caller should build inputs via
 * [buildActivationMapData], which returns null in that case).
 */
@Composable
internal fun PotaActivationMap(
    operatorLat: Double,
    operatorLon: Double,
    contacts: List<MapContact>,
    modifier: Modifier = Modifier,
) {
    if (contacts.isEmpty()) return

    val context = LocalContext.current
    val landRings by produceState<List<FloatArray>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { WorldOutlines.load(context) }
    }
    val stateRings by produceState<List<FloatArray>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { UsStateOutlines.load(context) }
    }
    val myCall = GeneralVariables.myCallsign?.trim().orEmpty()

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .activationMap(
                landRings = landRings,
                stateRings = stateRings,
                operatorLat = operatorLat,
                operatorLon = operatorLon,
                contacts = contacts,
                operatorColor = Accent,
                contactColor = Signal,
                operatorLabel = myCall.ifEmpty { null },
            ),
    )
}

/**
 * Equirectangular activation map drawn via [drawWithCache]: the land + US state
 * ring sets are projected into screen-space [Path]s once per size/data change in
 * the cache phase, so the per-frame draw only issues `drawPath`/`drawLine` calls.
 */
private fun Modifier.activationMap(
    landRings: List<FloatArray>?,
    stateRings: List<FloatArray>?,
    operatorLat: Double,
    operatorLon: Double,
    contacts: List<MapContact>,
    operatorColor: Color,
    contactColor: Color,
    operatorLabel: String?,
): Modifier = drawWithCache {
    val proj = PotaActivationProjection(
        operatorLat = operatorLat,
        operatorLon = operatorLon,
        contacts = contacts.map { Pair(it.lat, it.lon) },
        width = size.width,
        height = size.height,
    )

    val landPath = landRings?.let { buildRingPath(it, proj) }
    val statePath = stateRings?.let { buildRingPath(it, proj) }

    val opX = proj.projectX(operatorLon)
    val opY = proj.projectY(operatorLat)
    // Screen positions for each contact, using the short-path normalized lon.
    val contactPts = contacts.mapIndexed { i, c ->
        Offset(proj.projectX(proj.normalizedContactLons[i]), proj.projectY(c.lat))
    }
    // Built once per size/data change, not per contact per frame.
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))

    onDrawBehind {
        drawRect(color = BgSurface, size = size)

        // Land fill + coastline.
        landPath?.let { land ->
            drawPath(land, color = Color(0x4094A3B8))
            drawPath(land, color = Color(0x9094A3B8), style = Stroke(width = 0.75f))
        }
        // US state borders (stroke only) layered over the land fill.
        statePath?.let {
            drawPath(it, color = Color(0x5594A3B8), style = Stroke(width = 0.6f))
        }

        // Dashed line operator -> each contact, then a dot per contact.
        for (pt in contactPts) {
            drawLine(
                color = operatorColor.copy(alpha = 0.55f),
                start = Offset(opX, opY),
                end = pt,
                strokeWidth = 1.5f,
                pathEffect = dashEffect,
            )
        }
        for (pt in contactPts) {
            drawContactDot(pt.x, pt.y, contactColor)
        }

        // Operator on top, with a label.
        drawStationDot(opX, opY, operatorColor)
        operatorLabel?.let { drawMapLabel(it, opX, opY, operatorColor) }
    }
}

/**
 * Build a closed [Path] from polygon rings, repeated at -360/0/+360 lon offsets
 * so continents that wrap across the view's edge still appear. Mirrors the
 * helper in QsoSheet but bound to [PotaActivationProjection].
 */
private fun buildRingPath(rings: List<FloatArray>, proj: PotaActivationProjection): Path {
    val path = Path()
    for (off in doubleArrayOf(-360.0, 0.0, 360.0)) {
        for (ring in rings) {
            if (ring.size < 6) continue
            path.moveTo(proj.projectX(ring[0].toDouble() + off), proj.projectY(ring[1].toDouble()))
            var i = 2
            while (i < ring.size) {
                path.lineTo(proj.projectX(ring[i].toDouble() + off), proj.projectY(ring[i + 1].toDouble()))
                i += 2
            }
            path.close()
        }
    }
    return path
}

private fun DrawScope.drawStationDot(x: Float, y: Float, color: Color) {
    drawCircle(color = color.copy(alpha = 0.22f), radius = 9f, center = Offset(x, y))
    drawCircle(color = color, radius = 4.5f, center = Offset(x, y))
    drawCircle(color = BgApp, radius = 4.5f, center = Offset(x, y), style = Stroke(width = 1.3f))
}

private fun DrawScope.drawContactDot(x: Float, y: Float, color: Color) {
    drawCircle(color = color.copy(alpha = 0.20f), radius = 6f, center = Offset(x, y))
    drawCircle(color = color, radius = 3f, center = Offset(x, y))
}

private fun DrawScope.drawMapLabel(text: String, x: Float, y: Float, color: Color) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = 22f
        isAntiAlias = true
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.BOLD,
        )
    }
    drawContext.canvas.nativeCanvas.drawText(text, x + 9f, y + paint.textSize / 3f, paint)
}
