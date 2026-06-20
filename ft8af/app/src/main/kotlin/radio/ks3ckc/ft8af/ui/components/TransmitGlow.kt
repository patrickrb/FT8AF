package radio.ks3ckc.ft8af.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.ui.motion.MotionTokens
import radio.ks3ckc.ft8af.ui.motion.rememberHaptics

/**
 * Full-screen breathing border that overlays content while [isTransmitting] is true.
 *
 * Implementation notes:
 *  - Place at the top level of the screen as a sibling (not parent) of the main content,
 *    so its per-frame invalidations don't bubble into the waterfall composable.
 *  - The brush is built once via [drawWithCache]; only alpha changes per frame.
 *  - Pointer events pass through (this layer never consumes them).
 */
@Composable
fun TransmitGlow(
    isTransmitting: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    LaunchedEffect(isTransmitting) {
        if (isTransmitting) haptics.confirm()
    }

    AnimatedVisibility(
        visible = isTransmitting,
        enter = fadeIn(tween(MotionTokens.DurMed)),
        exit = fadeOut(tween(MotionTokens.DurSlow)),
        modifier = modifier,
    ) {
        val transition = rememberInfiniteTransition(label = "tx-glow")
        val breath by transition.animateFloat(
            initialValue = 0.18f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = MotionTokens.EasingStandard),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "tx-glow-alpha"
        )

        val useBlendPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val inset = 6.dp.toPx()
                    val strokeWidth = 4.dp.toPx()
                    val cornerRadius = 22.dp.toPx()

                    val rect = Rect(
                        offset = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                    )
                    val path = Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                rect,
                                androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius)
                            )
                        )
                    }
                    val brush = Brush.linearGradient(
                        0f to Accent.copy(alpha = 0.9f),
                        0.5f to Accent.copy(alpha = 0.4f),
                        1f to Accent.copy(alpha = 0.9f),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    )
                    val blend = if (useBlendPlus) BlendMode.Plus else BlendMode.SrcOver

                    onDrawWithContent {
                        drawContent()
                        drawPath(
                            path = path,
                            brush = brush,
                            alpha = breath,
                            style = Stroke(width = strokeWidth),
                            blendMode = blend,
                        )
                    }
                }
        )
    }
}
