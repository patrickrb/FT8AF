package radio.ks3ckc.ft8af.ui.logbook

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.log.OnShareLogEvents
import com.k1af.ft8af.log.ShareLogs
import com.k1af.ft8af.ui.ToastMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.AccentSoft
import radio.ks3ckc.ft8af.theme.BgApp
import radio.ks3ckc.ft8af.theme.BgSurface3
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.FT8AFBottomSheet

private enum class ExportPhase { IDLE, WORKING, FAILED }

@Composable
fun ExportLogSheet(
    visible: Boolean,
    mainViewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    FT8AFBottomSheet(visible = visible, onDismiss = onDismiss) {
        ExportLogSheetContent(
            mainViewModel = mainViewModel,
            visible = visible,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ExportLogSheetContent(
    mainViewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val queryKey = mainViewModel.queryKey ?: ""
    val queryFilter = mainViewModel.queryFilter

    // Reusable ShareLogs instance — we hold a single one so cancellation flips
    // the right flag regardless of which action is in flight.
    val shareLogs = remember { ShareLogs() }

    var dateStart by remember { mutableStateOf("") }
    var dateEnd by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(ExportPhase.IDLE) }
    var progressText by remember { mutableStateOf("") }
    var progressMax by remember { mutableIntStateOf(1) }
    var progressValue by remember { mutableIntStateOf(0) }

    // Recompute matching count whenever the date filters change. Cheap query.
    val recordCount = remember(dateStart, dateEnd, queryKey, queryFilter) {
        runCatching {
            shareLogs.getCount(
                mainViewModel.databaseOpr.db,
                queryKey,
                queryFilter,
                dateStart.ifBlank { null },
                dateEnd.ifBlank { null },
            )
        }.getOrDefault(0)
    }

    // If the sheet is dismissed mid-export, cancel the background work.
    DisposableEffect(visible) {
        onDispose {
            if (phase == ExportPhase.WORKING) shareLogs.cancelShare()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 24.dp),
    ) {
        // -- Header --
        Text(
            text = stringResource(R.string.export_title),
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.06.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SummaryLine(count = recordCount, queryKey = queryKey, queryFilter = queryFilter)

        Spacer(modifier = Modifier.height(20.dp))

        // -- Date range --
        Text(
            text = stringResource(R.string.export_date_range),
            color = TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.08.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(
                value = dateStart,
                onValueChange = { dateStart = it.filter { ch -> ch.isDigit() }.take(8) },
                placeholder = stringResource(R.string.export_date_from_placeholder),
                enabled = phase != ExportPhase.WORKING,
                modifier = Modifier.weight(1f),
            )
            DateField(
                value = dateEnd,
                onValueChange = { dateEnd = it.filter { ch -> ch.isDigit() }.take(8) },
                placeholder = stringResource(R.string.export_date_to_placeholder),
                enabled = phase != ExportPhase.WORKING,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // -- Progress / status row (visible while exporting or after failure) --
        AnimatedVisibility(visible = phase != ExportPhase.IDLE) {
            Column {
                ProgressBar(
                    value = progressValue,
                    max = progressMax,
                    failed = phase == ExportPhase.FAILED,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progressText,
                    color = if (phase == ExportPhase.FAILED) StatusBad else TextMuted,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // -- Action buttons --
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryActionButton(
                label = stringResource(R.string.action_save),
                enabled = phase != ExportPhase.WORKING,
                modifier = Modifier.weight(1f),
                onClick = {
                    startSave(
                        context = context,
                        mainViewModel = mainViewModel,
                        shareLogs = shareLogs,
                        dateStart = dateStart.ifBlank { null },
                        dateEnd = dateEnd.ifBlank { null },
                        onStateChange = { newPhase, msg, value, max ->
                            phase = newPhase
                            if (msg != null) progressText = msg
                            if (value != null) progressValue = value
                            if (max != null) progressMax = max
                        },
                        onDone = onDismiss,
                    )
                },
            )
            PrimaryActionButton(
                label = stringResource(R.string.export_share),
                enabled = phase != ExportPhase.WORKING,
                modifier = Modifier.weight(1f),
                onClick = {
                    startShare(
                        context = context,
                        mainViewModel = mainViewModel,
                        shareLogs = shareLogs,
                        dateStart = dateStart.ifBlank { null },
                        dateEnd = dateEnd.ifBlank { null },
                        onStateChange = { newPhase, msg, value, max ->
                            phase = newPhase
                            if (msg != null) progressText = msg
                            if (value != null) progressValue = value
                            if (max != null) progressMax = max
                        },
                        onDone = onDismiss,
                    )
                },
            )
        }

        // Reset transient state on each open so a previous failed run does
        // not bleed into the next session.
        LaunchedEffect(visible) {
            if (visible) {
                phase = ExportPhase.IDLE
                progressText = ""
                progressValue = 0
                progressMax = 1
            }
        }
    }
}

@Composable
private fun SummaryLine(count: Int, queryKey: String, queryFilter: Int) {
    val filterLabel = when (queryFilter) {
        1 -> stringResource(R.string.export_filter_confirmed)
        2 -> stringResource(R.string.export_filter_unconfirmed)
        else -> null
    }
    val recordWord = if (count == 1) stringResource(R.string.export_record_singular)
        else stringResource(R.string.export_record_plural)
    val keyLabel = if (queryKey.isBlank()) stringResource(R.string.export_summary_all)
        else stringResource(R.string.export_summary_key, queryKey)
    val suffix = filterLabel?.let { stringResource(R.string.export_summary_suffix, it) } ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.export_summary, count, recordWord, keyLabel, suffix),
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = GeistMonoFamily,
        )
    }
}

@Composable
private fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface3)
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = TextFaint,
                fontSize = 12.sp,
                fontFamily = GeistMonoFamily,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 12.sp,
                fontFamily = GeistMonoFamily,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProgressBar(value: Int, max: Int, failed: Boolean) {
    val fraction = (value.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 180),
        label = "export-progress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(BgSurface3),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (failed) StatusBad else Accent),
        )
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Accent else AccentSoft)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) BgApp else TextFaint,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SecondaryActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface3)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextFaint,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Export orchestration
// ---------------------------------------------------------------------------

private fun startShare(
    context: Context,
    mainViewModel: MainViewModel,
    shareLogs: ShareLogs,
    dateStart: String?,
    dateEnd: String?,
    onStateChange: (ExportPhase, String?, Int?, Int?) -> Unit,
    onDone: () -> Unit,
) {
    val adi = makeTempAdi(context) ?: return
    onStateChange(ExportPhase.WORKING, context.getString(R.string.export_preparing), 0, 1)

    Thread {
        shareLogs.doShareLogs(
            context,
            adi,
            context.getString(R.string.export_share_chooser_title),
            mainViewModel.databaseOpr.db,
            mainViewModel.queryKey ?: "",
            mainViewModel.queryFilter,
            dateStart,
            dateEnd,
            adi,
            false,
            progressEvents(onStateChange, onComplete = onDone),
        )
    }.start()
}

private fun startSave(
    context: Context,
    mainViewModel: MainViewModel,
    shareLogs: ShareLogs,
    dateStart: String?,
    dateEnd: String?,
    onStateChange: (ExportPhase, String?, Int?, Int?) -> Unit,
    onDone: () -> Unit,
) {
    val adi = makeTempAdi(context) ?: return
    onStateChange(ExportPhase.WORKING, context.getString(R.string.export_preparing), 0, 1)

    val displayName = "ft8af-log-" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
        ".adi"

    Thread {
        shareLogs.downQSLTableToFile(
            mainViewModel.databaseOpr.db,
            mainViewModel.queryKey ?: "",
            mainViewModel.queryFilter,
            dateStart,
            dateEnd,
            adi,
            false,
            progressEvents(
                onStateChange = onStateChange,
                onComplete = {
                    val result = ShareLogs.saveToDownloads(
                        context.applicationContext,
                        adi,
                        displayName,
                    )
                    if (result != null) {
                        ToastMessage.show(context.getString(R.string.export_saved_to, result))
                    } else {
                        ToastMessage.show(context.getString(R.string.export_save_failed))
                    }
                    onDone()
                },
            ),
        )
    }.start()
}

private fun makeTempAdi(context: Context): File? {
    val adi = GeneralVariables.writeToTempFile(context, "FT8AF-", ".adi", "")
    if (adi == null) {
        ToastMessage.show(context.getString(R.string.export_temp_file_failed))
    }
    return adi
}

private fun progressEvents(
    onStateChange: (ExportPhase, String?, Int?, Int?) -> Unit,
    onComplete: () -> Unit,
): OnShareLogEvents = object : OnShareLogEvents {
    override fun onPreparing(info: String) {
        onStateChange(ExportPhase.WORKING, info, null, null)
    }

    override fun onShareStart(count: Int, info: String) {
        onStateChange(ExportPhase.WORKING, info, 0, count.coerceAtLeast(1))
    }

    override fun onShareProgress(count: Int, position: Int, info: String): Boolean {
        onStateChange(ExportPhase.WORKING, info, position, count.coerceAtLeast(1))
        return true
    }

    override fun afterGet(count: Int, info: String) {
        val safeCount = count.coerceAtLeast(1)
        onStateChange(ExportPhase.WORKING, info, safeCount, safeCount)
        onComplete()
    }

    override fun onShareFailed(info: String) {
        onStateChange(ExportPhase.FAILED, info, null, null)
    }
}
