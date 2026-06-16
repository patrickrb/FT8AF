package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import radio.ks3ckc.ft8af.ui.motion.MotionTokens

/**
 * Digit-by-digit rolling counter. Each digit is its own AnimatedContent so only the digits that
 * actually changed animate. Non-digit characters (commas, etc.) render statically.
 */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    formatter: (Int) -> String = { it.toString() },
) {
    val text = formatter(value)
    Row(modifier = modifier) {
        text.forEachIndexed { index, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val rollUp = targetState.toString().toIntOrNull()?.let { t ->
                        initialState.toString().toIntOrNull()?.let { i -> t > i }
                    } ?: true
                    val direction = if (rollUp) 1 else -1
                    val enter = slideInVertically(
                        animationSpec = tween(MotionTokens.DurMed, easing = MotionTokens.EasingStandard)
                    ) { fullHeight -> direction * fullHeight } + fadeIn(tween(MotionTokens.DurMed))
                    val exit = slideOutVertically(
                        animationSpec = tween(MotionTokens.DurMed, easing = MotionTokens.EasingStandard)
                    ) { fullHeight -> -direction * fullHeight } + fadeOut(tween(MotionTokens.DurFast))
                    enter togetherWith exit
                },
                label = "counter-digit-$index"
            ) { c ->
                Text(text = c.toString(), style = style)
            }
        }
    }
}
