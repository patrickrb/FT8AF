package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8af.ui.motion.MotionTokens
import radio.ks3ckc.ft8af.ui.motion.rememberHaptics

/**
 * Spring-driven press-scale + optional haptic.
 *
 * Drives a single [Animatable] via [graphicsLayer] (GPU only — no recomposition per frame).
 */
fun Modifier.pressableScale(
    enabled: Boolean = true,
    hapticOnPress: Boolean = true,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    if (hapticOnPress) haptics.tick()
                    scope.launch { scale.animateTo(pressedScale, MotionTokens.SpringSnappy) }
                    tryAwaitRelease()
                    scope.launch { scale.animateTo(1f, MotionTokens.SpringSnappy) }
                },
                onTap = { onClick() }
            )
        }
}

/**
 * Standalone one-shot pulse hook: when [trigger] changes, an `Animatable<Float>` from 0→1 over
 * [durationMs] is exposed via [content] for callers to draw a one-shot ring/expansion.
 *
 * Returns the current pulse value (0f when idle, 0..1 during animation).
 */
@Composable
fun rememberOneShotPulse(trigger: Any?, durationMs: Int = MotionTokens.DurSlow): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        anim.snapTo(0f)
        anim.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = durationMs,
                easing = MotionTokens.EasingStandard
            )
        )
    }
    return anim.value
}
