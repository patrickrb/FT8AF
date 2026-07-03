package radio.ks3ckc.ft8af.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.*

/** Maps a [MeterZone] to its bar/readout color. */
private fun zoneColor(zone: MeterZone): Color =
    when (zone) {
        MeterZone.IDLE -> TextFaint
        MeterZone.NEUTRAL -> Signal
        MeterZone.GOOD -> StatusConfirmed
        MeterZone.CAUTION -> StatusWarn
        MeterZone.DANGER -> StatusBad
    }

/** The localized short label for each meter type. */
@Composable
private fun meterTypeLabel(type: MeterType): String =
    stringResource(
        when (type) {
            MeterType.SWR -> R.string.meters_swr
            MeterType.ALC -> R.string.meters_alc
            MeterType.POWER -> R.string.meters_power
            MeterType.SMETER -> R.string.meters_smeter
            MeterType.VOLTAGE -> R.string.meters_voltage
            MeterType.TEMP -> R.string.meters_temp
        },
    )

/**
 * Pull-down meters HUD. Shows the meters the connected rig reports back —
 * universally ALC and SWR, plus power / S-meter / voltage / temperature on rigs
 * that stream them (e.g. FlexRadio/Xiegu network). Which of the available meters
 * appear is controlled per-meter in settings (SWR + ALC on by default). Meters
 * are only measured while keyed, so the readout is live during TX and labelled
 * "last TX" otherwise. Opened by a top-edge swipe-down ([TopEdgeMetersTrigger]).
 */
@Composable
fun MetersSheet(
    visible: Boolean,
    mainViewModel: MainViewModel,
    isTransmitting: Boolean,
    onDismiss: () -> Unit,
) {
    // Poll a few times a second while the sheet is open so live rig meters
    // refresh. Reading [frame] here (the render scope) — and passing it down — is
    // what makes the bars actually update; polling inside the sampler would only
    // recompose the sampler, not these bars. Idle (no recomposition) when hidden.
    val frame by produceState(0L, visible) {
        if (visible) {
            while (true) {
                delay(250)
                value++
            }
        }
    }
    val available = rememberRigMeterSamples(mainViewModel, frame, active = visible)
    val enabled =
        enabledMeterTypes(
            swr = GeneralVariables.meterShowSwr,
            alc = GeneralVariables.meterShowAlc,
            power = GeneralVariables.meterShowPower,
            smeter = GeneralVariables.meterShowSMeter,
            voltage = GeneralVariables.meterShowVoltage,
            temp = GeneralVariables.meterShowTemp,
        )
    val visibleSamples = visibleMeters(available, enabled)
    val freshness = meterFreshness(isTransmitting, hasData = available.isNotEmpty())

    MetersTopSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.meters_title),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.06.sp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                FreshnessBadge(freshness)
            }

            // TX power control — only on rigs whose power is settable from the app
            // (FlexRadio). Shown above the meters so set-power and the live PWR
            // meter read together. Independent of meter availability: you set
            // power before keying up.
            if (rememberRigSupportsTxPower(mainViewModel)) {
                Spacer(modifier = Modifier.height(16.dp))
                TxPowerControl(mainViewModel)
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                // The rig hasn't reported any meters (unsupported, or no TX yet).
                available.isEmpty() -> HintText(stringResource(R.string.meters_no_data))
                // Rig reports meters but the user has hidden all the available ones.
                visibleSamples.isEmpty() -> HintText(stringResource(R.string.meters_all_hidden))
                else ->
                    visibleSamples.forEachIndexed { index, sample ->
                        if (index > 0) Spacer(modifier = Modifier.height(14.dp))
                        MeterRow(
                            label = meterTypeLabel(sample.type),
                            valueText = sample.text,
                            fraction = sample.fraction,
                            zone = sample.zone,
                            dim = freshness == MeterFreshness.NONE,
                        )
                    }
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 11.sp,
        fontFamily = GeistMonoFamily,
    )
}

/**
 * Adjustable TX-power row (network rigs). The label tracks the slider live; the
 * new value is pushed to the rig and persisted on release ([setRigTxPowerWatts]),
 * not on every drag tick, to avoid spamming RFPOWER over the network.
 */
@Composable
private fun TxPowerControl(mainViewModel: MainViewModel) {
    var watts by remember { mutableIntStateOf(currentTxPowerWatts()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.meters_tx_power),
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.08.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$watts W",
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        IntSlider(
            value = watts,
            onValueChange = { watts = it },
            onValueChangeFinished = { setRigTxPowerWatts(mainViewModel, watts) },
            valueRange = 0f..MAX_TX_POWER_WATTS.toFloat(),
            thumbColor = Accent,
            activeTrackColor = Accent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FreshnessBadge(freshness: MeterFreshness) {
    val (text, color) =
        when (freshness) {
            MeterFreshness.LIVE -> stringResource(R.string.meters_live) to StatusConfirmed
            MeterFreshness.LAST_TX -> stringResource(R.string.meters_last_tx) to TextMuted
            MeterFreshness.NONE -> return
        }
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = GeistMonoFamily,
        letterSpacing = 0.10.sp,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun MeterRow(
    label: String,
    valueText: String,
    fraction: Float,
    zone: MeterZone,
    dim: Boolean,
) {
    val barColor = if (dim) TextDim else zoneColor(zone)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (dim) TextFaint else TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.08.sp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                color = if (dim) TextFaint else TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        // Bar track + fill
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(BgSurface3),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(barColor),
            )
        }
    }
}

/**
 * Top-anchored sheet scaffold — the mirror of [FT8AFBottomSheet]: it slides in
 * from the top edge, rounds its bottom corners, and is dismissed by a drag UP,
 * a scrim tap, or Back. Kept local to the meters HUD so the well-tested bottom
 * sheet stays untouched.
 */
@Composable
private fun MetersTopSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetState = remember { MutableTransitionState(visible) }
    sheetState.targetState = visible

    // Keep Back captured through the exit animation, same rationale as the bottom
    // sheet (#201): currentState stays true until slide-out completes.
    BackHandler(enabled = sheetBackHandlerActive(sheetState.currentState, sheetState.targetState)) {
        onDismiss()
    }

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 80.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(visible) { dragOffset = 0f }

    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        label = "meters-sheet-drag-offset",
    )

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color(0xB805080E))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() },
        )
    }

    AnimatedVisibility(
        visibleState = sheetState,
        // Slide from the TOP (negative offset) instead of the bottom.
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, animatedOffset.toInt()) }
                        .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                        .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                        .background(BgSurface2)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { /* consume click */ },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()

                // Drag handle at the BOTTOM edge — a drag UP dismisses the sheet.
                Box(
                    modifier =
                        Modifier
                            .padding(top = 4.dp, bottom = 10.dp)
                            .width(72.dp)
                            .height(20.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragOffset < -dismissThresholdPx) {
                                            onDismiss()
                                        } else {
                                            dragOffset = 0f
                                        }
                                    },
                                    onDragCancel = { dragOffset = 0f },
                                    onVerticalDrag = { _, dy ->
                                        val maxUp = if (sheetHeightPx > 0f) sheetHeightPx else Float.MAX_VALUE
                                        dragOffset = (dragOffset + dy).coerceIn(-maxUp, 0f)
                                    },
                                )
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(Color(0x6694A3B8)),
                    )
                }
            }
        }
    }
}

/**
 * A small visible pull-tab centered at the very top of the app that opens the
 * meters HUD. Replaces the old invisible top-edge swipe, which (a) gave no hint
 * it existed and (b) fought Android's notification-shade gesture, which owns the
 * screen's top edge. This tab sits just below the status bar (statusBarsPadding)
 * so the system doesn't steal the gesture, and opens on either a tap or a short
 * downward drag — whichever the user reaches for.
 */
@Composable
fun MetersHandle(
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val density = LocalDensity.current
    val openThresholdPx = with(density) { 24.dp.toPx() }
    var totalDy by remember { mutableFloatStateOf(0f) }
    Box(
        modifier =
            modifier
                .statusBarsPadding()
                // Generous touch target around the small visible tab.
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { totalDy = 0f },
                        onDragEnd = {
                            if (shouldOpenFromEdgeDrag(totalDy, openThresholdPx)) onOpen()
                            totalDy = 0f
                        },
                        onDragCancel = { totalDy = 0f },
                        onVerticalDrag = { _, dy -> totalDy += dy },
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpen() }
                .padding(horizontal = 28.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // The visible tab: a rounded-bottom chip with a grabber + "METERS ▾".
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(AccentSoft)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = stringResource(R.string.meters_title),
                color = Accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.12.sp,
            )
            Text(
                text = "▾",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
