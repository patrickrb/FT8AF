package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.wave.InputAudioLevel
import radio.ks3ckc.ft8af.theme.BgSurface3
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.StatusWarn
import radio.ks3ckc.ft8af.theme.StatusWorked
import radio.ks3ckc.ft8af.theme.TextMuted
import kotlin.math.roundToInt

/**
 * RX input-level classification (issue #356). Thresholds are on the window
 * PEAK as a fraction of full scale:
 *  - below [INPUT_LEVEL_LOW] .................. too low
 *  - [INPUT_LEVEL_LOW]..[INPUT_LEVEL_HIGH] .... just right
 *  - above [INPUT_LEVEL_HIGH] ................. too high
 *  - at/above [INPUT_LEVEL_CLIP] .............. clipping (full-scale hit)
 */
internal const val INPUT_LEVEL_LOW = 0.25f
internal const val INPUT_LEVEL_HIGH = 0.75f
internal const val INPUT_LEVEL_CLIP = 0.99f

internal enum class InputLevelStatus { TOO_LOW, JUST_RIGHT, TOO_HIGH, CLIPPING }

/**
 * Classify a metering-window peak (1.0 = full scale) into the color-coded
 * status shown next to the meter. Boundary values 25% and 75% both count as
 * "just right" so the advertised 25–75% window is inclusive.
 */
internal fun classifyInputLevel(peak: Float): InputLevelStatus = when {
    peak >= INPUT_LEVEL_CLIP -> InputLevelStatus.CLIPPING
    peak > INPUT_LEVEL_HIGH -> InputLevelStatus.TOO_HIGH
    peak < INPUT_LEVEL_LOW -> InputLevelStatus.TOO_LOW
    else -> InputLevelStatus.JUST_RIGHT
}

/** Meter-bar fill fraction for a sample level, clamped to 0..1. */
internal fun inputLevelFraction(level: Float): Float = level.coerceIn(0f, 1f)

/** Numeric readout: level as an integer percent of full scale, 0..100. */
internal fun inputLevelPercent(level: Float): Int =
    (inputLevelFraction(level) * 100f).roundToInt()

/**
 * Compact live RX input-level meter for the waterfall bottom strip: an
 * RMS-filled bar with a peak tick, a percent readout of the window peak, and
 * a color-coded status word. All decision/geometry logic lives in the plain
 * functions above; this composable only draws.
 */
@Composable
internal fun InputLevelIndicator(
    levels: InputAudioLevel.Levels?,
    modifier: Modifier = Modifier,
) {
    val peak = levels?.peak ?: 0f
    val rms = levels?.rms ?: 0f
    val status = classifyInputLevel(peak)
    val color = inputLevelColor(status)
    val statusText = stringResource(
        when (status) {
            InputLevelStatus.TOO_LOW -> R.string.input_level_too_low
            InputLevelStatus.JUST_RIGHT -> R.string.input_level_good
            InputLevelStatus.TOO_HIGH -> R.string.input_level_too_high
            InputLevelStatus.CLIPPING -> R.string.input_level_clipping
        },
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.input_level_rx_label),
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.sp,
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Meter bar: RMS fill + peak tick.
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(BgSurface3),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(inputLevelFraction(rms))
                    .fillMaxHeight()
                    .background(color),
            )
            Box(
                modifier = Modifier
                    .offset(x = 44.dp * inputLevelFraction(peak) - 1.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(color),
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = stringResource(R.string.settings_percent_format, inputLevelPercent(peak)),
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 9.sp,
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = statusText,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.sp,
        )
    }
}

/** Status color: blue = too low, green = good, yellow = hot, red = clipping. */
internal fun inputLevelColor(status: InputLevelStatus): Color = when (status) {
    InputLevelStatus.TOO_LOW -> StatusWorked
    InputLevelStatus.JUST_RIGHT -> StatusConfirmed
    InputLevelStatus.TOO_HIGH -> StatusWarn
    InputLevelStatus.CLIPPING -> StatusBad
}
