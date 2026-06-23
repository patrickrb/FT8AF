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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.runtime.livedata.observeAsState
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.ft8transmit.MeterProtectionController
import radio.ks3ckc.ft8af.theme.*

/** Maps a [MeterZone] to its bar/readout color. */
private fun zoneColor(zone: MeterZone): Color = when (zone) {
    MeterZone.IDLE -> TextFaint
    MeterZone.GOOD -> StatusConfirmed
    MeterZone.CAUTION -> StatusWarn
    MeterZone.DANGER -> StatusBad
}

/**
 * Pull-down meters HUD: ALC and SWR read back from the rig over CAT. These are
 * the two meters every supported CAT rig reports, and only while keyed — so the
 * readout is live during TX and labelled "last TX" otherwise. Opened by a
 * top-edge swipe-down (see [TopEdgeMetersTrigger]) from anywhere in the app.
 */
@Composable
fun MetersSheet(
    visible: Boolean,
    mainViewModel: MainViewModel,
    isTransmitting: Boolean,
    onDismiss: () -> Unit,
) {
    val controller = mainViewModel.meterProtectionController
    val alc by controller.lastAlc.observeAsState(0)
    val swr by controller.lastSwr.observeAsState(0)
    val hasData by controller.meterDataReceived.observeAsState(false)

    MetersTopSheet(visible = visible, onDismiss = onDismiss) {
        val freshness = meterFreshness(isTransmitting, hasData)

        Column(
            modifier = Modifier
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

            Spacer(modifier = Modifier.height(16.dp))

            if (freshness == MeterFreshness.NONE) {
                Text(
                    text = stringResource(R.string.meters_no_data),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            MeterRow(
                label = stringResource(R.string.meters_alc),
                valueText = "${alcPercent(alc)}%",
                fraction = meterBarFraction(alc),
                zone = alcZone(alc, GeneralVariables.alcTargetLow, GeneralVariables.alcTargetHigh),
                dim = freshness == MeterFreshness.NONE,
            )

            Spacer(modifier = Modifier.height(14.dp))

            MeterRow(
                label = stringResource(R.string.meters_swr),
                valueText = MeterProtectionController.normalizedSwrToRatio(swr),
                fraction = meterBarFraction(swr),
                zone = swrZone(swr, GeneralVariables.swrHaltThreshold),
                dim = freshness == MeterFreshness.NONE,
            )
        }
    }
}

@Composable
private fun FreshnessBadge(freshness: MeterFreshness) {
    val (text, color) = when (freshness) {
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
        modifier = Modifier
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
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(BgSurface3),
        ) {
            Box(
                modifier = Modifier
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
            modifier = Modifier
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
                modifier = Modifier
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
                    modifier = Modifier
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
                        modifier = Modifier
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
 * Invisible top-edge strip that opens the meters HUD on a downward swipe. Sized
 * thin so it only claims the very top edge (like the Android status-bar pull),
 * leaving the rest of the screen's gestures untouched. Tracks cumulative drag
 * and commits via [shouldOpenFromEdgeDrag].
 */
@Composable
fun TopEdgeMetersTrigger(
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    val density = LocalDensity.current
    val openThresholdPx = with(density) { 36.dp.toPx() }
    var totalDy by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
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
            },
    )
}
