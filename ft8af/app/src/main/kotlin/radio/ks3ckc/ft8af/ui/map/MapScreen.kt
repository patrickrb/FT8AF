package radio.ks3ckc.ft8af.ui.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.pskreporter.PskReporterClient
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Data structures
// ---------------------------------------------------------------------------

private data class StationMarker(
    override val callsign: String,
    val grid: String,
    override val lat: Double,
    override val lon: Double,
    val snr: Int,
    val isCQ: Boolean,
    val isWorked: Boolean,
    override val isToMe: Boolean,
    val color: Color,
    val message: Ft8Message,
) : StationLine

// PSK Reporter overlay (issue #33) — a receiver that has decoded the operator's signal.
private data class PskSpotMarker(
    val receiverCallsign: String,
    val grid: String,
    val lat: Double,
    val lon: Double,
    val snr: Int,
    val frequencyHz: Long,
)

private const val PSK_OVERLAY_SECONDS_BACK = 3600
private const val PSK_POLL_INTERVAL_MS = 5L * 60L * 1000L

internal data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val distKm: Double,
)

internal enum class MapViewMode { STANDARD, AZIMUTHAL }

private const val MAP_MIN_ZOOM = 1f
private const val MAP_MAX_ZOOM = 8f
private const val MAP_ZOOM_STEP = 2f

// ---------------------------------------------------------------------------
// Great-circle distance (km) — used by both projections
// ---------------------------------------------------------------------------

internal fun greatCircleKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLam = Math.toRadians(lon2 - lon1)
    val cosC = sin(phi1) * sin(phi2) + cos(phi1) * cos(phi2) * cos(dLam)
    return acos(cosC.coerceIn(-1.0, 1.0)) * 6371.0
}

// ---------------------------------------------------------------------------
// Equirectangular (Plate Carrée) projection — lat/lon -> normalized [-1,1]
// ---------------------------------------------------------------------------

internal fun equirectProject(lat: Double, lon: Double): ProjectedPoint {
    return ProjectedPoint(
        x = (lon / 180.0).toFloat(),
        y = (-lat / 90.0).toFloat(),
        distKm = 0.0,
    )
}

// ---------------------------------------------------------------------------
// Azimuthal equidistant projection
// ---------------------------------------------------------------------------

internal fun azProject(opLat: Double, opLon: Double, lat: Double, lon: Double): ProjectedPoint {
    val phi1 = Math.toRadians(opLat)
    val lam1 = Math.toRadians(opLon)
    val phi2 = Math.toRadians(lat)
    val lam2 = Math.toRadians(lon)
    val dLam = lam2 - lam1

    val cosC = sin(phi1) * sin(phi2) + cos(phi1) * cos(phi2) * cos(dLam)
    val c = acos(cosC.coerceIn(-1.0, 1.0))

    if (c < 1e-10) {
        return ProjectedPoint(0f, 0f, 0.0)
    }

    val k = c / sin(c)
    val x = k * cos(phi2) * sin(dLam)
    val y = k * (cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLam))

    return ProjectedPoint(
        x = (x / PI).toFloat(),
        y = (-y / PI).toFloat(),
        distKm = c * 6371.0,
    )
}

// ---------------------------------------------------------------------------
// Map Screen
// ---------------------------------------------------------------------------

@Composable
fun MapScreen(mainViewModel: MainViewModel) {
    val messages by mainViewModel.mutableFt8MessageList.observeAsState(arrayListOf())
    val myGrid by GeneralVariables.mutableMyMaidenheadGrid.observeAsState(
        GeneralVariables.getMyMaidenheadGrid() ?: ""
    )
    // Active TX target — the station we're calling — drives the solid connection
    // line. "CQ"/blank/null means we're calling CQ or idle (no target line).
    val txTarget by mainViewModel.ft8TransmitSignal.mutableToCallsign.observeAsState()
    val txTargetCall = txTarget?.callsign?.takeIf { it.isNotBlank() && it != "CQ" }

    var selectedCallsign by remember { mutableStateOf<String?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(MapViewMode.STANDARD) }
    var pskOverlayEnabled by rememberSaveable { mutableStateOf(GeneralVariables.pskOverlayEnabled) }
    var pskSpots by remember { mutableStateOf<List<PskSpotMarker>>(emptyList()) }
    // Last overlay fetch error (transport/HTTP/rate-limit); null when the last fetch
    // succeeded or was a benign cooldown skip. Surfaced in the status row so an empty
    // overlay caused by a failure is distinguishable from "genuinely no spots".
    var pskError by remember { mutableStateOf<String?>(null) }

    // Zoom + pan (issue #51). Scale is clamped to [1, MAX_ZOOM]; pan is clamped so the
    // scaled map can't be dragged entirely off-screen. Both reset whenever the projection
    // changes — pan offsets in screen pixels don't translate meaningfully between
    // equirectangular and azimuthal.
    var mapScale by rememberSaveable { mutableStateOf(1f) }
    var mapPanX by rememberSaveable { mutableStateOf(0f) }
    var mapPanY by rememberSaveable { mutableStateOf(0f) }
    LaunchedEffect(viewMode) {
        mapScale = 1f
        mapPanX = 0f
        mapPanY = 0f
    }

    // PSK Reporter polling — fires immediately on enter and every 5 min while enabled.
    // Re-reads myCallsign each cycle so a mid-session change is picked up on the next tick.
    // Structured concurrency cancels the loop when MapScreen leaves composition.
    LaunchedEffect(pskOverlayEnabled) {
        if (!pskOverlayEnabled) {
            pskSpots = emptyList()
            pskError = null
            return@LaunchedEffect
        }
        var firstFetch = true
        while (true) {
            val call = GeneralVariables.myCallsign.trim().uppercase()
            if (call.isEmpty()) {
                pskSpots = emptyList()
                pskError = null
            } else {
                // force=true only on the first fetch after the overlay is (re)entered so a
                // recent prior-visit fetch within the 5-min cooldown doesn't leave the
                // overlay blank; the 429/503 back-off is still honoured.
                val spots = PskReporterClient.fetchSpotsForMe(call, PSK_OVERLAY_SECONDS_BACK, force = firstFetch)
                firstFetch = false
                if (spots != null) {
                    pskSpots = spots.map {
                        PskSpotMarker(
                            receiverCallsign = it.receiverCallsign,
                            grid = it.receiverGrid,
                            lat = it.receiverLat,
                            lon = it.receiverLon,
                            snr = it.snr,
                            frequencyHz = it.frequencyHz,
                        )
                    }
                    pskError = null
                } else {
                    // null = transport/HTTP error, rate-limit back-off, or cooldown skip.
                    // Keep prior spots so the overlay doesn't flicker; surface the reason
                    // (lastError is null for a benign cooldown skip). debug.log has detail.
                    pskError = PskReporterClient.lastError
                }
            }
            delay(PSK_POLL_INTERVAL_MS)
        }
    }

    // Derive operator lat/lon from grid
    val opLatLng = remember(myGrid) {
        if (myGrid.isNullOrEmpty()) null
        else try { MaidenheadGrid.gridToLatLng(myGrid) } catch (_: Exception) { null }
    }
    val opLat = opLatLng?.latitude ?: 0.0
    val opLon = opLatLng?.longitude ?: 0.0

    // Build station markers from decoded messages (deduplicate by callsign)
    val stations = remember(messages) {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<StationMarker>()
        for (msg in messages) {
            val call = msg.callsignFrom ?: continue
            if (call in seen) continue
            seen.add(call)

            val grid = msg.maidenGrid ?: continue
            val latLng = try {
                MaidenheadGrid.gridToLatLng(grid)
            } catch (_: Exception) { continue }
            if (latLng == null) continue

            val isCQ = msg.checkIsCQ()
            val isWorked = msg.isQSL_Callsign
            val isToMe = GeneralVariables.checkIsMyCallsign(msg.callsignTo ?: "")

            val color = when {
                isToMe -> Signal
                isWorked -> StatusWorked
                isCQ && !isWorked -> Accent
                else -> StatusNew
            }

            result.add(
                StationMarker(
                    callsign = call,
                    grid = grid,
                    lat = latLng.latitude,
                    lon = latLng.longitude,
                    snr = msg.snr,
                    isCQ = isCQ,
                    isWorked = isWorked,
                    isToMe = isToMe,
                    color = color,
                    message = msg,
                )
            )
        }
        result
    }

    val selectedStation = stations.find { it.callsign == selectedCallsign }

    // Connection lines: solid to the TX target, dashed from anyone calling us.
    val connectionLines = remember(stations, txTargetCall) {
        buildConnectionLines(stations, txTargetCall)
    }

    // Canvas size for pan clamping — the map now fills this Box edge-to-edge.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Clamp a candidate pan so the map can't be dragged into empty space. STANDARD
    // routes through EquirectViewport (which allows horizontal pan even at zoom 1×,
    // since the 2:1 world overflows a tall canvas); AZIMUTHAL only pans when zoomed.
    fun clampPan(px: Float, py: Float, scale: Float): Offset {
        val w = canvasSize.width.toFloat()
        val hgt = canvasSize.height.toFloat()
        if (w <= 0f || hgt <= 0f) return Offset(px, py)
        return when (viewMode) {
            MapViewMode.STANDARD -> EquirectViewport(w, hgt, scale).clampPan(px, py)
            MapViewMode.AZIMUTHAL -> if (scale > 1f) {
                val mx = w * (scale - 1f) / 2f
                val my = hgt * (scale - 1f) / 2f
                Offset(px.coerceIn(-mx, mx), py.coerceIn(-my, my))
            } else {
                Offset.Zero
            }
        }
    }

    // Full-bleed map with floating control overlays — pinch to zoom, drag to pan,
    // double-tap to toggle 2× zoom in/out.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .onSizeChanged { canvasSize = it }
            .pointerInput(viewMode) {
                detectTransformGestures { _, panDelta, zoom, _ ->
                    val newScale = (mapScale * zoom).coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)
                    val clamped = clampPan(mapPanX + panDelta.x, mapPanY + panDelta.y, newScale)
                    mapPanX = clamped.x
                    mapPanY = clamped.y
                    mapScale = newScale
                }
            }
            .pointerInput(viewMode) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        // Toggle between 1× and ZOOM_STEP×, zooming around the tap point so
                        // the tapped feature stays under the finger.
                        val target = if (mapScale < MAP_ZOOM_STEP * 0.95f) MAP_ZOOM_STEP else 1f
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val factor = target / mapScale
                        val newPanX = (1f - factor) * (tap.x - cx) + factor * mapPanX
                        val newPanY = (1f - factor) * (tap.y - cy) + factor * mapPanY
                        val clamped = clampPan(newPanX, newPanY, target)
                        mapPanX = clamped.x
                        mapPanY = clamped.y
                        mapScale = target
                    },
                )
            },
    ) {
        when (viewMode) {
            MapViewMode.STANDARD -> StandardMapCanvas(
                opLat = opLat,
                opLon = opLon,
                stations = stations,
                pskSpots = pskSpots,
                connectionLines = connectionLines,
                selectedCallsign = selectedCallsign,
                onStationSelected = { selectedCallsign = it },
                scale = mapScale,
                panX = mapPanX,
                panY = mapPanY,
                modifier = Modifier.fillMaxSize(),
            )
            MapViewMode.AZIMUTHAL -> AzimuthalMapCanvas(
                opLat = opLat,
                opLon = opLon,
                stations = stations,
                pskSpots = pskSpots,
                connectionLines = connectionLines,
                selectedCallsign = selectedCallsign,
                onStationSelected = { selectedCallsign = it },
                scale = mapScale,
                panX = mapPanX,
                panY = mapPanY,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Right edge: zoom controls.
        ZoomControls(
            scale = mapScale,
            onZoomIn = {
                val newScale = (mapScale * MAP_ZOOM_STEP).coerceAtMost(MAP_MAX_ZOOM)
                if (newScale != mapScale) {
                    val factor = newScale / mapScale
                    val clamped = clampPan(mapPanX * factor, mapPanY * factor, newScale)
                    mapPanX = clamped.x
                    mapPanY = clamped.y
                    mapScale = newScale
                }
            },
            onZoomOut = {
                val newScale = (mapScale / MAP_ZOOM_STEP).coerceAtLeast(MAP_MIN_ZOOM)
                val factor = newScale / mapScale
                val clamped = clampPan(mapPanX * factor, mapPanY * factor, newScale)
                mapPanX = clamped.x
                mapPanY = clamped.y
                mapScale = newScale
            },
            onReset = {
                mapScale = 1f
                mapPanX = 0f
                mapPanY = 0f
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(8.dp),
        )

        // Top-left: grid + station/heard counts + projection (floating chip).
        MapInfoChip(
            myGrid = myGrid,
            stationCount = stations.size,
            pskOverlayEnabled = pskOverlayEnabled,
            pskSpotCount = pskSpots.size,
            pskError = pskError,
            viewMode = viewMode,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )

        // Top-right: PSK overlay toggle + STD/AZ view toggle (floating).
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PskOverlayToggle(
                enabled = pskOverlayEnabled,
                onToggle = { newVal ->
                    pskOverlayEnabled = newVal
                    GeneralVariables.pskOverlayEnabled = newVal
                    mainViewModel.databaseOpr.writeConfig(
                        "pskOverlayEnabled",
                        if (newVal) "1" else "0",
                        null,
                    )
                },
            )
            Spacer(modifier = Modifier.width(6.dp))
            MapViewToggle(
                mode = viewMode,
                onModeChange = { viewMode = it },
            )
        }

        // Bottom: selected station info card (floating sheet).
        if (selectedStation != null) {
            SelectedStationCard(
                station = selectedStation,
                opLat = opLat,
                opLon = opLon,
                onDismiss = { selectedCallsign = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Floating info chip (grid + counts + projection)
// ---------------------------------------------------------------------------

@Composable
private fun MapInfoChip(
    myGrid: String?,
    stationCount: Int,
    pskOverlayEnabled: Boolean,
    pskSpotCount: Int,
    pskError: String?,
    viewMode: MapViewMode,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                text = if (myGrid.isNullOrEmpty()) stringResource(R.string.map_no_grid_set) else myGrid,
                color = Signal,
                fontFamily = GeistMonoFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (pskOverlayEnabled) {
                    stringResource(R.string.map_station_count_with_heard, stationCount, pskSpotCount)
                } else {
                    stringResource(R.string.map_station_count, stationCount)
                },
                color = TextMuted,
                fontSize = 10.5.sp,
            )
            if (pskOverlayEnabled && pskError != null) {
                Text(
                    text = stringResource(R.string.map_psk_error, pskError),
                    color = StatusNew,
                    fontSize = 10.5.sp,
                )
            }
            Text(
                text = if (viewMode == MapViewMode.STANDARD) {
                    stringResource(R.string.map_projection_equirectangular)
                } else {
                    stringResource(R.string.map_projection_azimuthal)
                },
                color = TextDim,
                fontSize = 9.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// View-mode toggle (segmented pill)
// ---------------------------------------------------------------------------

@Composable
internal fun MapViewToggle(
    mode: MapViewMode,
    onModeChange: (MapViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgSurface3),
    ) {
        TogglePill(
            label = stringResource(R.string.map_mode_standard),
            active = mode == MapViewMode.STANDARD,
            onClick = { onModeChange(MapViewMode.STANDARD) },
        )
        TogglePill(
            label = stringResource(R.string.map_mode_azimuthal),
            active = mode == MapViewMode.AZIMUTHAL,
            onClick = { onModeChange(MapViewMode.AZIMUTHAL) },
        )
    }
}

@Composable
internal fun PskOverlayToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) PskSpot else BgSurface3)
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.map_overlay_psk),
            color = if (enabled) BgApp else TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TogglePill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) BgApp else TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Zoom controls overlay
// ---------------------------------------------------------------------------

@Composable
private fun ZoomControls(
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canZoomIn = scale < MAP_MAX_ZOOM - 0.001f
    val canZoomOut = scale > MAP_MIN_ZOOM + 0.001f
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgSurface3.copy(alpha = 0.85f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZoomButton(label = "+", enabled = canZoomIn, onClick = onZoomIn)
        ZoomButton(label = "−", enabled = canZoomOut, onClick = onZoomOut)
        ZoomButton(label = "1×", enabled = canZoomOut, onClick = onReset, fontSize = 11.sp)
    }
}

@Composable
private fun ZoomButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    Box(
        modifier = Modifier
            .width(32.dp)
            .height(28.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextFaint,
            fontFamily = GeistMonoFamily,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Azimuthal map canvas
// ---------------------------------------------------------------------------

@Composable
private fun AzimuthalMapCanvas(
    opLat: Double,
    opLon: Double,
    stations: List<StationMarker>,
    pskSpots: List<PskSpotMarker>,
    connectionLines: List<ConnectionLine>,
    selectedCallsign: String?,
    onStationSelected: (String?) -> Unit,
    scale: Float,
    panX: Float,
    panY: Float,
    modifier: Modifier = Modifier,
) {
    // Shared infinite transition drives every station's pulse phase via index offsets,
    // so we avoid N independent animations.
    val pulseTransition = rememberInfiniteTransition(label = "map-pulse")
    val pulsePhase by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "map-pulse-phase",
    )

    val context = LocalContext.current
    val landRings by produceState<List<FloatArray>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { WorldOutlines.load(context) }
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f * 0.92f
        // Operator's screen position after pan — used as the centre for range rings and
        // compass bearings so they stay anchored to the operator marker when panned.
        val opX = cx + panX
        val opY = cy + panY

        // Background circle (fixed — the disc itself doesn't move when panning).
        drawCircle(
            color = BgSurface,
            radius = r,
            center = Offset(cx, cy),
        )

        // Land — Natural Earth 110m vector outlines projected via azProject
        landRings?.let { rings -> drawAzimuthalLand(rings, opLat, opLon, cx, cy, r, scale, panX, panY) }

        // Range rings (scale with zoom, follow operator on pan)
        drawRangeRings(opX, opY, r, scale)

        // Compass bearings stay anchored to the disc — they're a fixed UI reference,
        // not a map feature, so they don't pan or scale.
        drawCompassBearings(cx, cy, r)

        // Connection lines (operator -> TX target / stations calling us). Drawn under
        // the operator marker + station markers. Skip any whose endpoint is off-disc.
        for (line in connectionLines) {
            val proj = azProject(opLat, opLon, line.toLat, line.toLon)
            val ex = cx + proj.x * r * scale + panX
            val ey = cy + proj.y * r * scale + panY
            val ddx = ex - cx
            val ddy = ey - cy
            if (sqrt(ddx * ddx + ddy * ddy) > r) continue
            drawConnectionLine(opX, opY, ex, ey, line.style, line.color)
        }

        // Operator center marker
        drawCircle(
            color = Accent,
            radius = 4f,
            center = Offset(opX, opY),
        )

        // PSK Reporter spots — drawn before stations so decoded markers layer on top.
        // Square shape distinguishes them from circular station markers.
        for (spot in pskSpots) {
            val proj = azProject(opLat, opLon, spot.lat, spot.lon)
            val sx = cx + proj.x * r * scale + panX
            val sy = cy + proj.y * r * scale + panY
            // Skip markers that fall outside the disc visually
            val ddx = sx - cx
            val ddy = sy - cy
            if (sqrt(ddx * ddx + ddy * ddy) > r) continue

            drawMarkerShape(MarkerShape.SQUARE_PSK, sx, sy, 2.5f, PskSpot.copy(alpha = 0.7f))

            // Receiver callsign — only when zoomed in (declutter; PSK spots aren't selectable).
            if (scale >= LABEL_ZOOM_THRESHOLD) {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(160, 248, 113, 113)
                    textSize = 14f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.NORMAL,
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    spot.receiverCallsign,
                    sx + 5f,
                    sy + paint.textSize / 3f,
                    paint,
                )
            }
        }

        // Station markers
        for ((index, station) in stations.withIndex()) {
            val proj = azProject(opLat, opLon, station.lat, station.lon)
            val sx = cx + proj.x * r * scale + panX
            val sy = cy + proj.y * r * scale + panY

            // Check if within map circle
            val ddx = sx - cx
            val ddy = sy - cy
            if (sqrt(ddx * ddx + ddy * ddy) > r) continue

            val isSelected = station.callsign == selectedCallsign
            val style = markerStyleFor(
                isCQ = station.isCQ,
                isToMe = station.isToMe,
                isWorked = station.isWorked,
                snr = station.snr,
                isSelected = isSelected,
                currentZoom = scale,
            )
            val glowR = if (isSelected) 12f else 8f

            // Pulse rings: 2 expanding rings per station, staggered by 0.5 phase. Phase derived from
            // shared infinite transition with per-station offset (no per-marker InfiniteTransition).
            val baseAmp = if (isSelected) 1f else 0.55f
            val stationOffset = (index * 0.137f) % 1f
            for (ringIndex in 0..1) {
                val phase = ((pulsePhase + stationOffset + ringIndex * 0.5f) % 1f)
                val ringR = glowR + (16f + (if (isSelected) 10f else 0f)) * phase
                val ringAlpha = (1f - phase) * 0.35f * baseAmp
                if (ringAlpha > 0f) {
                    drawCircle(
                        color = style.fill.copy(alpha = ringAlpha),
                        radius = ringR,
                        center = Offset(sx, sy),
                        style = Stroke(width = 1f),
                    )
                }
            }

            // Bearing line for selected station (originates at the operator's screen pos)
            if (isSelected) {
                drawLine(
                    color = style.fill.copy(alpha = 0.4f),
                    start = Offset(opX, opY),
                    end = Offset(sx, sy),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                )
            }

            // Glow
            drawCircle(
                color = style.fill.copy(alpha = if (isSelected) 0.25f else 0.15f),
                radius = glowR,
                center = Offset(sx, sy),
            )

            // Marker shape (per type: triangle CQ / diamond to-me / donut worked / dot)
            drawMarkerShape(style.shape, sx, sy, style.radiusPx, style.fill)

            // Callsign label — only when zoomed in or selected (declutter)
            if (style.showLabel) {
                val textColor = if (isSelected) {
                    android.graphics.Color.WHITE
                } else {
                    android.graphics.Color.argb(180, 138, 150, 177) // TextMuted
                }
                val paint = android.graphics.Paint().apply {
                    color = textColor
                    textSize = if (isSelected) 24f else 20f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.NORMAL,
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    station.callsign,
                    sx + glowR + 4f,
                    sy + paint.textSize / 3f,
                    paint,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Standard (equirectangular) map canvas
// ---------------------------------------------------------------------------

@Composable
private fun StandardMapCanvas(
    opLat: Double,
    opLon: Double,
    stations: List<StationMarker>,
    pskSpots: List<PskSpotMarker>,
    connectionLines: List<ConnectionLine>,
    selectedCallsign: String?,
    onStationSelected: (String?) -> Unit,
    scale: Float,
    panX: Float,
    panY: Float,
    modifier: Modifier = Modifier,
) {
    val pulseTransition = rememberInfiniteTransition(label = "map-pulse-std")
    val pulsePhase by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "map-pulse-phase-std",
    )

    val context = LocalContext.current
    val landRings by produceState<List<FloatArray>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { WorldOutlines.load(context) }
    }

    Canvas(modifier = modifier) {
        val vp = EquirectViewport(size.width, size.height, scale, panX, panY)

        // Background panel
        drawRect(color = BgSurface, size = size)

        // Land — Natural Earth 110m vector outlines (drawn once polygons are loaded)
        landRings?.let { rings -> drawWorldLand(rings, vp) }

        // Lat/lon grid (over land so it stays visible across continents)
        drawEquirectGrid(vp)

        // Operator marker (at projected lat/lon, scaled + panned with map)
        val opPos = vp.projectLatLon(opLat, opLon)
        val opX = opPos.x
        val opY = opPos.y
        drawCircle(
            color = Accent.copy(alpha = 0.25f),
            radius = 8f,
            center = Offset(opX, opY),
        )
        drawCircle(
            color = Accent,
            radius = 4f,
            center = Offset(opX, opY),
        )

        // Connection lines (operator -> TX target / stations calling us). Drawn under
        // the station markers; both endpoints project through the viewport so they
        // pan/zoom with the map.
        for (line in connectionLines) {
            val end = vp.projectLatLon(line.toLat, line.toLon)
            drawConnectionLine(opX, opY, end.x, end.y, line.style, line.color)
        }

        // PSK Reporter spots — drawn before stations so decoded markers layer on top.
        for (spot in pskSpots) {
            val pos = vp.projectLatLon(spot.lat, spot.lon)
            val sx = pos.x
            val sy = pos.y

            drawMarkerShape(MarkerShape.SQUARE_PSK, sx, sy, 2.5f, PskSpot.copy(alpha = 0.7f))

            // Receiver callsign — only when zoomed in (declutter; PSK spots aren't selectable).
            if (scale >= LABEL_ZOOM_THRESHOLD) {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(160, 248, 113, 113)
                    textSize = 14f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.NORMAL,
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    spot.receiverCallsign,
                    sx + 5f,
                    sy + paint.textSize / 3f,
                    paint,
                )
            }
        }

        // Station markers
        for ((index, station) in stations.withIndex()) {
            val pos = vp.projectLatLon(station.lat, station.lon)
            val sx = pos.x
            val sy = pos.y

            val isSelected = station.callsign == selectedCallsign
            val style = markerStyleFor(
                isCQ = station.isCQ,
                isToMe = station.isToMe,
                isWorked = station.isWorked,
                snr = station.snr,
                isSelected = isSelected,
                currentZoom = scale,
            )
            val glowR = if (isSelected) 12f else 8f

            // Pulse rings (same pattern as azimuthal: shared transition + per-station phase offset)
            val baseAmp = if (isSelected) 1f else 0.55f
            val stationOffset = (index * 0.137f) % 1f
            for (ringIndex in 0..1) {
                val phase = ((pulsePhase + stationOffset + ringIndex * 0.5f) % 1f)
                val ringR = glowR + (16f + (if (isSelected) 10f else 0f)) * phase
                val ringAlpha = (1f - phase) * 0.35f * baseAmp
                if (ringAlpha > 0f) {
                    drawCircle(
                        color = style.fill.copy(alpha = ringAlpha),
                        radius = ringR,
                        center = Offset(sx, sy),
                        style = Stroke(width = 1f),
                    )
                }
            }

            // Bearing line for selected station (straight rhumb-style on flat map)
            if (isSelected) {
                drawLine(
                    color = style.fill.copy(alpha = 0.4f),
                    start = Offset(opX, opY),
                    end = Offset(sx, sy),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                )
            }

            // Glow
            drawCircle(
                color = style.fill.copy(alpha = if (isSelected) 0.25f else 0.15f),
                radius = glowR,
                center = Offset(sx, sy),
            )

            // Marker shape (per type: triangle CQ / diamond to-me / donut worked / dot)
            drawMarkerShape(style.shape, sx, sy, style.radiusPx, style.fill)

            // Callsign label — only when zoomed in or selected (declutter)
            if (style.showLabel) {
                val textColor = if (isSelected) {
                    android.graphics.Color.WHITE
                } else {
                    android.graphics.Color.argb(180, 138, 150, 177)
                }
                val paint = android.graphics.Paint().apply {
                    color = textColor
                    textSize = if (isSelected) 22f else 18f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.NORMAL,
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    station.callsign,
                    sx + glowR + 4f,
                    sy + paint.textSize / 3f,
                    paint,
                )
            }
        }
    }
}

// Draw an operator -> station connection line. Solid + thicker for the active TX
// target; dashed + thinner for stations calling us.
private fun DrawScope.drawConnectionLine(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    style: LineStyle,
    color: Color,
) {
    when (style) {
        LineStyle.SOLID_TX -> drawLine(
            color = color.copy(alpha = 0.85f),
            start = Offset(x0, y0),
            end = Offset(x1, y1),
            strokeWidth = 2.2f,
        )
        LineStyle.DASHED_CALLING_ME -> drawLine(
            color = color.copy(alpha = 0.7f),
            start = Offset(x0, y0),
            end = Offset(x1, y1),
            strokeWidth = 1.6f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
        )
    }
}

// Draw a station/spot marker glyph. The shape encodes the station's state (set by
// markerStyleFor); colour + size come from the MarkerStyle. Each shape gets a BgApp
// outline so it reads against land/ocean alike.
private fun DrawScope.drawMarkerShape(shape: MarkerShape, cx: Float, cy: Float, r: Float, fill: Color) {
    when (shape) {
        MarkerShape.DOT_DEFAULT -> {
            drawCircle(fill, r, Offset(cx, cy))
            drawCircle(BgApp, r, Offset(cx, cy), style = Stroke(width = 1.2f))
        }
        MarkerShape.SQUARE_PSK -> {
            drawRect(fill, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2))
            drawRect(BgApp, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2), style = Stroke(width = 0.8f))
        }
        MarkerShape.CHECK_WORKED -> {
            // Donut: filled disc with the centre punched out — distinct from a plain dot.
            drawCircle(fill, r, Offset(cx, cy))
            drawCircle(BgApp, r * 0.45f, Offset(cx, cy))
            drawCircle(BgApp, r, Offset(cx, cy), style = Stroke(width = 1.2f))
        }
        MarkerShape.TRIANGLE_CQ -> {
            val p = Path().apply {
                moveTo(cx, cy - r * 1.3f)
                lineTo(cx - r * 1.2f, cy + r * 0.9f)
                lineTo(cx + r * 1.2f, cy + r * 0.9f)
                close()
            }
            drawPath(p, fill)
            drawPath(p, BgApp, style = Stroke(width = 1.2f))
        }
        MarkerShape.DIAMOND_TO_ME -> {
            val p = Path().apply {
                moveTo(cx, cy - r * 1.35f)
                lineTo(cx + r * 1.35f, cy)
                lineTo(cx, cy + r * 1.35f)
                lineTo(cx - r * 1.35f, cy)
                close()
            }
            drawPath(p, fill)
            drawPath(p, BgApp, style = Stroke(width = 1.2f))
        }
    }
}

// Inline projection scalars from the viewport. Equivalent to vp.projectLatLon but
// without allocating an Offset per vertex — these helpers run every animation
// frame over thousands of land vertices, so the land/grid paths use raw floats.
private fun DrawScope.drawEquirectGrid(vp: EquirectViewport) {
    val gridColor = Color(0x1894A3B8)
    val axisColor = Color(0x30FFAF5E) // equator + prime meridian — accent tint
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
    val cx = vp.canvasW / 2f
    val cy = vp.canvasH / 2f
    val sxUnit = vp.worldPxW / 2f
    val syUnit = vp.worldPxH / 2f

    // Meridians (vertical lines) every 30° lon, from -180 to 180
    var lon = -180
    while (lon <= 180) {
        val isPrime = lon == 0
        val x = cx + (lon.toFloat() / 180f) * sxUnit + vp.panX
        drawLine(
            color = if (isPrime) axisColor else gridColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = if (isPrime) 1f else 0.5f,
            pathEffect = dashEffect,
        )
        lon += 30
    }

    // Parallels (horizontal lines) every 30° lat, from -90 to 90
    var lat = -90
    while (lat <= 90) {
        val isEquator = lat == 0
        val y = cy + (-lat.toFloat() / 90f) * syUnit + vp.panY
        drawLine(
            color = if (isEquator) axisColor else gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (isEquator) 1f else 0.5f,
            pathEffect = dashEffect,
        )
        lat += 30
    }
}

private fun DrawScope.drawWorldLand(rings: List<FloatArray>, vp: EquirectViewport) {
    // rings are flat float arrays of [lon, lat, lon, lat, ...] in geographic degrees.
    // Project via equirectangular and apply scale + pan around the canvas centre.
    val cx = vp.canvasW / 2f
    val cy = vp.canvasH / 2f
    val sxUnit = vp.worldPxW / 2f
    val syUnit = vp.worldPxH / 2f
    fun px(lon: Float) = cx + (lon / 180f) * sxUnit + vp.panX
    fun py(lat: Float) = cy + (-lat / 90f) * syUnit + vp.panY

    val path = Path()
    for (ring in rings) {
        if (ring.size < 6) continue // need at least 3 points for a polygon
        path.moveTo(px(ring[0]), py(ring[1]))
        var i = 2
        while (i < ring.size) {
            path.lineTo(px(ring[i]), py(ring[i + 1]))
            i += 2
        }
        path.close()
    }
    drawPath(path, color = Color(0x4094A3B8))                                  // fill
    drawPath(path, color = Color(0x9094A3B8), style = Stroke(width = 0.75f))   // outline
}

// ---------------------------------------------------------------------------
// Azimuthal land renderer + drawing helpers
// ---------------------------------------------------------------------------

private fun DrawScope.drawAzimuthalLand(
    rings: List<FloatArray>,
    opLat: Double,
    opLon: Double,
    cx: Float,
    cy: Float,
    r: Float,
    scale: Float,
    panX: Float,
    panY: Float,
) {
    val land = Path()
    for (ring in rings) {
        if (ring.size < 6) continue
        var first = true
        var i = 0
        while (i < ring.size) {
            val lon = ring[i].toDouble()
            val lat = ring[i + 1].toDouble()
            val proj = azProject(opLat, opLon, lat, lon)
            val px = cx + proj.x * r * scale + panX
            val py = cy + proj.y * r * scale + panY
            if (first) {
                land.moveTo(px, py)
                first = false
            } else {
                land.lineTo(px, py)
            }
            i += 2
        }
        land.close()
    }
    // Clip to the disc so anything that strays past the horizon (or wraps
    // weirdly near the antipode) is hidden.
    val disc = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(
            left = cx - r, top = cy - r, right = cx + r, bottom = cy + r,
        ))
    }
    clipPath(disc) {
        drawPath(land, color = Color(0x4094A3B8))
        drawPath(land, color = Color(0x9094A3B8), style = Stroke(width = 0.75f))
    }
}

private fun DrawScope.drawRangeRings(cx: Float, cy: Float, r: Float, scale: Float = 1f) {
    val maxKm = 20015.0
    val rings = listOf(2500, 5000, 10000, 15000, 20000)
    val ringColor = Color(0x1894A3B8)
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))

    for (km in rings) {
        val ringR = (km.toFloat() / maxKm.toFloat()) * r * (PI.toFloat() / 2f) * scale
        drawCircle(
            color = ringColor,
            radius = ringR,
            center = Offset(cx, cy),
            style = Stroke(width = 1f, pathEffect = dashEffect),
        )
    }
}

private fun DrawScope.drawCompassBearings(cx: Float, cy: Float, r: Float) {
    val directions = listOf(
        Triple("N", 0.0, true),
        Triple("NE", 45.0, false),
        Triple("E", 90.0, true),
        Triple("SE", 135.0, false),
        Triple("S", 180.0, true),
        Triple("SW", 225.0, false),
        Triple("W", 270.0, true),
        Triple("NW", 315.0, false),
    )

    for ((label, angle, isCardinal) in directions) {
        val rad = Math.toRadians(angle - 90.0) // -90 to align N with top
        val lineColor = if (isCardinal) {
            Color(0x30FFAF5E) // accent tint
        } else {
            Color(0x1894A3B8)
        }

        val endX = cx + cos(rad).toFloat() * r
        val endY = cy + sin(rad).toFloat() * r

        drawLine(
            color = lineColor,
            start = Offset(cx, cy),
            end = Offset(endX, endY),
            strokeWidth = if (isCardinal) 1f else 0.5f,
        )

        // Label
        val labelR = r + 12f
        val lx = cx + cos(rad).toFloat() * labelR
        val ly = cy + sin(rad).toFloat() * labelR

        val paint = android.graphics.Paint().apply {
            color = if (isCardinal) {
                android.graphics.Color.argb(200, 255, 175, 94) // Accent
            } else {
                android.graphics.Color.argb(100, 148, 163, 184)
            }
            textSize = if (isCardinal) 22f else 18f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                if (isCardinal) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
        }
        drawContext.canvas.nativeCanvas.drawText(
            label,
            lx,
            ly + paint.textSize / 3f,
            paint,
        )
    }
}

// ---------------------------------------------------------------------------
// Selected station info card
// ---------------------------------------------------------------------------

@Composable
private fun SelectedStationCard(
    station: StationMarker,
    opLat: Double,
    opLon: Double,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val distKm = greatCircleKm(opLat, opLon, station.lat, station.lon)
    val bearing = computeBearing(opLat, opLon, station.lat, station.lon)

    GlassCard(modifier = modifier.clickable { onDismiss() }) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = station.callsign,
                    color = station.color,
                    fontFamily = GeistMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    text = station.grid,
                    color = TextMuted,
                    fontFamily = GeistMonoFamily,
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                InfoChip(MaidenheadGrid.formatDist(distKm), stringResource(R.string.map_info_distance))
                InfoChip("${String.format("%.0f", bearing)}\u00B0", stringResource(R.string.map_info_bearing))
                InfoChip("${station.snr} dB", stringResource(R.string.map_info_snr))
            }
        }
    }
}

@Composable
private fun InfoChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = TextPrimary,
            fontFamily = GeistMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Text(
            text = label,
            color = TextFaint,
            fontSize = 9.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Bearing calculation
// ---------------------------------------------------------------------------

internal fun computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLam = Math.toRadians(lon2 - lon1)

    val y = sin(dLam) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLam)
    val bearing = Math.toDegrees(atan2(y, x))
    return (bearing + 360.0) % 360.0
}
