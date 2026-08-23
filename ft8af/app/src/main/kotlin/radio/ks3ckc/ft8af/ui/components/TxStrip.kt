package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.rigs.CatConnectionState
import radio.ks3ckc.ft8af.theme.*

/**
 * The enabled/disabled state of the two mutually-exclusive action buttons (HUNT and
 * CQ/STOP), derived purely from the activation + hunt flags so the rule can be unit-tested
 * without Compose.
 *
 * HUNT (auto-answer the stations calling CQ) and running your own CQ can't both be on:
 *  - While a CQ/QSO is active ([isActivated]) the HUNT toggle is locked off.
 *  - While HUNT is armed (and no QSO yet) the CQ button is locked off.
 * Once activated, the CQ button becomes the STOP button.
 */
internal data class TxStripActionState(
    val huntDisabled: Boolean,
    val huntActive: Boolean,
    val cqDisabled: Boolean,
    val cqIsStop: Boolean,
)

/**
 * Clamp a volume value after a +/- step to the 0–100 range.
 * Extracted so it can be unit-tested without Compose.
 */
internal fun clampVolume(current: Int, delta: Int): Int =
    (current + delta).coerceIn(0, 100)

internal fun txStripActionState(isActivated: Boolean, huntEnabled: Boolean) = TxStripActionState(
    huntDisabled = isActivated && !huntEnabled,
    huntActive = huntEnabled,
    cqDisabled = huntEnabled && !isActivated,
    cqIsStop = isActivated,
)

/**
 * Label for the TUNE chip: the plain label when idle, "label countdown" while
 * the carrier is up (e.g. "Tune 7s") so the operator sees the safety timeout
 * running. Extracted so it can be unit-tested without Compose.
 */
internal fun tuneChipLabel(label: String, isTuning: Boolean, remainingSec: Int): String =
    if (isTuning) "$label ${remainingSec.coerceAtLeast(0)}s" else label

/**
 * The small subtitle shown under "Call CQ" that reflects which CQ variant is queued.
 * Precedence: free-text > Field Day > custom modifier > none. Null while the button is
 * in its STOP state. Extracted so the precedence can be unit-tested without Compose.
 */
internal fun cqStripSubtitle(
    cqIsStop: Boolean,
    isFreeTextMode: Boolean,
    fieldDayEnabled: Boolean,
    cqModifier: String,
): String? = when {
    cqIsStop -> null
    isFreeTextMode -> "FREE"
    fieldDayEnabled -> "FD"
    cqModifier.isNotEmpty() -> cqModifier
    else -> null
}

/**
 * Which of the three status states the strip's status row shows, and whether the "next window"
 * countdown should appear (only while Listening). Extracted so the mapping is unit-testable
 * without Compose. The pulse-dot color is amber ([Accent]) for Tuning/Transmitting, cyan
 * ([Signal]) for Listening.
 */
internal data class TxStatusVisuals(
    val labelRes: Int,
    val listening: Boolean,
)

internal fun txStatusVisuals(isTransmitting: Boolean, isTuning: Boolean): TxStatusVisuals = when {
    isTuning -> TxStatusVisuals(R.string.tx_status_tuning, listening = false)
    isTransmitting -> TxStatusVisuals(R.string.tx_status_transmitting, listening = false)
    else -> TxStatusVisuals(R.string.tx_status_listening, listening = true)
}

/**
 * The redesigned bottom-anchored TX strip (design option 3a). Rows: status + CAT chip, a
 * tappable Band & Mode card, the primary Call CQ button (with an attached MORE split-button)
 * alongside the Hunt tile, then the TX-period segmented control with Tune / DX. The slot
 * progress bar and clock-sync pill stay in the separate [SlotTimerBar] rendered just above.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TxStrip(
    isTransmitting: Boolean,
    isActivated: Boolean,
    bandModeLabel: String,
    slotMillis: Long,
    txSlot: Int,
    huntEnabled: Boolean,
    huntOptionLabel: String,
    onCallCQ: () -> Unit,
    onStop: () -> Unit,
    onSelectTxPeriod: (Int) -> Unit,
    onToggleHunt: () -> Unit,
    onOpenHuntOptions: () -> Unit,
    onOpenBandMode: () -> Unit,
    isTuning: Boolean = false,
    dxEnabled: Boolean = false,
    catState: CatConnectionState = CatConnectionState.DISCONNECTED,
    showCatChip: Boolean = false,
    txVolume: Int = 80,
    showVolumeSlider: Boolean = false,
    cqModifier: String = "",
    isFreeTextMode: Boolean = false,
    fieldDayEnabled: Boolean = false,
    tuneRemainingSec: Int = 0,
    onToggleTune: () -> Unit = {},
    onVolumeChange: (Int) -> Unit = {},
    onVolumeChangeFinished: () -> Unit = {},
    onOpenCqOptions: () -> Unit = {},
    onToggleDx: () -> Unit = {},
    onReconnectCat: () -> Unit = {},
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .drawBehind {
                drawLine(
                    color = Border,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- Row 1: status + CAT chip ----
        val status = txStatusVisuals(isTransmitting, isTuning)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseDot(color = if (status.listening) Signal else Accent)
                Column {
                    Text(
                        text = stringResource(status.labelRes),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status.listening) {
                        NextTxWindowLabel(slotMillis = slotMillis, txSlot = txSlot)
                    }
                }
            }
            if (showCatChip) {
                CatStatusChip(state = catState, onReconnect = onReconnectCat)
            }
        }

        // ---- Row 2: band & mode row (opens the Band & Mode sheet) ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BgSurface3)
                .border(1.dp, Border, RoundedCornerShape(12.dp))
                .clickable { onOpenBandMode() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.tx_band_mode_label),
                color = TextFaint,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFamily,
                letterSpacing = 0.8.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = bandModeLabel,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    maxLines = 1,
                    softWrap = false,
                )
                FT8AFIcons.ChevronDown(size = 14.dp, color = TextMuted, strokeWidth = 2f)
            }
        }

        // ---- Row 3: Call CQ (+ MORE) · Hunt tile ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cqIsStop = actions.cqIsStop
            val variantSubtitle = cqStripSubtitle(cqIsStop, isFreeTextMode, fieldDayEnabled, cqModifier)
            val cqSubtitle = variantSubtitle
                ?: if (!cqIsStop) stringResource(R.string.tx_call_cq_subtitle) else null
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

            HuntTile(
                modifier = Modifier.weight(1f),
                huntEnabled = actions.huntActive,
                huntDisabled = actions.huntDisabled,
                optionLabel = huntOptionLabel,
                onToggle = onToggleHunt,
                onOpenOptions = onOpenHuntOptions,
                optionsContentDescription = stringResource(R.string.tx_hunt_options),
            )
        }

        // ---- Row 4: TX period segmented control · Tune · DX ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TxPeriodControl(
                modifier = Modifier.weight(1.7f),
                txSlot = txSlot,
                onSelect = onSelectTxPeriod,
            )
            val tuneEnabled = isTuning || (!isActivated && !isTransmitting)
            SecondaryButton(
                modifier = Modifier.weight(0.45f),
                label = tuneChipLabel(stringResource(R.string.tune_button), isTuning, tuneRemainingSec),
                active = isTuning,
                enabled = tuneEnabled,
                onClick = onToggleTune,
            )
            SecondaryButton(
                modifier = Modifier.weight(0.45f),
                label = stringResource(R.string.tx_dx),
                active = dxEnabled,
                enabled = true,
                onClick = onToggleDx,
            )
        }

        // ---- Inline TX volume slider (togglable from Settings) ----
        val volumeDecrease = stringResource(R.string.tx_volume_decrease)
        val volumeIncrease = stringResource(R.string.tx_volume_increase)
        if (showVolumeSlider) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3)
                        .semantics { role = Role.Button; contentDescription = volumeDecrease }
                        .clickable {
                            onVolumeChange(clampVolume(txVolume, -5))
                            onVolumeChangeFinished()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "−",
                        color = TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                    )
                }

                IntSlider(
                    value = txVolume,
                    onValueChange = { v -> onVolumeChange(v.coerceIn(0, 100)) },
                    onValueChangeFinished = onVolumeChangeFinished,
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                )

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3)
                        .semantics { role = Role.Button; contentDescription = volumeIncrease }
                        .clickable {
                            onVolumeChange(clampVolume(txVolume, 5))
                            onVolumeChangeFinished()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+",
                        color = TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                    )
                }

                Text(
                    text = "${txVolume}%",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                )
            }
        }
    }
}

/**
 * The primary Call CQ button (72dp): amber with a stacked label + helper subtitle and an
 * attached "MORE" split button that opens the CQ-variant options. Turns into the red STOP
 * button (centered, no split) once a CQ/QSO is running.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallCqButton(
    cqIsStop: Boolean,
    cqDisabled: Boolean,
    subtitle: String?,
    onClick: () -> Unit,
    onOpenOptions: () -> Unit,
    optionsContentDescription: String,
    moreLabel: String,
    modifier: Modifier = Modifier,
) {
    val background = when {
        cqIsStop -> StatusBad
        cqDisabled -> Accent.copy(alpha = 0.35f)
        else -> Accent
    }
    val contentColor = if (cqIsStop) Color.White else BgApp

    Row(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .combinedClickable(
                enabled = !cqDisabled,
                onClick = onClick,
                onLongClick = if (!cqIsStop) onOpenOptions else null,
            )
            .padding(start = if (cqIsStop) 12.dp else 16.dp, end = if (cqIsStop) 12.dp else 4.dp),
        horizontalArrangement = if (cqIsStop) {
            Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.SpaceBetween
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cqIsStop) {
            FT8AFIcons.Close(size = 18.dp, color = contentColor, strokeWidth = 2f)
            Text(
                text = stringResource(R.string.tx_stop),
                color = contentColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = InterFamily,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FT8AFIcons.Transmit(size = 20.dp, color = contentColor, strokeWidth = 1.8f)
                    Text(
                        text = stringResource(R.string.tx_call_cq),
                        color = contentColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = InterFamily,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = contentColor.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = InterFamily,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // Attached MORE split button — a discoverable, separately-tappable affordance
            // for the CQ options (the whole button also long-presses to the same menu).
            Column(
                modifier = Modifier
                    .size(width = 48.dp, height = 60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(contentColor.copy(alpha = 0.16f))
                    .clickable(enabled = !cqDisabled, onClick = onOpenOptions)
                    .semantics { role = Role.Button; contentDescription = optionsContentDescription },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                FT8AFIcons.ChevronDown(size = 18.dp, color = contentColor, strokeWidth = 2.2f)
                Text(
                    text = moreLabel,
                    color = contentColor.copy(alpha = 0.65f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                )
            }
        }
    }
}

/**
 * The Hunt tile (72dp): tapping the tile toggles Hunt; the amber chip shows the active hunt
 * option and, when tapped, opens the hunt-options picker. Dimmed and locked while a CQ/QSO is
 * running (Hunt and calling CQ are mutually exclusive).
 */
@Composable
private fun HuntTile(
    huntEnabled: Boolean,
    huntDisabled: Boolean,
    optionLabel: String,
    onToggle: () -> Unit,
    onOpenOptions: () -> Unit,
    optionsContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val contentAlpha = if (huntDisabled) 0.4f else 1f
    Column(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (huntDisabled) BgSurface3.copy(alpha = 0.4f) else BgSurface3)
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .clickable(enabled = !huntDisabled) { onToggle() }
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FT8AFIcons.Target(size = 18.dp, color = TextMuted.copy(alpha = contentAlpha), strokeWidth = 1.8f)
            Text(
                text = stringResource(R.string.tx_hunt),
                color = TextPrimary.copy(alpha = contentAlpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = InterFamily,
                maxLines = 1,
                softWrap = false,
            )
        }
        HuntChip(
            label = optionLabel,
            active = huntEnabled,
            enabled = !huntDisabled,
            onClick = onOpenOptions,
            contentDescription = optionsContentDescription,
        )
    }
}

@Composable
private fun HuntChip(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
) {
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) AccentSoft else BgSurface)
            .then(
                if (active) Modifier.border(1.dp, BorderAmber, RoundedCornerShape(999.dp))
                else Modifier
            )
            .clickable(enabled = enabled, onClickLabel = contentDescription) { onClick() }
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                // Surface the visible label ("Off" / the active priority) so a screen reader
                // announces the current hunt option, not just "Hunt options".
                stateDescription = label
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = if (active) Accent else TextFaint,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFamily,
            maxLines = 1,
            softWrap = false,
        )
        FT8AFIcons.ChevronDown(
            size = 10.dp,
            color = if (active) Accent else TextFaint,
            strokeWidth = 2.4f,
        )
    }
}

/**
 * The TX-period card (52dp): a label plus a two-segment control choosing the 1st (even) or
 * 2nd (odd) transmit slot. [txSlot] 0 selects "1st (even)", 1 selects "2nd (odd)".
 */
@Composable
private fun TxPeriodControl(
    txSlot: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSurface3)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.tx_period_label),
            color = TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFamily,
            letterSpacing = 0.6.sp,
            maxLines = 1,
            softWrap = false,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(9.dp))
                .background(BgSurface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PeriodSegment(
                label = stringResource(R.string.tx_period_first),
                selected = txSlot == 0,
                modifier = Modifier.weight(1f),
                onClick = { if (txSlot != 0) onSelect(0) },
            )
            PeriodSegment(
                label = stringResource(R.string.tx_period_second),
                selected = txSlot == 1,
                modifier = Modifier.weight(1f),
                onClick = { if (txSlot != 1) onSelect(1) },
            )
        }
    }
}

@Composable
private fun PeriodSegment(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton) { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Accent else TextMuted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = InterFamily,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A small 52dp secondary button (Tune / DX): amber-tinted when active, muted otherwise. */
@Composable
private fun SecondaryButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Accent.copy(alpha = 0.18f) else BgSurface3)
            .border(
                1.dp,
                if (active) Accent.copy(alpha = 0.28f) else Border,
                RoundedCornerShape(12.dp),
            )
            // toggleable (not clickable) so TalkBack announces the on/off state — the DX
            // button's label never changes, so color alone can't convey whether it's active.
            .toggleable(value = active, enabled = enabled, onValueChange = { onClick() })
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                active -> Accent
                enabled -> TextMuted
                else -> TextMuted.copy(alpha = 0.4f)
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFamily,
            // The active "Tune 60s" countdown can be wider than the ~42dp available on a
            // narrow phone; allow it to wrap to a second line instead of clipping the seconds.
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PulseDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )
    val pulseSize by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseSize",
    )

    Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((7 + pulseSize * 2).dp)
                .clip(CircleShape)
                .background(color.copy(alpha = pulseAlpha * 0.18f))
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}
