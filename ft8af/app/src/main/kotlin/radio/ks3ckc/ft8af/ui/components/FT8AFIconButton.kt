package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.ui.motion.MotionTokens

/**
 * Icon-only button with PressableScale + optional one-shot expanding ring on tap.
 *
 * Use `pulseOnAction = true` for CQ/STOP-style action buttons where a confirmation pulse
 * is desirable. Use `pulseOnAction = false` for general toolbar/utility buttons.
 */
@Composable
fun FT8AFIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    pulseOnAction: Boolean = false,
    pulseColor: Color = Accent,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var pulseTrigger by remember { mutableIntStateOf(0) }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger == 0) return@LaunchedEffect
        pulse.snapTo(0f)
        pulse.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = MotionTokens.DurSlow,
                easing = MotionTokens.EasingEmphasizedDecel,
            )
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pressableScale(
                enabled = enabled,
                onClick = {
                    if (pulseOnAction) pulseTrigger++
                    onClick()
                }
            )
            .drawWithContent {
                drawContent()
                if (pulseOnAction && pulse.value > 0f && pulse.value < 1f) {
                    val p = pulse.value
                    val maxR = this.size.minDimension / 2f
                    val ringR = maxR * (0.5f + 0.7f * p)
                    val alpha = (1f - p) * 0.55f
                    drawCircle(
                        color = pulseColor.copy(alpha = alpha),
                        radius = ringR,
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        style = Stroke(width = 1.5f.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
