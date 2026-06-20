package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.AccentGlow
import radio.ks3ckc.ft8af.ui.motion.rememberHaptics
import kotlin.math.cos
import kotlin.math.sin

private const val DURATION_MS = 900L
private const val PARTICLE_COUNT = 12

/**
 * One-shot QSO celebration overlay. Render at the root of [FT8AFApp] as a sibling above content.
 *
 * Drives a single [withFrameNanos] coroutine; per-frame work is O(PARTICLE_COUNT). All particle
 * offsets/vectors are precomputed in [remember], so there are no allocations per frame.
 *
 * Trigger by [triggerAt] — when it changes to a non-null value, one celebration sequence plays.
 */
@Composable
fun QsoCelebration(
    triggerAt: Long?,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()

    // Precompute particle directional unit vectors (no per-frame allocations).
    val directions = remember {
        Array(PARTICLE_COUNT) { i ->
            val theta = (i.toDouble() / PARTICLE_COUNT) * 2.0 * Math.PI
            Offset(cos(theta).toFloat(), sin(theta).toFloat())
        }
    }

    // 0f = idle, (0,1] = playing
    var progress by remember { mutableStateOf(0f) }

    // `triggerAt` mirrors a LiveData that a sibling effect resets to null right after
    // a QSO completes. Keying the animation directly on `triggerAt` lets that null
    // transition cancel the coroutine mid-flight, freezing a partial frame (the
    // starting ring + centered particle cluster) painted on screen forever — the
    // reported "leftover circle and dot" bug. Translate non-null triggers into a
    // monotonic token instead, so the null reset neither cancels nor restarts a play.
    var playToken by remember { mutableStateOf(0) }
    LaunchedEffect(triggerAt) {
        if (triggerAt != null) playToken++
    }

    LaunchedEffect(playToken) {
        if (playToken == 0) return@LaunchedEffect
        // Reset in a finally so a restart (or any cancellation) can never leave a
        // frozen frame on screen.
        try {
            haptics.success()
            progress = 0.0001f
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val elapsed = (now - start) / 1_000_000L
                val p = (elapsed.toFloat() / DURATION_MS.toFloat()).coerceIn(0f, 1f)
                progress = p
                if (p >= 1f) break
            }
        } finally {
            progress = 0f
        }
    }

    if (progress <= 0f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val p = progress
        val center = Offset(size.width / 2f, size.height / 2f)

        // Radial flash: peaks at p ≈ 0.15, fades to 0 by p ≈ 0.5.
        val flashAlpha = when {
            p < 0.15f -> p / 0.15f
            p < 0.5f -> 1f - (p - 0.15f) / 0.35f
            else -> 0f
        } * 0.35f
        if (flashAlpha > 0f) {
            val flashRadius = size.minDimension * (0.3f + 0.7f * p)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to AccentGlow.copy(alpha = flashAlpha),
                    1f to Accent.copy(alpha = 0f),
                    center = center,
                    radius = flashRadius,
                ),
                center = center,
                radius = flashRadius,
            )
        }

        // Particle ring expanding outward, fading out.
        val ringTravel = size.minDimension * 0.42f
        val particleAlpha = (1f - p) * 0.95f
        if (particleAlpha > 0f) {
            val r = particleRadius(p)
            for (i in directions.indices) {
                val d = directions[i]
                val pos = Offset(
                    x = center.x + d.x * ringTravel * easeOutCubic(p),
                    y = center.y + d.y * ringTravel * easeOutCubic(p),
                )
                drawCircle(
                    color = Accent.copy(alpha = particleAlpha),
                    radius = r,
                    center = pos,
                )
            }
        }

        // Expanding ring stroke.
        val ringP = p.coerceAtMost(0.85f) / 0.85f
        val ringRadius = size.minDimension * 0.12f + size.minDimension * 0.30f * ringP
        val ringAlpha = (1f - ringP) * 0.55f
        if (ringAlpha > 0f) {
            drawCircle(
                color = Accent.copy(alpha = ringAlpha),
                radius = ringRadius,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = (2f + 4f * (1f - ringP)).coerceAtLeast(1f),
                ),
            )
        }
    }
}

private fun particleRadius(p: Float): Float {
    // shrinks from 5px to 2px as the burst progresses
    return 5f - 3f * p
}

private fun easeOutCubic(t: Float): Float {
    val u = 1f - t
    return 1f - u * u * u
}
