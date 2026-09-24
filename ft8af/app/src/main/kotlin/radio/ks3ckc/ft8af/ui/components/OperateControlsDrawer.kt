package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.k1af.ft8af.R
import com.k1af.ft8af.rigs.CatConnectionState
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

/** The vertical drag distance on the peek handle that commits to expanding the drawer. */
internal val DrawerDragThreshold = 48.dp

/**
 * The collapsed (peek) state of the operate-controls drawer — design option 3a. Renders inline
 * above the tab bar: the rig/CAT status line docked on top, then a rounded-top drawer surface
 * with a drag handle and only the primary action (Call CQ / Stop) alongside the TX-period toggle.
 * Tapping the handle or swiping it up opens the full [OperateControlsSheet]; the rest of the
 * controls live there.
 */
@Composable
fun OperateControlsPeek(
    isTransmitting: Boolean,
    isActivated: Boolean,
    isTuning: Boolean,
    slotMillis: Long,
    txSlot: Int,
    huntEnabled: Boolean,
    cqModifier: String,
    isFreeTextMode: Boolean,
    fieldDayEnabled: Boolean,
    showCatChip: Boolean,
    catState: CatConnectionState,
    onExpand: () -> Unit,
    onCallCQ: () -> Unit,
    onStop: () -> Unit,
    onOpenCqOptions: () -> Unit,
    onSelectTxPeriod: (Int) -> Unit,
    onReconnectCat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isTransmitting) {
        Brush.horizontalGradient(
            listOf(
                Color(0x1FFFAF5E), // rgba(255,175,94,0.12)
                Color(0x0AFFAF5E), // rgba(255,175,94,0.04)
            )
        )
    } else {
        Brush.horizontalGradient(listOf(BgSurface, BgSurface))
    }
    val actions = txStripActionState(isActivated, huntEnabled)
    val cqIsStop = actions.cqIsStop
    val variantSubtitle = cqStripSubtitle(cqIsStop, isFreeTextMode, fieldDayEnabled, cqModifier)
    val cqSubtitle = variantSubtitle
        ?: if (!cqIsStop) stringResource(R.string.tx_call_cq_subtitle) else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .drawBehind {
                drawLine(
                    color = Border,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Rig/CAT status line docked above the drawer surface (mockup 3a).
        OperateStatusRow(
            isTransmitting = isTransmitting,
            isTuning = isTuning,
            slotMillis = slotMillis,
            txSlot = txSlot,
            showCatChip = showCatChip,
            catState = catState,
            onReconnectCat = onReconnectCat,
        )

        // The peeking drawer surface: rounded-top card with a drag handle + primary controls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(BgSurface3),
        ) {
            DrawerHandle(currentlyExpanded = false, onSettle = { expand -> if (expand) onExpand() })
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallCqButton(
                    modifier = Modifier.weight(1.6f),
                    cqIsStop = cqIsStop,
                    cqDisabled = actions.cqDisabled,
                    subtitle = cqSubtitle,
                    onClick = { if (cqIsStop) onStop() else onCallCQ() },
                    onOpenOptions = onOpenCqOptions,
                    optionsContentDescription = stringResource(R.string.tx_cq_options),
                    moreLabel = stringResource(R.string.tx_more),
                )
                TxPeriodControl(
                    modifier = Modifier.weight(1f),
                    txSlot = txSlot,
                    onSelect = onSelectTxPeriod,
                )
            }
        }
    }
}

/**
 * The expanded (open) state of the operate-controls drawer — design option 3b. Reuses
 * [FT8AFBottomSheet] (scrim + drag handle + swipe-down / scrim-tap / Back dismissal) and lays out
 * the full control set in the mockup order: Band & Mode, Call CQ (+ MORE) / Hunt, TX period /
 * Tune / DX, and the power slider. Every control is the same widget the collapsed peek and the
 * legacy [TxStrip] use, so behavior is identical wherever the operator taps it.
 */
@Composable
fun OperateControlsSheet(
    visible: Boolean,
    isTransmitting: Boolean,
    isActivated: Boolean,
    bandModeLabel: String,
    txSlot: Int,
    huntEnabled: Boolean,
    huntOptionLabel: String,
    isTuning: Boolean,
    dxEnabled: Boolean,
    txVolume: Int,
    showVolumeSlider: Boolean,
    cqModifier: String,
    isFreeTextMode: Boolean,
    fieldDayEnabled: Boolean,
    tuneRemainingSec: Int,
    onDismiss: () -> Unit,
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
    val actions = txStripActionState(isActivated, huntEnabled)
    FT8AFBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BandModeRow(bandModeLabel = bandModeLabel, onOpenBandMode = onOpenBandMode)
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

/**
 * The drawer's grab handle: a centered 36×4 dp bar in a full-width, full-height touch target
 * that both a tap and a vertical drag act on. On drag-end it settles via [drawerTargetExpanded];
 * a tap toggles. [onSettle] receives the desired expanded state.
 */
@Composable
private fun DrawerHandle(
    currentlyExpanded: Boolean,
    onSettle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thresholdPx = with(LocalDensity.current) { DrawerDragThreshold.toPx() }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val expandLabel = stringResource(R.string.controls_drawer_expand)
    val collapseLabel = stringResource(R.string.controls_drawer_collapse)
    val label = if (currentlyExpanded) collapseLabel else expandLabel

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSettle(!currentlyExpanded) }
            .semantics { role = Role.Button; contentDescription = label }
            .pointerInput(currentlyExpanded) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccum = 0f },
                    onDragEnd = {
                        onSettle(drawerTargetExpanded(currentlyExpanded, dragAccum, thresholdPx))
                        dragAccum = 0f
                    },
                    onDragCancel = { dragAccum = 0f },
                    onVerticalDrag = { _, dy -> dragAccum += dy },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0x6694A3B8)), // rgba(148,163,184,0.40)
        )
    }
}
