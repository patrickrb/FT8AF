package radio.ks3ckc.ft8us.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bg7yoz.ft8cn.R
import radio.ks3ckc.ft8us.theme.*

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
    expanded: Boolean = false,
    onCallCQ: () -> Unit,
    onStop: () -> Unit,
    onToggleSlot: () -> Unit,
    onToggleHunt: () -> Unit,
    onCycleMode: () -> Unit,
    onToggleDx: () -> Unit = {},
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .drawBehind {
                // Top border
                drawLine(
                    color = Border,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: chevron + status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Expand/collapse chevron — only shown when QSO is active
            if (isActivated) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onToggleExpand() }
                        .rotate(if (expanded) 0f else 180f),
                    contentAlignment = Alignment.Center,
                ) {
                    FT8USIcons.ChevronDown(
                        size = 14.dp,
                        color = TextMuted,
                        strokeWidth = 2f,
                    )
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
            )
        }

        // Right: CQ/Stop button + frequency
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Mode pill (FT8/FT4) — taps cycle the operating mode. Disabled mid-transmit
            // so we never switch the cycle out from under an in-progress TX.
            val modeBg = if (modeSwitchEnabled) Accent.copy(alpha = 0.18f) else BgSurface3
            val modeColor = if (modeSwitchEnabled) Accent else TextFaint
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(modeBg)
                    .clickable(enabled = modeSwitchEnabled) { onCycleMode() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = modeName,
                    color = modeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // Frequency / band pill — opens the frequency picker
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
                FT8USIcons.ChevronDown(
                    size = 12.dp,
                    color = TextMuted,
                    strokeWidth = 2f,
                )
            }

            // DX (DXpedition Hound) toggle pill. On = working a Fox/DXpedition
            // (call high, auto-QSY when answered). Mutually exclusive with HUNT/CQ.
            val dxBg = if (dxEnabled) Accent.copy(alpha = 0.18f) else BgSurface3
            val dxColor = if (dxEnabled) Accent else TextMuted
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(dxBg)
                    .clickable { onToggleDx() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "DX",
                    color = dxColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // HUNT (auto-answer CQ) toggle pill. On = proactively call stations
            // calling CQ; off = run CQ. Mutually exclusive with CQ: disabled while
            // you're actively running CQ so the two modes never overlap.
            val huntDisabled = isActivated && !huntEnabled
            val huntBg = when {
                huntDisabled -> BgSurface3.copy(alpha = 0.4f)
                huntEnabled -> Signal.copy(alpha = 0.18f)
                else -> BgSurface3
            }
            val huntColor = when {
                huntDisabled -> TextMuted.copy(alpha = 0.4f)
                huntEnabled -> Signal
                else -> TextMuted
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(huntBg)
                    // Disable via clickable(enabled=…) rather than dropping the modifier, so
                    // the pill keeps its button semantics and TalkBack still announces it as a
                    // disabled control instead of it vanishing from accessibility entirely.
                    .clickable(enabled = !huntDisabled) { onToggleHunt() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "HUNT",
                    color = huntColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // CQ / Stop pill button. Mutually exclusive with HUNT: disabled while
            // HUNT mode is on, so you can't run CQ and hunt at the same time.
            val cqDisabled = huntEnabled && !isActivated
            val buttonBg = when {
                isActivated -> StatusBad.copy(alpha = 0.18f)
                cqDisabled -> AccentSoft.copy(alpha = 0.4f)
                else -> AccentSoft
            }
            val buttonTextColor = when {
                isActivated -> StatusBad
                cqDisabled -> Accent.copy(alpha = 0.4f)
                else -> Accent
            }
            val buttonLabel = if (isActivated) "STOP" else "CQ"

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(buttonBg)
                    // Keep button semantics when disabled (see HUNT pill above) so the
                    // CQ/STOP control stays exposed to TalkBack as a disabled button.
                    .clickable(enabled = !cqDisabled) { if (isActivated) onStop() else onCallCQ() }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = buttonLabel,
                    color = buttonTextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.04.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // TX slot toggle pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgSurface3)
                    .clickable { onToggleSlot() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (txSlot == 0) "TX1" else "TX2",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
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
