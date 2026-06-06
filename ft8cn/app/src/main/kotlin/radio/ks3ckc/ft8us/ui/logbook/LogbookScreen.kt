package radio.ks3ckc.ft8us.ui.logbook

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import radio.ks3ckc.ft8us.ui.motion.MotionTokens
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.count.CountDbOpr
import com.bg7yoz.ft8cn.log.QSLCallsignRecord
import com.bg7yoz.ft8cn.log.ThirdPartyService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8us.theme.*
import radio.ks3ckc.ft8us.ui.components.AnimatedCounter
import radio.ks3ckc.ft8us.ui.components.EmptyStateWaves
import radio.ks3ckc.ft8us.ui.components.GlassCard
import radio.ks3ckc.ft8us.ui.components.QsoStatus
import radio.ks3ckc.ft8us.ui.components.ShimmerBox
import radio.ks3ckc.ft8us.ui.components.StatusPill
import radio.ks3ckc.ft8us.ui.components.TopBar
import radio.ks3ckc.ft8us.ui.components.TopBarSubtitle
import radio.ks3ckc.ft8us.ui.decode.UsStateLookup
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// Band color mapping
// ---------------------------------------------------------------------------

private val BandColorMap = mapOf(
    "20M" to Band20m,
    "15M" to Band15m,
    "40M" to Band40m,
    "10M" to Band10m,
    "30M" to Band30m,
    "17M" to Band17m,
    "12M" to Band12m,
)

private fun bandColor(band: String): Color =
    BandColorMap[band.uppercase().trim()] ?: TextMuted

// ---------------------------------------------------------------------------
// Tab enum
// ---------------------------------------------------------------------------

private enum class LogbookTab(@StringRes val labelRes: Int) {
    STATS(R.string.log_tab_stats),
    RECENT(R.string.log_tab_recent),
    AWARDS(R.string.log_tab_awards),
}

// ---------------------------------------------------------------------------
// Data holders for async queries
// ---------------------------------------------------------------------------

private data class LogbookStats(
    val totalQsos: Int = 0,
    val dxccEntities: Int = 0,
    val cqZones: Int = 0,
    val ituZones: Int = 0,
    val bandCounts: List<Pair<String, Int>> = emptyList(),
)

private data class AwardProgress(
    val name: String,
    val description: String,
    val current: Int,
    val total: Int,
    val color: Color,
)

// ---------------------------------------------------------------------------
// LogbookScreen (public entry point)
// ---------------------------------------------------------------------------

@Composable
fun LogbookScreen(mainViewModel: MainViewModel) {
    var activeTab by remember { mutableStateOf(LogbookTab.STATS) }
    var exportSheetVisible by remember { mutableStateOf(false) }

    // Async-loaded state
    var stats by remember { mutableStateOf(LogbookStats()) }
    var records by remember { mutableStateOf<List<QSLCallsignRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Bumped after an edit or delete to re-run the loader.
    var refreshKey by remember { mutableIntStateOf(0) }

    // Per-row action state for the Recent tab
    var editingRecord by remember { mutableStateOf<QSLCallsignRecord?>(null) }
    var deletingRecord by remember { mutableStateOf<QSLCallsignRecord?>(null) }

    // Catch-up sync UI state
    var syncDialogState by remember { mutableStateOf<SyncDialogState?>(null) }

    val scope = rememberCoroutineScope()

    // Load records and stats from the database. Re-runs when refreshKey changes
    // (e.g. after the user edits or deletes a QSO).
    LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) {
            try {
                val opr = mainViewModel.databaseOpr
                val db = opr?.db
                if (opr == null || db == null) {
                    isLoading = false
                    return@withContext
                }

                // QSO records — pull all rows, no filter
                val loaded = suspendCancellableCoroutine<List<QSLCallsignRecord>> { cont ->
                    opr.getQSLCallsignsByCallsign(true, 0, "", 0) { result ->
                        cont.resume(result?.toList() ?: emptyList())
                    }
                }
                records = loaded
                // Mirror into the legacy ViewModel field so other (Java) screens stay in sync.
                mainViewModel.callsignRecords?.let {
                    it.clear()
                    it.addAll(loaded)
                }

                // Total QSOs (single-fire callback)
                val totalInfo = suspendCancellableCoroutine { cont ->
                    val resumed = AtomicBoolean(false)
                    CountDbOpr.getQSLTotal(db) { info ->
                        if (resumed.compareAndSet(false, true)) cont.resume(info)
                    }
                }
                val totalQsos = totalInfo?.values?.sumOf { it.value } ?: 0

                // DXCC (callback fires twice; take only the first)
                val dxccInfo = suspendCancellableCoroutine { cont ->
                    val resumed = AtomicBoolean(false)
                    CountDbOpr.getDxcc(db) { info ->
                        if (resumed.compareAndSet(false, true)) cont.resume(info)
                    }
                }
                val dxccCount = dxccInfo?.values?.size ?: 0

                // CQ Zones (callback fires twice; take only the first)
                val cqInfo = suspendCancellableCoroutine { cont ->
                    val resumed = AtomicBoolean(false)
                    CountDbOpr.getCQZoneCount(db) { info ->
                        if (resumed.compareAndSet(false, true)) cont.resume(info)
                    }
                }
                val cqCount = cqInfo?.values?.size ?: 0

                // ITU Zones (callback fires twice; take only the first)
                val ituInfo = suspendCancellableCoroutine { cont ->
                    val resumed = AtomicBoolean(false)
                    CountDbOpr.getItuCount(db) { info ->
                        if (resumed.compareAndSet(false, true)) cont.resume(info)
                    }
                }
                val ituCount = ituInfo?.values?.size ?: 0

                // Band counts (single-fire callback)
                val bandInfo = suspendCancellableCoroutine { cont ->
                    val resumed = AtomicBoolean(false)
                    CountDbOpr.getBandCount(db) { info ->
                        if (resumed.compareAndSet(false, true)) cont.resume(info)
                    }
                }
                val bandCounts = bandInfo?.values?.map { (it.name ?: "") to it.value }
                    ?: emptyList()

                stats = LogbookStats(
                    totalQsos = totalQsos,
                    dxccEntities = dxccCount,
                    cqZones = cqCount,
                    ituZones = ituCount,
                    bandCounts = bandCounts,
                )
            } catch (_: Exception) {
                // Leave records/stats at defaults on error
            }
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgApp),
        ) {
            // Top bar
            TopBar(
                title = stringResource(R.string.log_title),
                subtitle = {
                    val count = if (stats.totalQsos > 0) stats.totalQsos else records.size
                    TopBarSubtitle(text = stringResource(R.string.log_subtitle_qsos_all_bands, count))
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (syncDialogState?.inProgress == true) return@IconButton
                            val cl = GeneralVariables.enableCloudlog
                            val qrz = GeneralVariables.enableQRZ
                            if (!cl && !qrz) {
                                syncDialogState = SyncDialogState(
                                    inProgress = false,
                                    done = 0,
                                    total = 0,
                                    cloudlogOk = 0,
                                    qrzOk = 0,
                                    cloudlogAttempted = false,
                                    qrzAttempted = false,
                                    finished = true,
                                    noServicesEnabled = true,
                                )
                                return@IconButton
                            }
                            syncDialogState = SyncDialogState(
                                inProgress = true,
                                done = 0,
                                total = 0,
                                cloudlogOk = 0,
                                qrzOk = 0,
                                cloudlogAttempted = cl,
                                qrzAttempted = qrz,
                                finished = false,
                                noServicesEnabled = false,
                            )
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    val db = mainViewModel.databaseOpr?.db
                                        ?: return@withContext null
                                    ThirdPartyService.syncAllQSOs(db) { done, total, ok1, ok2 ->
                                        // Marshal back to main thread for state update
                                        syncDialogState = syncDialogState?.copy(
                                            done = done,
                                            total = total,
                                            cloudlogOk = ok1,
                                            qrzOk = ok2,
                                        )
                                    }
                                }
                                syncDialogState = syncDialogState?.copy(
                                    inProgress = false,
                                    finished = true,
                                    total = result?.total ?: 0,
                                    cloudlogOk = result?.cloudlogOk ?: 0,
                                    qrzOk = result?.qrzOk ?: 0,
                                )
                                // Re-query QSLTable so the row chips pick up the
                                // newly-set synced_cloudlog / synced_qrz flags.
                                refreshKey++
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = stringResource(R.string.log_cd_sync_to_logging_services),
                            tint = TextMuted,
                        )
                    }
                    IconButton(onClick = { exportSheetVisible = true }) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.log_cd_export_qsos),
                            tint = TextMuted,
                        )
                    }
                },
            )

            // Segmented tab switcher
            SegmentedTabRow(
                tabs = LogbookTab.entries,
                selected = activeTab,
                onSelected = { activeTab = it },
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Tab content
            when (activeTab) {
                LogbookTab.STATS -> if (isLoading) StatsLoadingPlaceholder() else StatsTab(stats, records)
                LogbookTab.RECENT -> RecentTab(
                    records = records,
                    onEdit = { editingRecord = it },
                    onDelete = { deletingRecord = it },
                )
                LogbookTab.AWARDS -> AwardsTab(stats)
            }
        }

        // Export bottom sheet (overlays on top)
        ExportLogSheet(
            visible = exportSheetVisible,
            mainViewModel = mainViewModel,
            onDismiss = { exportSheetVisible = false },
        )

        // Per-row edit dialog
        editingRecord?.let { rec ->
            EditQsoDialog(
                record = rec,
                onDismiss = { editingRecord = null },
                onSave = { newCall, newGrid, newMode ->
                    editingRecord = null
                    if (rec.id > 0) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val db = mainViewModel.databaseOpr?.db ?: return@withContext
                                val values = android.content.ContentValues().apply {
                                    put("call", newCall.trim().uppercase())
                                    put("gridsquare", newGrid.trim())
                                    put("mode", newMode.trim())
                                }
                                db.update("QSLTable", values, "id=?",
                                    arrayOf(rec.id.toString()))
                            }
                            refreshKey++
                        }
                    }
                },
            )
        }

        // Per-row delete confirmation
        deletingRecord?.let { rec ->
            DeleteQsoConfirm(
                record = rec,
                onCancel = { deletingRecord = null },
                onConfirm = {
                    deletingRecord = null
                    if (rec.id > 0) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val db = mainViewModel.databaseOpr?.db ?: return@withContext
                                db.execSQL("delete from QSLTable where id=?",
                                    arrayOf<Any>(rec.id))
                            }
                            refreshKey++
                        }
                    }
                },
            )
        }

        // Catch-up sync progress / result dialog
        syncDialogState?.let { state ->
            CatchUpSyncDialog(
                state = state,
                onDismiss = {
                    if (!state.inProgress) syncDialogState = null
                },
            )
        }
    }
}

private data class SyncDialogState(
    val inProgress: Boolean,
    val done: Int,
    val total: Int,
    val cloudlogOk: Int,
    val qrzOk: Int,
    val cloudlogAttempted: Boolean,
    val qrzAttempted: Boolean,
    val finished: Boolean,
    val noServicesEnabled: Boolean,
)

@Composable
private fun StatsLoadingPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp),
                cornerRadius = 16.dp,
            )
            ShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp),
                cornerRadius = 16.dp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp),
                cornerRadius = 16.dp,
            )
            ShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(96.dp),
                cornerRadius = 16.dp,
            )
        }
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            cornerRadius = 16.dp,
        )
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            cornerRadius = 12.dp,
        )
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            cornerRadius = 16.dp,
        )
    }
}

// ---------------------------------------------------------------------------
// Segmented tab row
// ---------------------------------------------------------------------------

@Composable
private fun SegmentedTabRow(
    tabs: List<LogbookTab>,
    selected: LogbookTab,
    onSelected: (LogbookTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(BgSurface2, shape)
            .border(1.dp, Border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in tabs) {
            val isSelected = tab == selected
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) BgSurface3 else Color.Transparent,
                animationSpec = tween(200),
                label = "tabBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) Accent else TextMuted,
                animationSpec = tween(200),
                label = "tabText",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { onSelected(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.02.sp,
                )
            }
        }
    }
}

// ===========================================================================
// STATS TAB
// ===========================================================================

@Composable
private fun StatsTab(stats: LogbookStats, records: List<QSLCallsignRecord>) {
    // Animate charts in from 0 once on first render of this tab in this process lifecycle.
    var hasAnimated by rememberSaveable { mutableStateOf(false) }
    var animTarget by remember { mutableStateOf(if (hasAnimated) 1f else 0f) }
    val chartProgress by animateFloatAsState(
        targetValue = animTarget,
        animationSpec = tween(
            durationMillis = MotionTokens.DurXSlow,
            easing = MotionTokens.EasingEmphasizedDecel,
        ),
        label = "stats-tab-chart-progress",
    )
    LaunchedEffect(Unit) {
        if (!hasAnimated) {
            animTarget = 1f
            hasAnimated = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Big stat cards: 2-column grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BigStatCard(
                label = stringResource(R.string.log_stat_total_qsos),
                value = stats.totalQsos,
                accentColor = Accent,
                modifier = Modifier.weight(1f),
            )
            BigStatCard(
                label = stringResource(R.string.log_stat_dxcc_entities),
                value = stats.dxccEntities,
                accentColor = Signal,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BigStatCard(
                label = stringResource(R.string.log_stat_cq_zones),
                value = stats.cqZones,
                accentColor = StatusNew,
                modifier = Modifier.weight(1f),
            )
            BigStatCard(
                label = stringResource(R.string.log_stat_itu_zones),
                value = stats.ituZones,
                accentColor = Band17m,
                modifier = Modifier.weight(1f),
            )
        }

        // Band donut chart
        if (stats.bandCounts.isNotEmpty()) {
            SectionHeader(stringResource(R.string.log_section_band_distribution))
            BandDonutChart(
                bandCounts = stats.bandCounts,
                progress = chartProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Award progress bars
        SectionHeader(stringResource(R.string.log_section_award_progress))
        AwardProgressBar(
            label = stringResource(R.string.log_award_dxcc_mixed),
            current = stats.dxccEntities,
            total = 340,
            gradientColors = listOf(Signal, StatusConfirmed),
            progress = chartProgress,
        )
        AwardProgressBar(
            label = stringResource(R.string.log_award_vucc_grid_squares),
            current = gridSquaresWorked(records),
            total = 100,
            gradientColors = listOf(StatusNew, Band12m),
            progress = chartProgress,
        )
        AwardProgressBar(
            label = stringResource(R.string.log_award_dxcc_challenge),
            current = stats.dxccEntities * stats.bandCounts.size.coerceAtLeast(1),
            total = 1000,
            gradientColors = listOf(Accent, Band17m),
            progress = chartProgress,
        )

        // Grid square heatmap
        SectionHeader(stringResource(R.string.log_section_grid_coverage))
        GridSquareHeatmap(
            records = records,
            progress = chartProgress,
            modifier = Modifier.fillMaxWidth(),
        )

        // Signal trend sparkline
        SectionHeader(stringResource(R.string.log_section_signal_trend))
        SignalSparkline(
            records = records,
            progress = chartProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Big stat card
// ---------------------------------------------------------------------------

@Composable
private fun BigStatCard(
    label: String,
    value: Int,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            AnimatedCounter(
                value = value,
                style = MaterialTheme.typography.displayMedium.copy(
                    color = accentColor,
                    fontFamily = GeistMonoFamily,
                ),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.04.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Section header
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.06.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Band donut chart (Canvas)
// ---------------------------------------------------------------------------

@Composable
private fun BandDonutChart(
    bandCounts: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    val total = bandCounts.sumOf { it.second }.coerceAtLeast(1)
    val arcGap = 3f

    GlassCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Donut
            Canvas(
                modifier = Modifier.size(120.dp),
            ) {
                val strokeWidth = 18f
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f,
                )
                val arcSize = Size(diameter, diameter)

                var startAngle = -90f
                for ((band, count) in bandCounts) {
                    val sweep = ((count.toFloat() / total) * 360f - arcGap) * progress
                    if (sweep > 0f) {
                        drawArc(
                            color = bandColor(band),
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                    }
                    startAngle += sweep + arcGap
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Legend
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for ((band, count) in bandCounts.take(7)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(bandColor(band)),
                        )
                        Text(
                            text = band,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = GeistMonoFamily,
                        )
                        Text(
                            text = count.toString(),
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = GeistMonoFamily,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Award progress bar (gradient fill)
// ---------------------------------------------------------------------------

@Composable
private fun AwardProgressBar(
    label: String,
    current: Int,
    total: Int,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    val fraction = ((current.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)) * progress.coerceIn(0f, 1f)
    val trackShape = RoundedCornerShape(4.dp)

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "$current / $total",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(trackShape)
                    .background(BgSurface3),
            ) {
                // Fill
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(trackShape)
                        .background(
                            Brush.horizontalGradient(gradientColors),
                        ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Grid square coverage heatmap (18x10 field grid: AA..RR x 00..99 at field level)
// ---------------------------------------------------------------------------

@Composable
private fun GridSquareHeatmap(
    records: List<QSLCallsignRecord>,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    // Build set of worked 2-char field designators (e.g., "FN", "JO")
    val workedFields = remember(records) {
        records.mapNotNull { record ->
            val grid = record.grid
            if (grid != null && grid.length >= 2) {
                grid.substring(0, 2).uppercase()
            } else null
        }.toSet()
    }

    val cols = 18  // A..R
    val rows = 10  // 0..9 (latitude bands, typically A-R letters mapped, but for the field
                   // grid we show longitude letters across, latitude digits down)

    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            for (row in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (col in 0 until cols) {
                        val fieldLon = ('A' + col)
                        val fieldLat = ('A' + row)
                        val field = "$fieldLon$fieldLat"
                        val isWorked = field in workedFields

                        val cellColor = when {
                            isWorked -> Signal.copy(alpha = 0.7f * progress.coerceIn(0f, 1f))
                            else -> BgSurface3.copy(alpha = 0.4f)
                        }

                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(cellColor),
                        )
                    }
                }
                if (row < rows - 1) Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Signal trend sparkline (Canvas)
// ---------------------------------------------------------------------------

@Composable
private fun SignalSparkline(
    records: List<QSLCallsignRecord>,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
) {
    if (records.isEmpty()) {
        GlassCard(modifier = modifier) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.log_no_qsos_yet),
                    color = TextFaint,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                )
            }
        }
        return
    }

    // QSLCallsignRecord does not carry SNR, so we synthesize a coarse trend
    // from the per-record index. This is a visualization placeholder until
    // SNR is persisted on the QSO log row.
    val dataPoints = remember(records) {
        records.takeLast(30).mapIndexed { index, _ ->
            val base = -15f + (index % 20) * 1.2f
            base.coerceIn(-25f, 5f)
        }
    }

    GlassCard(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            val p = progress.coerceIn(0f, 1f)
            drawSparkline(
                dataPoints,
                Signal.copy(alpha = p),
                Signal.copy(alpha = 0.12f * p),
            )
        }
    }
}

private fun DrawScope.drawSparkline(
    data: List<Float>,
    lineColor: Color,
    fillColor: Color,
) {
    if (data.size < 2) return

    val minVal = data.min()
    val maxVal = data.max()
    val range = (maxVal - minVal).coerceAtLeast(1f)
    val w = size.width
    val h = size.height
    val stepX = w / (data.size - 1).toFloat()

    fun yOf(value: Float): Float = h - ((value - minVal) / range) * h

    // Build path
    val linePath = Path().apply {
        moveTo(0f, yOf(data[0]))
        for (i in 1 until data.size) {
            lineTo(i * stepX, yOf(data[i]))
        }
    }

    // Fill path
    val fillPath = Path().apply {
        addPath(linePath)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }

    drawPath(fillPath, fillColor)
    drawPath(
        linePath,
        lineColor,
        style = Stroke(width = 2f, cap = StrokeCap.Round),
    )
}

// ---------------------------------------------------------------------------
// Helper: count unique grid squares worked
// ---------------------------------------------------------------------------

private fun gridSquaresWorked(records: List<QSLCallsignRecord>): Int =
    records.mapNotNull { record ->
        val grid = record.grid
        if (!grid.isNullOrBlank() && grid.length >= 4) grid.substring(0, 4).uppercase() else null
    }.distinct().size

// ===========================================================================
// RECENT TAB
// ===========================================================================

@Composable
private fun RecentTab(
    records: List<QSLCallsignRecord>,
    onEdit: (QSLCallsignRecord) -> Unit,
    onDelete: (QSLCallsignRecord) -> Unit,
) {
    if (records.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyStateWaves(size = 180.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.log_no_qsos_recorded_yet),
                color = TextFaint,
                fontSize = 13.sp,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(
            items = records.reversed(),
            // Include id so an edit that changes other fields still maps to a stable key,
            // and so two grouped rows with otherwise identical display fields don't collide.
            key = { "${it.id}_${it.callsign}_${it.lastTime}_${it.band}" },
        ) { record ->
            QsoRow(
                record = record,
                onEdit = { onEdit(record) },
                onDelete = { onDelete(record) },
            )
        }

        // Bottom spacer for safe area
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ---------------------------------------------------------------------------
// QSO row
// ---------------------------------------------------------------------------

@Composable
private fun QsoRow(
    record: QSLCallsignRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val callsign = record.callsign ?: ""
    val grid = record.grid ?: ""
    val band = record.band ?: ""
    val time = record.lastTime ?: ""
    val dxcc = record.dxccStr ?: ""
    val context = LocalContext.current
    val state = UsStateLookup.stateFromGrid(context, grid)
    var menuOpen by remember { mutableStateOf(false) }

    // Show a status pill only when something positive has happened (worked or
    // LoTW-confirmed). New rows default to no badge — the chip area to the right
    // is where the sync indicators live.
    val status = when {
        record.isLotW_QSL -> QsoStatus.CONFIRMED
        record.isQSL -> QsoStatus.WORKED
        else -> null
    }

    // Build the secondary line entries (state takes precedence over DXCC when present
    // because for US contacts the DXCC string is always just "United States" and the
    // state is the more useful information).
    val stateUsaLabel = if (!state.isNullOrBlank())
        stringResource(R.string.log_state_usa, state) else null
    val secondaryParts = buildList {
        if (grid.isNotBlank()) add(grid to Signal)
        if (stateUsaLabel != null) {
            add(stateUsaLabel to TextMuted)
        } else if (dxcc.isNotBlank()) {
            add(dxcc to TextFaint)
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Time column
            Column(modifier = Modifier.width(52.dp)) {
                Text(
                    text = formatTime(time),
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = GeistMonoFamily,
                    maxLines = 1,
                )
                if (band.isNotBlank()) {
                    Text(
                        text = band.uppercase(),
                        color = bandColor(band),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistMonoFamily,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Callsign + grid + state/DX entity
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callsign,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    secondaryParts.forEach { (text, color) ->
                        Text(
                            text = text,
                            color = color,
                            fontSize = 10.sp,
                            fontFamily = GeistMonoFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status pill — only shown when worked or LoTW-confirmed; brand-new QSOs
            // get no badge to keep the row visually quiet.
            if (status != null) {
                StatusPill(status = status, compact = true)
            }

            // Sync-to-service indicator chips (independent of QSL state)
            SyncChips(
                cloudlog = record.syncedCloudlog,
                qrz = record.syncedQrz,
                cloudlogLabel = cloudlogFamilyLabel(GeneralVariables.cloudlogServerAddress),
            )

            // Overflow action menu (edit / delete)
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.log_cd_qso_actions),
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_action_edit), color = TextPrimary) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                tint = Accent,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_action_delete), color = StatusBad) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                tint = StatusBad,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sync-to-service chips ("CL" for Cloudlog/Wavelog/Nextlog, "QRZ" for QRZ)
// ---------------------------------------------------------------------------

@Composable
private fun SyncChips(cloudlog: Boolean, qrz: Boolean, cloudlogLabel: String) {
    if (!cloudlog && !qrz) return
    Row(
        modifier = Modifier.padding(start = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cloudlog) SyncChip(label = cloudlogLabel)
        if (qrz) SyncChip(label = "QRZ")
    }
}

// Cloudlog, Wavelog, and Nextlog share an upload API but identify differently in
// their hostnames. Pick the right short label from whatever the user configured.
private fun cloudlogFamilyLabel(serverAddress: String?): String {
    val host = serverAddress?.lowercase().orEmpty()
    return when {
        "wavelog" in host -> "WL"
        "nextlog" in host -> "NL"
        else -> "CL"
    }
}

@Composable
private fun SyncChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Signal.copy(alpha = 0.14f))
            .border(1.dp, Signal.copy(alpha = 0.32f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "↑ $label",
            color = Signal,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Format time helper: "20230320-143000" -> "14:30"
// ---------------------------------------------------------------------------

private fun formatTime(raw: String): String {
    if (raw.isBlank()) return "--:--"
    // Try to extract HH:MM from various formats
    val timepart = if ("-" in raw) raw.substringAfter("-") else raw
    return if (timepart.length >= 4) {
        "${timepart.substring(0, 2)}:${timepart.substring(2, 4)}"
    } else {
        raw.take(5)
    }
}

// ===========================================================================
// AWARDS TAB
// ===========================================================================

@Composable
private fun AwardsTab(stats: LogbookStats) {
    val dxccMixedName = stringResource(R.string.log_award_dxcc_mixed)
    val dxccMixedDesc = stringResource(R.string.log_award_dxcc_mixed_desc)
    val wasName = stringResource(R.string.log_award_was)
    val wasDesc = stringResource(R.string.log_award_was_desc)
    val wazName = stringResource(R.string.log_award_waz)
    val wazDesc = stringResource(R.string.log_award_waz_desc)
    val vuccName = stringResource(R.string.log_award_vucc)
    val vuccDesc = stringResource(R.string.log_award_vucc_desc)
    val iotaName = stringResource(R.string.log_award_iota)
    val iotaDesc = stringResource(R.string.log_award_iota_desc)
    val awards = remember(stats) {
        listOf(
            AwardProgress(
                name = dxccMixedName,
                description = dxccMixedDesc,
                current = stats.dxccEntities,
                total = 100,
                color = Signal,
            ),
            AwardProgress(
                name = wasName,
                description = wasDesc,
                current = (stats.dxccEntities * 50 / 340.coerceAtLeast(1)).coerceAtMost(50),
                total = 50,
                color = Accent,
            ),
            AwardProgress(
                name = wazName,
                description = wazDesc,
                current = stats.cqZones,
                total = 40,
                color = StatusNew,
            ),
            AwardProgress(
                name = vuccName,
                description = vuccDesc,
                current = 0, // Would need per-band grid counting
                total = 100,
                color = Band12m,
            ),
            AwardProgress(
                name = iotaName,
                description = iotaDesc,
                current = 0, // Not tracked in current DB
                total = 100,
                color = Band17m,
            ),
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(awards, key = { it.name }) { award ->
            AwardCard(award)
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Award card
// ---------------------------------------------------------------------------

@Composable
private fun AwardCard(award: AwardProgress) {
    val fraction = (award.current.toFloat() / award.total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val trackShape = RoundedCornerShape(4.dp)

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Icon circle
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(award.color.copy(alpha = 0.14f))
                    .border(1.dp, award.color.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = award.name.take(1),
                    color = award.color,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = award.name,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${award.current} / ${award.total}",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = GeistMonoFamily,
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = award.description,
                    color = TextFaint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(trackShape)
                        .background(BgSurface3),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(trackShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        award.color,
                                        award.color.copy(alpha = 0.6f),
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

// ===========================================================================
// Per-row QSO edit / delete dialogs (Recent tab)
// ===========================================================================

@Composable
private fun EditQsoDialog(
    record: QSLCallsignRecord,
    onDismiss: () -> Unit,
    onSave: (callsign: String, grid: String, mode: String) -> Unit,
) {
    var callsignInput by remember {
        mutableStateOf(TextFieldValue(record.callsign ?: ""))
    }
    var gridInput by remember {
        mutableStateOf(TextFieldValue(record.grid ?: ""))
    }
    var modeInput by remember {
        mutableStateOf(TextFieldValue(record.mode ?: ""))
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.log_edit_qso),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            OutlinedTextField(
                value = callsignInput,
                onValueChange = { callsignInput = it },
                label = { Text(stringResource(R.string.log_field_callsign)) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = gridInput,
                onValueChange = { gridInput = it },
                label = { Text(stringResource(R.string.log_field_grid_locator)) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 16.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = modeInput,
                onValueChange = { modeInput = it },
                label = { Text(stringResource(R.string.log_field_mode)) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 16.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        onSave(callsignInput.text, gridInput.text, modeInput.text)
                    },
                ) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DeleteQsoConfirm(
    record: QSLCallsignRecord,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val callsign = record.callsign ?: ""

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BgSurface2)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.log_delete_qso_title),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.06.sp,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (callsign.isNotBlank())
                    stringResource(R.string.log_delete_qso_body_callsign, callsign)
                else
                    stringResource(R.string.log_delete_qso_body),
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgSurface3)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(StatusBad)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.log_action_delete),
                        color = BgApp,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatchUpSyncDialog(
    state: SyncDialogState,
    onDismiss: () -> Unit,
) {
    val dismissOnOutside = !state.inProgress
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !state.inProgress,
            dismissOnClickOutside = dismissOnOutside,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BgSurface2)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            val title = when {
                state.noServicesEnabled -> stringResource(R.string.log_sync_title_no_services)
                state.inProgress -> stringResource(R.string.log_sync_title_syncing)
                else -> stringResource(R.string.log_sync_title_complete)
            }
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.06.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (state.noServicesEnabled) {
                Text(
                    text = stringResource(R.string.log_sync_enable_services),
                    color = TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            } else {
                val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.log_sync_progress_count, state.done, state.total),
                    color = TextMuted,
                    fontSize = 13.sp,
                )
                if (state.cloudlogAttempted) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.log_sync_cloudlog_accepted, state.cloudlogOk),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
                if (state.qrzAttempted) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.log_sync_qrz_accepted, state.qrzOk),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
                if (state.finished && state.total == 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.log_sync_nothing_to_upload),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (state.inProgress) BgSurface3 else Accent)
                    .let { m ->
                        if (state.inProgress) m else m.clickable(onClick = onDismiss)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.inProgress) stringResource(R.string.log_sync_working) else stringResource(R.string.action_done),
                    color = if (state.inProgress) TextMuted else BgApp,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
