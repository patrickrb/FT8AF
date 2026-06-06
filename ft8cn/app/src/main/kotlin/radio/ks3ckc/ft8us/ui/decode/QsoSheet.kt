package radio.ks3ckc.ft8us.ui.decode

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bg7yoz.ft8cn.Ft8Message
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid
import com.bg7yoz.ft8cn.rigs.BaseRigOperation
import com.bg7yoz.ft8cn.timer.UtcTimer
import radio.ks3ckc.ft8us.theme.*
import radio.ks3ckc.ft8us.ui.components.FT8USBottomSheet
import radio.ks3ckc.ft8us.ui.components.FT8USIcons
import radio.ks3ckc.ft8us.ui.components.GlassCard
import radio.ks3ckc.ft8us.ui.components.QsoStatus
import radio.ks3ckc.ft8us.ui.components.StatusPill

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
    FT8USBottomSheet(
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

        // -- Stat cards: Signal, Azimuth, Band --
        StatCardsRow(message = message)

        Spacer(modifier = Modifier.height(20.dp))

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
                    FT8USIcons.Check(color = StatusConfirmed, size = 18.dp)
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
                FT8USIcons.Transmit(color = BgApp, size = 18.dp)
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
                    FT8USIcons.Globe(color = TextFaint, size = 12.dp)
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

    // Compute azimuth
    val azimuthText = computeAzimuthText(myGrid, theirGrid)

    // Derive band label from message carrier frequency
    val bandLabel = try {
        BaseRigOperation.getFrequencyAllInfo(message.band)
    } catch (_: Exception) {
        "--"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatCard(
            label = stringResource(R.string.qso_stat_signal),
            value = "${message.snr} dB",
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.qso_stat_azimuth),
            value = azimuthText,
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
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
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
    val isFullyComplete = message.isQSL_Callsign && !isLiveQso
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

    val steps = listOf(
        QsoStep(
            label = stringResource(R.string.qso_step_send_call),
            txRxLabel = "TX",
            messagePreview = "$callsign $myCall $myGrid",
        ),
        QsoStep(
            label = stringResource(R.string.qso_step_report_sent),
            txRxLabel = "RX",
            messagePreview = "$myCall $callsign ${message.snr}",
        ),
        QsoStep(
            label = stringResource(R.string.qso_step_roger),
            txRxLabel = "TX",
            messagePreview = "$callsign $myCall R${message.snr}",
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
                        FT8USIcons.Check(color = StatusConfirmed, size = 12.dp)
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
// Helpers
// ---------------------------------------------------------------------------

/**
 * Compute the bearing/azimuth (in degrees) from the operator's grid to the
 * remote station's grid. Returns "--" if either grid is unavailable.
 */
private fun computeAzimuthText(myGrid: String?, theirGrid: String?): String {
    if (myGrid.isNullOrEmpty() || theirGrid.isNullOrEmpty()) return "--"
    return try {
        val myLatLng = MaidenheadGrid.gridToLatLng(myGrid) ?: return "--"
        val theirLatLng = MaidenheadGrid.gridToLatLng(theirGrid) ?: return "--"

        val lat1 = Math.toRadians(myLatLng.latitude)
        val lat2 = Math.toRadians(theirLatLng.latitude)
        val dLon = Math.toRadians(theirLatLng.longitude - myLatLng.longitude)

        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val bearing = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
        "${String.format("%.0f", bearing)}\u00B0"
    } catch (_: Exception) {
        "--"
    }
}
