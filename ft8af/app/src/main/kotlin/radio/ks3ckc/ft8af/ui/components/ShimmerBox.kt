package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface2

/**
 * Skeleton shimmer placeholder. Drop in while data loads.
 *
 * Single infinite transition drives a moving linear gradient through [drawWithCache] so the brush
 * is rebuilt only on size changes; per-frame work is just a translate.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    baseColor: Color = BgSurface,
    highlightColor: Color = BgSurface2,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-translate",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .drawWithCache {
                val widthPx = size.width
                val band = widthPx * 0.6f
                onDrawWithContent {
                    // Base fill.
                    drawRect(color = baseColor)
                    // Moving highlight band.
                    val startX = -band + (widthPx + band) * translate
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(baseColor, highlightColor, baseColor),
                            start = Offset(startX, 0f),
                            end = Offset(startX + band, size.height),
                        ),
                    )
                }
            }
    )
}
