package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.AccentGlow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Decorative empty-state illustration: three stacked sine waves drifting in phase, with a
 * radial accent glow behind them. Drop into empty Decode / Map / Logbook screens.
 *
 * Drawn entirely on a single Canvas. The Path is reused across frames via [remember]; only the
 * phase float updates per frame, so this is allocation-free in steady state.
 */
@Composable
fun EmptyStateWaves(
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    accent: Color = Accent,
    glow: Color = AccentGlow,
) {
    val transition = rememberInfiniteTransition(label = "empty-waves")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "empty-waves-phase",
    )

    val path = remember { Path() }

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f

            // Center glow.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to glow.copy(alpha = 0.25f),
                    1f to glow.copy(alpha = 0f),
                    center = Offset(cx, cy),
                    radius = w * 0.45f,
                ),
                center = Offset(cx, cy),
                radius = w * 0.45f,
            )

            // Three stacked sine waves, each offset by 120° of phase.
            val baseAmp = h * 0.10f
            val waveCenterY = cy
            val freq = (2.0 * PI / w).toFloat()
            val phaseRad = phase * 2f * PI.toFloat()

            for (i in 0..2) {
                val waveAmp = baseAmp * (1f - i * 0.18f)
                val waveAlpha = 0.45f - i * 0.10f
                val phaseShift = phaseRad + i * (2f * PI.toFloat() / 3f)
                val verticalOffset = (i - 1) * h * 0.10f

                path.reset()
                var x = 0f
                val step = 6f
                path.moveTo(0f, waveCenterY + verticalOffset + waveAmp * sin(phaseShift))
                while (x <= w) {
                    val y = waveCenterY + verticalOffset + waveAmp * sin(x * freq + phaseShift)
                    path.lineTo(x, y)
                    x += step
                }

                drawPath(
                    path = path,
                    color = accent.copy(alpha = waveAlpha),
                    style = Stroke(width = (1.5f - i * 0.2f).coerceAtLeast(0.8f).dp.toPx()),
                )
            }
        }
    }
}
