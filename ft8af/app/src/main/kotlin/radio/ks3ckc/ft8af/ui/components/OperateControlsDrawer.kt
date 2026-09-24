package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8af.theme.*

/**
 * Given the drawer's current state and the net vertical drag applied to the handle (negative =
 * upward), decide the state to settle into when the drag ends. An upward drag past the threshold
 * from the collapsed peek expands it; a downward drag past the threshold from expanded collapses
 * it; anything short of the threshold leaves the state unchanged. Extracted so the gesture rule
 * can be unit-tested without Compose.
 */
internal fun drawerTargetExpanded(
    currentlyExpanded: Boolean,
    dragDy: Float,
    thresholdPx: Float,
): Boolean = when {
    !currentlyExpanded && dragDy <= -thresholdPx -> true
    currentlyExpanded && dragDy >= thresholdPx -> false
    else -> currentlyExpanded
}

/** The vertical drag distance on the handle that commits to toggling the drawer. */
internal val DrawerDragThreshold = 48.dp

/**
 * The collapsed height of the drawer — the handle (with its "More controls" hint) plus the primary
 * Call CQ + Hunt row, stopping before the next row so nothing peeks through when collapsed.
 */
internal val OperateDrawerPeekHeight = 120.dp

/**
 * The operate-controls drawer (design 3a/3b) as a *single* bottom sheet that grows in place.
 *
 * The sheet is anchored so its bottom sits on [anchorBottomPx] (the window-space Y that the
 * reserved peek slot in the main column occupies, i.e. just above the tab bar). Its content is a
 * single top-aligned column — a grab handle, the primary Call CQ / Stop + Hunt row, then the
 * secondary controls (Band & Mode, TX period / Tune / DX, power). Collapsed, the sheet is
 * [OperateDrawerPeekHeight] tall and only the handle + primary row show (3a); dragging the handle
 * up or tapping it grows the *same* surface upward to reveal the rest over a scrim (3b). Nothing
 * slides over the primary controls — it is one sheet expanding, not a second sheet on top.
 */
@Composable
fun OperateControlsDrawer(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    anchorBottomPx: Float,
    isTransmitting: Boolean,
    isActivated: Boolean,
    isTuning: Boolean,
    bandModeLabel: String,
    txSlot: Int,
    huntEnabled: Boolean,
    huntOptionLabel: String,
    dxEnabled: Boolean,
    txVolume: Int,
    showVolumeSlider: Boolean,
    cqModifier: String,
    isFreeTextMode: Boolean,
    fieldDayEnabled: Boolean,
    tuneRemainingSec: Int,
    onCallCQ: () -> Unit,
    onStop: () -> Unit,
    onSelectTxPeriod: (Int) -> Unit,
    onToggleHunt: () -> Unit,
    onOpenHuntOptions: () -> Unit,
    onOpenBandMode: () -> Unit,
    onToggleTune: () -> Unit,
    onToggleDx: () -> Unit,
    onOpenCqOptions: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onVolumeChangeFinished: () -> Unit,
) {
    // Not laid out yet — the reserved peek slot hasn't reported its position. Skip a frame.
    if (anchorBottomPx <= 0f) return

    val density = LocalDensity.current
    val peekPx = with(density) { OperateDrawerPeekHeight.toPx() }
    val thresholdPx = with(density) { DrawerDragThreshold.toPx() }
    val scope = rememberCoroutineScope()

    // Full natural height of the content, measured independently of the (clipped) sheet height.
    var fullContentPx by remember { mutableFloatStateOf(peekPx) }
    val rangePx = (fullContentPx - peekPx).coerceAtLeast(1f)

    // fraction 0 = collapsed peek, 1 = fully expanded. Animated on tap; follows the finger on drag.
    val fraction = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) { fraction.animateTo(if (expanded) 1f else 0f, tween(220)) }

    val sheetHeightPx = peekPx + rangePx * fraction.value
    val scrimAlpha = 0.55f * fraction.value

    val actions = txStripActionState(isActivated, huntEnabled)

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim over the content above the sheet only (stops at the tab bar so the tabs stay
        // usable). Tappable to collapse once the sheet is mostly open.
        if (scrimAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { anchorBottomPx.toDp() })
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .then(
                        if (fraction.value > 0.5f) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onExpandedChange(false) }
                        } else {
                            Modifier
                        }
                    )
            )
        }

        // The sheet — positioned so its bottom edge lands on anchorBottomPx and it grows upward.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (anchorBottomPx - sheetHeightPx).roundToInt()) }
                .height(with(density) { sheetHeightPx.toDp() })
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(BgSurface3)
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Measure the natural (unbounded) content height so the expanded target is
                    // exact; the parent Box clips it to the animated height.
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .onSizeChanged { fullContentPx = it.height.toFloat() },
            ) {
                DrawerHandle(
                    expanded = expanded,
                    onToggle = { onExpandedChange(!expanded) },
                    onDrag = { dy ->
                        val next = (fraction.value - dy / rangePx).coerceIn(0f, 1f)
                        scope.launch { fraction.snapTo(next) }
                    },
                    onDragEnd = { totalDy ->
                        val target = drawerTargetExpanded(expanded, totalDy, thresholdPx)
                        scope.launch { fraction.animateTo(if (target) 1f else 0f, tween(220)) }
                        onExpandedChange(target)
                    },
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ---- Primary row (visible in the peek): Call CQ / Stop + Hunt ----
                    CqHuntRow(
                        actions = actions,
                        huntOptionLabel = huntOptionLabel,
                        cqModifier = cqModifier,
                        isFreeTextMode = isFreeTextMode,
                        fieldDayEnabled = fieldDayEnabled,
                        onCallCQ = onCallCQ,
                        onStop = onStop,
                        onOpenCqOptions = onOpenCqOptions,
                        onToggleHunt = onToggleHunt,
                        onOpenHuntOptions = onOpenHuntOptions,
                    )

                    // ---- Secondary controls, revealed as the sheet grows (design 3b) ----
                    BandModeRow(bandModeLabel = bandModeLabel, onOpenBandMode = onOpenBandMode)

                    PeriodTuneDxRow(
                        txSlot = txSlot,
                        isActivated = isActivated,
                        isTransmitting = isTransmitting,
                        isTuning = isTuning,
                        tuneRemainingSec = tuneRemainingSec,
                        dxEnabled = dxEnabled,
                        onSelectTxPeriod = onSelectTxPeriod,
                        onToggleTune = onToggleTune,
                        onToggleDx = onToggleDx,
                    )

                    if (showVolumeSlider) {
                        TxVolumeRow(
                            txVolume = txVolume,
                            onVolumeChange = onVolumeChange,
                            onVolumeChangeFinished = onVolumeChangeFinished,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The drawer's grab handle: a centered grabber bar plus — while collapsed — an up-chevron and
 * "More controls" caption that gently bob to advertise that the sheet pulls up. A tap toggles the
 * drawer; a vertical drag is fed live to [onDrag] (dy, negative = up) and settled in [onDragEnd]
 * with the total drag distance.
 */
@Composable
private fun DrawerHandle(
    expanded: Boolean,
    onToggle: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var totalDy by remember { mutableFloatStateOf(0f) }
    val expandLabel = stringResource(R.string.controls_drawer_expand)
    val collapseLabel = stringResource(R.string.controls_drawer_collapse)
    val label = if (expanded) collapseLabel else expandLabel

    // Gentle up-and-down bob on the hint, only while collapsed, to draw the eye to the affordance.
    val bobTransition = rememberInfiniteTransition(label = "drawer-hint-bob")
    val bob by bobTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "drawer-hint-bob-offset",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggle() }
            .semantics { role = Role.Button; contentDescription = label }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { totalDy = 0f },
                    onDragEnd = { onDragEnd(totalDy); totalDy = 0f },
                    onDragCancel = { totalDy = 0f },
                    onVerticalDrag = { _, dy ->
                        totalDy += dy
                        onDrag(dy)
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0x6694A3B8)), // rgba(148,163,184,0.40)
        )
        if (expanded) {
            // Down-chevron when open — a plain "pull down to collapse" hint.
            FT8AFIcons.ChevronDown(size = 14.dp, color = TextFaint, strokeWidth = 2.2f)
        } else {
            Row(
                modifier = Modifier.offset(y = (bob * -3f).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // Up-chevron (the down-chevron glyph rotated) to say "pulls up".
                Box(modifier = Modifier.rotate(180f)) {
                    FT8AFIcons.ChevronDown(size = 13.dp, color = Accent, strokeWidth = 2.4f)
                }
                Text(
                    text = stringResource(R.string.controls_drawer_more),
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                    letterSpacing = 0.2.sp,
                )
            }
        }
    }
}
