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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TxStrip(
    isTransmitting: Boolean,
    isActivated: Boolean,
    frequencyLabel: String,
    txSlot: Int,
    huntEnabled: Boolean,
    modeName: String,
    modeSwitchEnabled: Boolean,
    dxEnabled: Boolean = false,
    catState: CatConnectionState = CatConnectionState.DISCONNECTED,
    showCatChip: Boolean = false,
    expanded: Boolean = false,
    txVolume: Int = 80,
    showVolumeSlider: Boolean = false,
    cqModifier: String = "",
    isFreeTextMode: Boolean = false,
    fieldDayEnabled: Boolean = false,
    onVolumeChange: (Int) -> Unit = {},
    onVolumeChangeFinished: () -> Unit = {},
    onCallCQ: () -> Unit,
    onStop: () -> Unit,
    onLongPressCQ: () -> Unit = {},
    onToggleSlot: () -> Unit,
    onToggleHunt: () -> Unit,
    onCycleMode: () -> Unit,
    onToggleDx: () -> Unit = {},
    onReconnectCat: () -> Unit = {},
    onOpenFrequencyPicker: () -> Unit,
    onToggleExpand: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isTransmitting) {
        Brush.horizontalGradient(
            listOf(
                Color(0x1FFFAF5E),  // rgba(255,175,94,0.12)
                Color(0x0AFFAF5E),  // rgba(255,175,94,0.04)
            )
        )
    } else {
        Brush.horizontalGradient(listOf(BgSurface, BgSurface))
    }

    val actions = txStripActionState(isActivated, huntEnabled)

    // Two stacked rows (vs. the old single cramped FlowRow): an info line with the small
    // status/mode/frequency/DX chips, and a row of three large primary buttons
    // (HUNT · CQ/STOP · TX slot) sized like the design prototype so the main action is a
    // comfortable tap target on a phone.
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- Info row: status (left) · mode / frequency / DX chips (right) ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: expand chevron (when QSO active) + pulse dot + state + CAT chip.
            // weight(1f, fill=false) lets the status label ellipsize before it can shove
            // the right-hand chips off screen on a narrow device.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isActivated) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onToggleExpand() }
                            .rotate(if (expanded) 0f else 180f),
                        contentAlignment = Alignment.Center,
                    ) {
                        FT8AFIcons.ChevronDown(size = 14.dp, color = TextMuted, strokeWidth = 2f)
                    }
                }
                PulseDot(color = if (isTransmitting) Accent else Signal)
                Text(
                    text = if (isTransmitting) stringResource(R.string.tx_transmitting)
                    else stringResource(R.string.tx_listening),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showCatChip) {
                    CatStatusChip(state = catState, onReconnect = onReconnectCat)
                }
            }

            // Right: mode pill, frequency/band pill, DX pill.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Mode pill (FT8/FT4/FT2) — taps cycle the operating mode. Disabled
                // mid-transmit so we never switch the cycle out from under a live TX.
                TxChip(
                    label = modeName,
                    background = if (modeSwitchEnabled) Accent.copy(alpha = 0.18f) else BgSurface3,
                    textColor = if (modeSwitchEnabled) Accent else TextFaint,
                    bold = true,
                    enabled = modeSwitchEnabled,
                    onClick = onCycleMode,
                )

                // Frequency / band pill — opens the frequency picker.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3)
                        .clickable { onOpenFrequencyPicker() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = frequencyLabel,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistMonoFamily,
                        letterSpacing = 0.02.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    FT8AFIcons.ChevronDown(size = 12.dp, color = TextMuted, strokeWidth = 2f)
                }

                // DX (DXpedition Hound) toggle pill. On = working a Fox (call high,
                // auto-QSY when answered). Mutually exclusive with HUNT/CQ.
                TxChip(
                    label = "DX",
                    background = if (dxEnabled) Accent.copy(alpha = 0.18f) else BgSurface3,
                    textColor = if (dxEnabled) Accent else TextMuted,
                    bold = false,
                    enabled = true,
                    onClick = onToggleDx,
                )
            }
        }

        // ---- Action row: HUNT · CQ/STOP · TX slot ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // HUNT — stacked icon + label. Locked off during an active CQ/QSO.
            StackedActionButton(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.tx_hunt),
                background = when {
                    actions.huntDisabled -> BgSurface3.copy(alpha = 0.4f)
                    actions.huntActive -> Signal.copy(alpha = 0.18f)
                    else -> BgSurface3
                },
                contentColor = when {
                    actions.huntDisabled -> TextMuted.copy(alpha = 0.4f)
                    actions.huntActive -> Signal
                    else -> TextMuted
                },
                borderColor = if (actions.huntActive) Signal.copy(alpha = 0.5f) else Border,
                enabled = !actions.huntDisabled,
                onClick = onToggleHunt,
            ) { color -> FT8AFIcons.Target(size = 18.dp, color = color, strokeWidth = 1.8f) }

            // CQ / STOP — the primary action. Filled amber to call CQ, red to stop.
            // Locked off while HUNT is armed (and no QSO yet).
            // Long-press opens CQ options (modifier, free text, Field Day).
            val cqIsStop = actions.cqIsStop
            val cqSubtitle = when {
                cqIsStop -> null
                isFreeTextMode -> "FREE"
                fieldDayEnabled -> "FD"
                cqModifier.isNotEmpty() -> cqModifier
                else -> null
            }
            PrimaryActionButton(
                modifier = Modifier.weight(1.7f),
                label = if (cqIsStop) stringResource(R.string.tx_stop) else stringResource(R.string.tx_call_cq),
                subtitle = cqSubtitle,
                background = when {
                    cqIsStop -> StatusBad
                    actions.cqDisabled -> Accent.copy(alpha = 0.35f)
                    else -> Accent
                },
                contentColor = if (cqIsStop) Color.White else BgApp,
                enabled = !actions.cqDisabled,
                onClick = { if (cqIsStop) onStop() else onCallCQ() },
                onLongClick = if (!cqIsStop) onLongPressCQ else null,
            ) { color ->
                if (cqIsStop) {
                    FT8AFIcons.Close(size = 18.dp, color = color, strokeWidth = 2f)
                } else {
                    FT8AFIcons.Transmit(size = 18.dp, color = color, strokeWidth = 1.8f)
                }
            }

            // TX time-slot toggle — stacked arrow + TX1/TX2.
            StackedActionButton(
                modifier = Modifier.weight(1f),
                label = if (txSlot == 0) "TX1" else "TX2",
                background = BgSurface3,
                contentColor = TextMuted,
                borderColor = Border,
                enabled = true,
                onClick = onToggleSlot,
            ) { color -> FT8AFIcons.ArrowUp(size = 18.dp, color = color, strokeWidth = 1.8f) }
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
                // Minus button
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
                        text = "\u2212", // minus sign
                        color = TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                    )
                }

                // Slider
                IntSlider(
                    value = txVolume,
                    onValueChange = { v ->
                        onVolumeChange(v.coerceIn(0, 100))
                    },
                    onValueChangeFinished = onVolumeChangeFinished,
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                )

                // Plus button
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

                // Percentage label
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

/** A small rounded text chip (mode / DX). Keeps button semantics when disabled. */
@Composable
private fun TxChip(
    label: String,
    background: Color,
    textColor: Color,
    bold: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            // Disable via clickable(enabled=…) rather than dropping the modifier, so the
            // chip keeps its button semantics and TalkBack still announces it as a disabled
            // control instead of vanishing from accessibility entirely.
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.02.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A large secondary button with the icon stacked above the label (HUNT, TX slot). */
@Composable
private fun StackedActionButton(
    label: String,
    background: Color,
    contentColor: Color,
    borderColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    Column(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        icon(contentColor)
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.02.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The large primary button with the icon beside the label (CQ / STOP). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrimaryActionButton(
    label: String,
    background: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable (Color) -> Unit,
) {
    Row(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(contentColor)
        if (subtitle != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.04.sp,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = subtitle,
                    color = contentColor.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Text(
                text = label,
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.04.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (onLongClick != null) {
            FT8AFIcons.ChevronDown(size = 10.dp, color = contentColor.copy(alpha = 0.5f), strokeWidth = 2f)
        }
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
        // Pulse ring
        Box(
            modifier = Modifier
                .size((6 + pulseSize * 2).dp)
                .clip(CircleShape)
                .background(color.copy(alpha = pulseAlpha * 0.18f))
        )
        // Solid dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}
