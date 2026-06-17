package radio.ks3ckc.ft8af.ui.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.Wordmark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val SPLASH_DURATION_MS = 1800L

@Composable
fun FT8AFSplashScreen(
    onSplashComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        delay(SPLASH_DURATION_MS)
        onSplashComplete()
    }

    val transition = rememberInfiniteTransition(label = "splash")
    val auraPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aura-phase",
    )
    val blinkPhase by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blink",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    0f to Color(0xFF1A2238),
                    0.55f to Color(0xFF0A1020),
                    1f to Color(0xFF04070E),
                )
            )
    ) {
        // Range rings + compass marks + amber halo backdrop.
        SplashBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Icon + animated pulse aura. Uses the launcher icon clipped to a circle
            // so the splash matches the system pre-splash exactly. AndroidView + ImageView
            // because the launcher is an adaptive icon (XML), which painterResource can't load.
            Box(contentAlignment = Alignment.Center) {
                PulseAura(phase = auraPhase, modifier = Modifier.size(220.dp))
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            setImageResource(R.mipmap.ic_launcher)
                        }
                    },
                    modifier = Modifier
                        .size(148.dp)
                        .clip(CircleShape),
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Wordmark(fontSize = 40.sp)

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.splash_tagline),
                color = TextPrimary.copy(alpha = 0.55f),
                fontSize = 12.sp,
                letterSpacing = 0.18.em,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Loading state.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Signal.copy(alpha = blinkPhase)),
                )
                LoadingDots(label = stringResource(R.string.splash_initializing), color = Accent.copy(alpha = 0.85f))
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.splash_version, GeneralVariables.VERSION, GeneralVariables.VERSION_CODE),
                color = Color(0xFF8A96B1).copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.08.em,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "ft8af.app",
                color = Color(0xFF8A96B1).copy(alpha = 0.45f),
                fontSize = 10.sp,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.08.em,
            )

            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
private fun SplashBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height * 0.38f

        // Amber center halo.
        val haloRadius = size.minDimension * 0.4f
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFFFAF5E).copy(alpha = 0.18f),
                0.6f to Color(0xFFFFAF5E).copy(alpha = 0.04f),
                1f to Color(0xFFFFAF5E).copy(alpha = 0f),
                center = Offset(centerX, centerY),
                radius = haloRadius,
            ),
            center = Offset(centerX, centerY),
            radius = haloRadius,
        )

        // Range rings (dashed, blue-grey).
        val ringStroke = Stroke(
            width = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f)),
        )
        val ringColor = Color(0xFF94AFDC).copy(alpha = 0.1f)
        floatArrayOf(80f, 160f, 240f, 320f).forEach { r ->
            drawCircle(
                color = ringColor,
                radius = r,
                center = Offset(centerX, centerY),
                style = ringStroke,
            )
        }

        // Compass NSEW marks at radius 175 from a slightly lower center (matches splash.jsx).
        val compassCenterY = centerY + size.height * 0.08f
        val compassRadius = 175f
        val compassColor = Color(0xFFFFAF5E).copy(alpha = 0.45f)
        listOf("N", "E", "S", "W").forEachIndexed { i, _ ->
            val theta = (i * 90 - 90) * PI.toFloat() / 180f
            val x = centerX + cos(theta) * compassRadius
            val y = compassCenterY + sin(theta) * compassRadius
            drawCircle(
                color = compassColor,
                radius = 2f,
                center = Offset(x, y),
            )
        }
    }
}

@Composable
private fun PulseAura(phase: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseR = size.minDimension * 0.34f
        for (i in 0..2) {
            val p = ((phase + i * 0.33f) % 1f)
            val r = baseR * (1f + p * 0.8f)
            val alpha = (1f - p) * 0.55f
            drawCircle(
                color = Accent.copy(alpha = alpha),
                radius = r,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun LoadingDots(label: String, color: Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    val tick by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dots-tick",
    )
    val dotCount = tick.toInt().coerceIn(0, 3)
    val dots = ".".repeat(dotCount)
    Text(
        text = "$label$dots",
        color = color,
        fontSize = 11.sp,
        fontFamily = GeistMonoFamily,
        letterSpacing = 0.12.em,
    )
}
