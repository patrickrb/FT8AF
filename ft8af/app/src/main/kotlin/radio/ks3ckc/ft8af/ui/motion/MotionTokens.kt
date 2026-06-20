package radio.ks3ckc.ft8af.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

object MotionTokens {

    val SpringSnappy = spring<Float>(
        dampingRatio = 0.65f,
        stiffness = 380f,
    )

    val SpringSmooth = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = 220f,
    )

    val SpringBouncy = spring<Float>(
        dampingRatio = 0.5f,
        stiffness = 300f,
    )

    val EasingStandard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EasingEmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EasingEmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val DurFast = 120
    const val DurMed = 240
    const val DurSlow = 400
    const val DurXSlow = 800
}
