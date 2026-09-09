package radio.ks3ckc.ft8af.ui.decode

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import com.k1af.ft8af.rigs.BaseRigOperation
import com.k1af.ft8af.timer.UtcTimer
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.FT8AFBottomSheet
import radio.ks3ckc.ft8af.ui.components.FT8AFIcons
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.QsoStatus
import radio.ks3ckc.ft8af.ui.components.StatusPill
import radio.ks3ckc.ft8af.ui.map.GrayLineDetail
import radio.ks3ckc.ft8af.ui.map.GrayLineDisplay
import radio.ks3ckc.ft8af.ui.map.GrayLinePhase
import radio.ks3ckc.ft8af.ui.map.UsStateOutlines
import radio.ks3ckc.ft8af.ui.map.WorldOutlines
import radio.ks3ckc.ft8af.ui.map.forEachRingVertex
import radio.ks3ckc.ft8af.ui.map.grayLineDisplay
import radio.ks3ckc.ft8af.ui.map.solarSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bottom sheet that displays full station details for a decoded message and
 * provides a "Call" action to initiate a QSO sequence.
 *
 * Sections:
 *  1. Station header: callsign avatar, callsign, status, location info
 *  2. Stat cards: Signal (SNR), Azimuth, Band
 *  3. QSO sequence visualizer (5 steps)
 *  4. "Call {callsign}" action button
 */
@Composable
fun QsoSheet(
    message: Ft8Message?,
    mainViewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FT8AFBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        if (message != null) {
            QsoSheetContent(
                message = message,
                mainViewModel = mainViewModel,
            )
        }
    }
}

@Composable
private fun QsoSheetContent(
    message: Ft8Message,
    mainViewModel: MainViewModel,
) {
    val callsign = message.callsignFrom ?: ""
    val status = resolveQsoStatus(message)
    val context = LocalContext.current
    val isTransmitting by mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observeAsState(false)
    val isActivated by mainViewModel.ft8TransmitSignal.mutableIsActivated.observeAsState(false)
    val toCallsign by mainViewModel.ft8TransmitSignal.mutableToCallsign.observeAsState()
    val txFunctionOrder by mainViewModel.ft8TransmitSignal.mutableFunctionOrder.observeAsState(6)

    val usState = UsStateLookup.stateFromGrid(context, message.maidenGrid)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // -- Station header --
        StationHeader(
            callsign = callsign,
            status = status,
            country = message.fromWhere,
            state = usState,
            grid = message.maidenGrid,
            onQrzClick = {
                try {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.qrz.com/db/${Uri.encode(callsign)}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            },
        )

        Spacer(modifier = Modifier.height(20.dp))

        // -- Stat cards: Signal, Distance, Azimuth, Band --
        StatCardsRow(message = message)

        Spacer(modifier = Modifier.height(20.dp))

        // -- Last heard: relative recency of this decode, above the map --
        LastHeardRow(utcTimeMillis = message.utcTime, mainViewModel = mainViewModel)

        // -- Gray line: is the DX at their sunrise/sunset right now, and how long
        // until they are? A propagation timing aid; renders nothing without a grid.
        GrayLineRow(grid = message.maidenGrid)

        Spacer(modifier = Modifier.height(12.dp))

        // -- Worked before: prior-QSO history with this station (renders nothing
        // for a never-worked station, so first contacts stay uncluttered) --
        WorkedBeforeCard(callsign = callsign)

        // -- Path map: operator grid -> remote grid, with a connecting line.
        // Renders nothing (and no trailing spacer) when either grid is unknown.
        QsoPathMap(
            callsign = callsign,
            theirGrid = message.maidenGrid,
            country = message.fromWhere,
            state = usState,
        )

        // -- QSO sequence visualizer --
        val liveQsoIsThisCallsign =
            isActivated && toCallsign?.callsign?.equals(callsign, ignoreCase = true) == true
        QsoSequenceVisualizer(
            callsign = callsign,
            message = message,
            isLiveQso = liveQsoIsThisCallsign,
            liveFunctionOrder = txFunctionOrder ?: 6,
            isTransmitting = isTransmitting,
        )

        // -- Current TX banner: always shows what's queued to transmit next --
        CurrentTxBanner(
            mainViewModel = mainViewModel,
            isActivated = isActivated,
            isTransmitting = isTransmitting,
        )

        // -- Live QSO status (contextual: TX'ing call / waiting for reply) --
        QsoLiveStatusRow(
            callsign = callsign,
            message = message,
            isLiveQso = liveQsoIsThisCallsign,
            isTransmitting = isTransmitting,
            liveFunctionOrder = txFunctionOrder ?: 6,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // -- Call button --
        val isQsoComplete = status == QsoStatus.WORKED || status == QsoStatus.CONFIRMED

        if (isQsoComplete) {
            // QSO complete banner
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x1A4ADE80))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    FT8AFIcons.Check(color = StatusConfirmed, size = 18.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.qso_complete),
                        color = StatusConfirmed,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    mainViewModel.callStation(message)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = BgApp,
                ),
                enabled = !isTransmitting,
            ) {
                FT8AFIcons.Transmit(color = BgApp, size = 18.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.qso_call_action, callsign),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// Station Header
// ---------------------------------------------------------------------------

@Composable
private fun StationHeader(
    callsign: String,
    status: QsoStatus?,
    country: String?,
    state: String?,
    grid: String?,
    onQrzClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Callsign avatar circle (QRZ profile image with initials fallback)
        QrzAvatar(
            callsign = callsign,
            size = 48.dp,
            fallbackText = callsign.take(2),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = callsign,
                    color = TextPrimary,
                    fontFamily = GeistMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                if (status != null) {
                    StatusPill(status = status, compact = true)
                }
            }

            // Location info: combine state + country + grid into a single sentence
            val locationText = formatLocationLine(state = state, country = country, grid = grid)
            if (!locationText.isNullOrEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FT8AFIcons.Globe(color = TextFaint, size = 12.dp)
                    Text(
                        text = locationText,
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            // QRZ link
            Row(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onQrzClick)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.qso_qrz_link),
                    color = Signal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                    letterSpacing = 0.04.sp,
                )
            }
        }
    }
}

/**
 * Build a human-readable single-line location label combining (in order):
 *   state, country (collapsed when redundant with state), grid.
 * Returns null when none of the inputs are usable.
 */
private fun formatLocationLine(
    state: String?,
    country: String?,
    grid: String?,
): String? {
    val countryClean = country?.trim()
    val isUs = countryClean?.contains("United States", ignoreCase = true) == true ||
        countryClean.equals("USA", ignoreCase = true)

    val placeText = when {
        !state.isNullOrEmpty() && isUs -> "$state, USA"
        !state.isNullOrEmpty() && countryClean.isNullOrEmpty() -> "$state, USA"
        !state.isNullOrEmpty() -> "$state, $countryClean"
        countryClean.isNullOrEmpty() -> null
        countryClean == "United States of America" -> "USA"
        countryClean == "United Kingdom" -> "UK"
        else -> countryClean
    }

    val parts = buildList {
        if (!placeText.isNullOrEmpty()) add(placeText)
        if (!grid.isNullOrEmpty()) add(grid)
    }
    return if (parts.isEmpty()) null else parts.joinToString(" \u2022 ")
}

// ---------------------------------------------------------------------------
// Stat Cards
// ---------------------------------------------------------------------------

@Composable
private fun StatCardsRow(message: Ft8Message) {
    val myGrid = GeneralVariables.getMyMaidenheadGrid()
    val theirGrid = message.maidenGrid ?: ""

    // Compute beam heading + distance from the operator's grid to the remote
    // station. The azimuth card leads with the short-path heading and carries the
    // long-path (reciprocal) heading as a subtitle, so DXers with a beam can see
    // both ways to point the antenna.
    val headings = computeBeamHeadings(myGrid, theirGrid)
    val azimuthText = headings?.let { formatHeading(it.shortPathDeg) } ?: "--"
    val longPathText = headings?.let {
        stringResource(R.string.qso_stat_azimuth_long_path, formatHeading(it.longPathDeg))
    }
    val distanceText = computeDistanceText(myGrid, theirGrid)

    // Derive band label from message carrier frequency
    val bandLabel = try {
        BaseRigOperation.getFrequencyAllInfo(message.band)
    } catch (_: Exception) {
        "--"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCard(
            label = stringResource(R.string.qso_stat_signal),
            value = if (message.hasSnr()) "${message.snr} dB" else "-- dB",
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.qso_stat_distance),
            value = distanceText,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.qso_stat_azimuth),
            value = azimuthText,
            subtitle = longPathText,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.qso_stat_band),
            value = bandLabel,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = TextFaint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.06.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = TextPrimary,
                fontFamily = GeistMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextFaint,
                    fontFamily = GeistMonoFamily,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// QSO Sequence Visualizer
// ---------------------------------------------------------------------------

private val QsoStepLabels = listOf("call", "report", "roger", "confirm", "73")

/**
 * Resolve which step (0..4) the QSO is currently on, or -1 for "not started".
 * Shared by the visualizer and the live status row so they cannot diverge.
 *
 * Just-viewing (no active QSO) always returns -1 so the sequence stays
 * grayed out until the operator commits via the Call button. Step state
 * only lights up for a live QSO with this callsign or for a previously
 * worked-and-confirmed call (which lights all 5 as complete).
 */
private fun computeCurrentStepIndex(
    message: Ft8Message,
    isLiveQso: Boolean,
    liveFunctionOrder: Int,
): Int {
    val isFullyComplete = !isLiveQso && (message.isQSL_Callsign ||
        GeneralVariables.checkQSLCallsign(message.getCallsignFrom()))
    return when {
        isFullyComplete -> 5
        isLiveQso -> when (liveFunctionOrder) {
            1 -> 0
            2 -> 1
            3 -> 2
            4 -> 3
            5 -> 4
            else -> -1
        }
        else -> -1
    }
}

@Composable
private fun QsoLiveStatusRow(
    callsign: String,
    message: Ft8Message,
    isLiveQso: Boolean,
    isTransmitting: Boolean,
    liveFunctionOrder: Int,
) {
    val text = when {
        isLiveQso && isTransmitting -> {
            val idx = computeCurrentStepIndex(message, true, liveFunctionOrder)
            val stepLabel = QsoStepLabels.getOrNull(idx) ?: stringResource(R.string.qso_live_step_fallback)
            stringResource(R.string.qso_live_sending, callsign, stepLabel)
        }
        isLiveQso && !isTransmitting -> stringResource(R.string.qso_live_waiting, callsign)
        !isLiveQso && isTransmitting -> stringResource(R.string.qso_live_transmitting)
        else -> null
    } ?: return

    Spacer(modifier = Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BgElev)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = Accent,
            fontFamily = GeistMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
    }
}

private data class QsoStep(
    val label: String,
    val txRxLabel: String,
    val messagePreview: String,
)

@Composable
private fun QsoSequenceVisualizer(
    callsign: String,
    message: Ft8Message,
    isLiveQso: Boolean,
    liveFunctionOrder: Int,
    isTransmitting: Boolean,
) {
    val myCall = GeneralVariables.myCallsign ?: ""
    val myGrid = GeneralVariables.getMyMaidenhead4Grid() ?: ""

    val snrDisplay = if (message.hasSnr()) "${message.snr}" else "--"

    val steps = listOf(
        QsoStep(
            label = stringResource(R.string.qso_step_send_call),
            txRxLabel = "TX",
            messagePreview = "$callsign $myCall $myGrid",
        ),
        QsoStep(
            label = stringResource(R.string.qso_step_report_sent),
            txRxLabel = "RX",
            messagePreview = "$myCall $callsign $snrDisplay",
        ),
        QsoStep(
            label = stringResource(R.string.qso_step_roger),
            txRxLabel = "TX",
            messagePreview = "$callsign $myCall R$snrDisplay",
        ),
        QsoStep(
            label = stringResource(R.string.qso_step_confirm),
            txRxLabel = "RX",
            messagePreview = "$myCall $callsign RR73",
        ),
        QsoStep(
            label = stringResource(R.string.qso_step_logged),
            txRxLabel = "--",
            messagePreview = "$callsign $myCall 73",
        ),
    )

    val currentStepIndex = computeCurrentStepIndex(message, isLiveQso, liveFunctionOrder)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = stringResource(R.string.qso_sequence_header),
            color = TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        // Always render every step; state is per-step.
        steps.forEachIndexed { index, step ->
            val isComplete = index < currentStepIndex
            val isCurrent = index == currentStepIndex && currentStepIndex in 0..4

            QsoStepRow(
                stepNumber = index + 1,
                step = step,
                isComplete = isComplete,
                isCurrent = isCurrent,
                pulse = isCurrent && (isLiveQso || isTransmitting),
                isLast = index == steps.lastIndex,
            )
        }
    }
}

@Composable
private fun QsoStepRow(
    stepNumber: Int,
    step: QsoStep,
    isComplete: Boolean,
    isCurrent: Boolean,
    pulse: Boolean,
    isLast: Boolean,
) {
    val stepColor = when {
        isComplete -> StatusConfirmed
        isCurrent -> Accent
        else -> TextDim
    }
    val textColor = when {
        isComplete -> TextMuted
        isCurrent -> TextPrimary
        else -> TextDim
    }
    val txRxColor = when (step.txRxLabel) {
        "TX" -> if (isComplete || isCurrent) StatusBad else TextDim
        "RX" -> if (isComplete || isCurrent) Signal else TextDim
        else -> TextDim
    }

    // Pulse animation: only when this is the active step in a live QSO.
    val pulseScale: Float
    val pulseAlpha: Float
    if (pulse) {
        val transition = rememberInfiniteTransition(label = "qso-step-pulse")
        pulseScale = transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse-scale",
        ).value
        pulseAlpha = transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse-alpha",
        ).value
    } else {
        pulseScale = 1f
        pulseAlpha = 0f
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Step indicator (number, pulsing dot, or check)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp),
        ) {
            Box(
                modifier = Modifier.size(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Pulse halo behind the indicator while active.
                if (pulse) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(Accent.copy(alpha = pulseAlpha)),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isComplete -> StatusConfirmed.copy(alpha = 0.18f)
                                isCurrent -> Accent.copy(alpha = 0.18f)
                                else -> BgSurface3
                            },
                        )
                        .border(
                            1.dp,
                            when {
                                isComplete -> StatusConfirmed.copy(alpha = 0.5f)
                                isCurrent -> Accent.copy(alpha = 0.6f)
                                else -> Border
                            },
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isComplete) {
                        FT8AFIcons.Check(color = StatusConfirmed, size = 12.dp)
                    } else {
                        Text(
                            text = "$stepNumber",
                            color = stepColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Connecting line between steps
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(12.dp)
                        .background(
                            if (isComplete) StatusConfirmed.copy(alpha = 0.3f) else Border,
                        ),
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = step.label,
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // TX/RX badge
                if (step.txRxLabel != "--") {
                    Text(
                        text = step.txRxLabel,
                        modifier = Modifier
                            .background(
                                txRxColor.copy(alpha = 0.12f),
                                RoundedCornerShape(3.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        color = txRxColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.06.sp,
                    )
                }
            }
            // Message preview (dimmer when neither current nor complete).
            Text(
                text = step.messagePreview,
                color = if (isComplete || isCurrent) TextDim else TextDim.copy(alpha = 0.55f),
                fontFamily = GeistMonoFamily,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Current TX Banner
// ---------------------------------------------------------------------------

@Composable
private fun CurrentTxBanner(
    mainViewModel: MainViewModel,
    isActivated: Boolean,
    isTransmitting: Boolean,
) {
    val functions by mainViewModel.ft8TransmitSignal.mutableFunctions.observeAsState(arrayListOf())
    val functionOrder by mainViewModel.ft8TransmitSignal.mutableFunctionOrder.observeAsState(6)

    if (!isActivated) return

    val currentFn = functions?.firstOrNull { it.functionOrder == functionOrder } ?: return
    val messageText = currentFn.functionMessage?.takeIf { it.isNotBlank() } ?: return

    val labelText = if (isTransmitting) stringResource(R.string.qso_tx_now) else stringResource(R.string.qso_tx_next)
    val labelColor = if (isTransmitting) StatusBad else Accent

    Spacer(modifier = Modifier.height(14.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(labelColor.copy(alpha = 0.08f))
            .border(1.dp, labelColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = labelText,
            color = labelColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.sp,
            modifier = Modifier
                .background(labelColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = messageText,
            color = TextPrimary,
            fontFamily = GeistMonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// "Last heard" relative time
// ---------------------------------------------------------------------------

/**
 * Bucketed relative age of a decode, used for the "Last heard" line above the
 * map. Keeping the bucketing as a pure sealed result (rather than a formatted
 * string) lets [computeLastHeard] be unit-tested without Compose/resources and
 * keeps the "ago" wording localizable in [lastHeardText].
 */
internal sealed interface LastHeard {
    /** No usable timestamp (missing / non-positive). */
    object Unknown : LastHeard

    /** Under 5 s old, or a future timestamp clamped forward. */
    object JustNow : LastHeard

    data class Seconds(val value: Long) : LastHeard
    data class Minutes(val value: Long) : LastHeard
    data class Hours(val value: Long) : LastHeard
    data class Days(val value: Long) : LastHeard
}

/**
 * Bucket the elapsed time between [utcTimeMillis] (when the station was heard)
 * and [nowMillis] into a [LastHeard] value. Edge cases handled cleanly:
 *  - a missing/non-positive timestamp -> [LastHeard.Unknown]
 *  - a future timestamp (clock skew) -> [LastHeard.JustNow] (never negative)
 *  - a very old timestamp -> a plain [LastHeard.Days] count
 *
 * The bucket *thresholds* (5 s / 60 s / 60 m / 24 h) mirror the existing
 * decode-row "ago" formatter so recency reads consistently across the app; the
 * displayed wording is independent (e.g. this bucket says "just now" where the
 * decode row says "now") and lives in [lastHeardText].
 */
internal fun computeLastHeard(utcTimeMillis: Long, nowMillis: Long): LastHeard {
    if (utcTimeMillis <= 0L) return LastHeard.Unknown
    val elapsedMs = nowMillis - utcTimeMillis
    val seconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 5L -> LastHeard.JustNow
        seconds < 60L -> LastHeard.Seconds(seconds)
        seconds < 3600L -> LastHeard.Minutes(seconds / 60L)
        seconds < 86_400L -> LastHeard.Hours(seconds / 3600L)
        else -> LastHeard.Days(seconds / 86_400L)
    }
}

/** Map a [LastHeard] bucket to its localized display string. */
@Composable
private fun lastHeardText(lastHeard: LastHeard): String = when (lastHeard) {
    LastHeard.Unknown -> stringResource(R.string.qso_last_heard_unknown)
    LastHeard.JustNow -> stringResource(R.string.qso_last_heard_just_now)
    is LastHeard.Seconds -> stringResource(R.string.qso_last_heard_seconds, lastHeard.value)
    is LastHeard.Minutes -> stringResource(R.string.qso_last_heard_minutes, lastHeard.value)
    is LastHeard.Hours -> stringResource(R.string.qso_last_heard_hours, lastHeard.value)
    is LastHeard.Days -> stringResource(R.string.qso_last_heard_days, lastHeard.value)
}

/**
 * "Last heard" row shown above the path map. The app's ticking UTC clock
 * (`MainViewModel.timerSec`) is observed *here* rather than in the parent sheet:
 * it changes ~1×/s, and the sheet also hosts `QsoPathMap`, which redraws on every
 * recompose — observing the tick at the sheet level would repaint the whole map
 * each second, so the observation is scoped to just this row.
 */
@Composable
private fun LastHeardRow(utcTimeMillis: Long, mainViewModel: MainViewModel) {
    val nowMillis by mainViewModel.timerSec.observeAsState(UtcTimer.getSystemTime())
    val valueText = lastHeardText(computeLastHeard(utcTimeMillis, nowMillis))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.qso_last_heard),
            color = TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.06.sp,
        )
        Text(
            text = valueText,
            color = TextPrimary,
            fontFamily = GeistMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

/**
 * Gray-line / sun status for the tapped station. Shows whether the DX is in
 * daylight, night, or on the gray line right now, plus a countdown to their next
 * sunrise/sunset — the moments HF propagation to them is briefly enhanced.
 *
 * Renders nothing when the station's grid is unknown (nothing to place on the
 * globe). The snapshot is computed once when the sheet opens: the state changes
 * over minutes, and rescanning a 26-hour window every second would be wasteful,
 * so we deliberately do not observe the ticking clock here.
 */
@Composable
private fun GrayLineRow(grid: String?) {
    val latLon = remember(grid) { gridToLatLon(grid) } ?: return
    val display = remember(grid) {
        grayLineDisplay(solarSnapshot(latLon.first, latLon.second, UtcTimer.getSystemTime()))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.qso_grayline_label),
            color = TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.06.sp,
        )
        val onGrayLine = display.phase == GrayLinePhase.GRAY_LINE
        Text(
            text = grayLineStatusText(display),
            color = if (onGrayLine) Accent else TextPrimary,
            fontFamily = GeistMonoFamily,
            fontWeight = if (onGrayLine) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

/** Map [GrayLineDisplay] tokens to a localized "Daylight · sunset in 1h 20m" line. */
@Composable
private fun grayLineStatusText(display: GrayLineDisplay): String {
    val phaseText = when (display.phase) {
        GrayLinePhase.GRAY_LINE -> stringResource(R.string.qso_grayline_now)
        GrayLinePhase.DAYLIGHT -> stringResource(R.string.qso_grayline_daylight)
        GrayLinePhase.NIGHT -> stringResource(R.string.qso_grayline_night)
    }
    val detailText = when (display.detail) {
        // An empty countdown flags the "happening now" case: use a grammatical,
        // localized "at sunrise/sunset" string instead of "sunrise in now".
        GrayLineDetail.SUNRISE ->
            if (display.countdown.isEmpty()) stringResource(R.string.qso_grayline_sunrise_now)
            else stringResource(R.string.qso_grayline_sunrise_in, display.countdown)
        GrayLineDetail.SUNSET ->
            if (display.countdown.isEmpty()) stringResource(R.string.qso_grayline_sunset_now)
            else stringResource(R.string.qso_grayline_sunset_in, display.countdown)
        GrayLineDetail.MIDNIGHT_SUN -> stringResource(R.string.qso_grayline_midnight_sun)
        GrayLineDetail.POLAR_NIGHT -> stringResource(R.string.qso_grayline_polar_night)
    }
    return "$phaseText · $detailText"
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Distance from the operator's grid to the remote station's grid, formatted in
 * the user's preferred unit (mi/km). Returns "--" when either grid is unknown.
 */
internal fun computeDistanceText(myGrid: String?, theirGrid: String?): String {
    if (myGrid.isNullOrEmpty() || theirGrid.isNullOrEmpty()) return "--"
    // Go through lat/lng so a genuine 0-distance (identical grids) formats as
    // "0 mi/km" rather than "--"; only unparseable grids fall back to "--".
    // (MaidenheadGrid.getDistStr collapses both cases to an empty string.)
    return try {
        val myLatLng = MaidenheadGrid.gridToLatLng(myGrid) ?: return "--"
        val theirLatLng = MaidenheadGrid.gridToLatLng(theirGrid) ?: return "--"
        MaidenheadGrid.getDistLatLngStr(myLatLng, theirLatLng)
    } catch (_: Exception) {
        "--"
    }
}

/** Decode a Maidenhead grid to (lat, lon); null if the grid is blank/invalid. */
internal fun gridToLatLon(grid: String?): Pair<Double, Double>? {
    if (grid.isNullOrEmpty()) return null
    return try {
        val ll = MaidenheadGrid.gridToLatLng(grid) ?: return null
        Pair(ll.latitude, ll.longitude)
    } catch (_: Exception) {
        null
    }
}

// ---------------------------------------------------------------------------
// QSO path map (operator grid -> remote grid)
// ---------------------------------------------------------------------------

/**
 * Compact equirectangular map auto-zoomed to frame the operator's grid and the
 * remote station's grid, with a dashed line connecting the two. Renders nothing
 * (and no trailing spacer) when either grid is unavailable.
 */
@Composable
private fun QsoPathMap(
    callsign: String,
    theirGrid: String?,
    country: String?,
    state: String?,
) {
    val myGrid = GeneralVariables.getMyMaidenheadGrid()
    val me = remember(myGrid) { gridToLatLon(myGrid) }
    val them = remember(theirGrid) { gridToLatLon(theirGrid) }
    if (me == null || them == null) return

    val context = LocalContext.current
    val landRings by produceState<List<FloatArray>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { WorldOutlines.load(context) }
    }
    val stateRings by produceState<List<FloatArray>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { UsStateOutlines.load(context) }
    }
    val myCall = GeneralVariables.myCallsign?.trim().orEmpty()

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .qsoPath(
                        landRings = landRings,
                        stateRings = stateRings,
                        myLat = me.first,
                        myLon = me.second,
                        theirLat = them.first,
                        theirLon = them.second,
                        myColor = Accent,
                        theirColor = Signal,
                        myLabel = myCall.ifEmpty { null },
                        theirLabel = callsign,
                    ),
            )

            // DX country / state footer (falls back to grid only).
            val place = formatLocationLine(state = state, country = country, grid = theirGrid)
            if (!place.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FT8AFIcons.Globe(color = TextFaint, size = 12.dp)
                    Text(
                        text = place,
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))
}

/**
 * Equirectangular QSO path map drawn via [drawWithCache]: the (large) land + US
 * state ring sets are projected into screen-space [Path]s ONCE per
 * size/endpoint/ring change in the cache phase, so the per-frame draw — which
 * fires on every recompose and every frame of the sheet's slide-in animation —
 * only issues `drawPath` calls instead of rebuilding the whole geometry.
 */
private fun Modifier.qsoPath(
    landRings: List<FloatArray>?,
    stateRings: List<FloatArray>?,
    myLat: Double,
    myLon: Double,
    theirLat: Double,
    theirLon: Double,
    myColor: Color,
    theirColor: Color,
    myLabel: String?,
    theirLabel: String,
): Modifier = drawWithCache {
    // Equirectangular framing of the two endpoints (pure, unit-tested geometry
    // in QsoPathProjection — handles antemeridian normalization + uniform scale).
    val proj = QsoPathProjection(myLat, myLon, theirLat, theirLon, size.width, size.height)
    val tLon = proj.normalizedTheirLon

    val landPath = landRings?.let { buildRingPath(it, proj) }
    val statePath = stateRings?.let { buildRingPath(it, proj) }

    val myX = proj.projectX(myLon)
    val myY = proj.projectY(myLat)
    val tX = proj.projectX(tLon)
    val tY = proj.projectY(theirLat)

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

        drawLine(
            color = Accent.copy(alpha = 0.75f),
            start = Offset(myX, myY),
            end = Offset(tX, tY),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)),
        )

        drawStationDot(myX, myY, myColor)
        drawStationDot(tX, tY, theirColor)

        myLabel?.let { drawMapLabel(it, myX, myY, myColor) }
        drawMapLabel(theirLabel, tX, tY, theirColor)
    }
}

/**
 * Build a closed [Path] from polygon rings, repeated at -360/0/+360 lon offsets
 * so continents/states that wrap across the view's edge (e.g. trans-Pacific
 * paths, or the Aleutians) still appear.
 */
private fun buildRingPath(rings: List<FloatArray>, proj: QsoPathProjection): Path {
    val path = Path()
    for (off in doubleArrayOf(-360.0, 0.0, 360.0)) {
        for (ring in rings) {
            val plotted = forEachRingVertex(ring) { lon, lat, first ->
                val x = proj.projectX(lon.toDouble() + off)
                val y = proj.projectY(lat.toDouble())
                if (first) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (plotted) path.close()
        }
    }
    return path
}

private fun DrawScope.drawStationDot(x: Float, y: Float, color: Color) {
    drawCircle(color = color.copy(alpha = 0.22f), radius = 9f, center = Offset(x, y))
    drawCircle(color = color, radius = 4.5f, center = Offset(x, y))
    drawCircle(color = BgApp, radius = 4.5f, center = Offset(x, y), style = Stroke(width = 1.3f))
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
