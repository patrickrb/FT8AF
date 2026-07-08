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
import com.k1af.ft8af.spectrum.AudioInputLevel
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
 * Classify a metering-window snapshot into the color-coded status shown next
 * to the meter. Delegates to [AudioInputLevel], the app's single RX
 * level classifier (issue #405): this strip and the legacy spectrum meter now
 * judge the same audio with the same peak/RMS dBFS thresholds, so the two UIs
 * can never disagree and the healthy-gain window is tuned in one place.
 */
internal fun classifyInputLevel(levels: InputAudioLevel.Levels?): AudioInputLevel.Status =
    if (levels == null) {
        AudioInputLevel.Status.SILENT
    } else {
        AudioInputLevel.fromPeakRms(levels.peak, levels.rms).status
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
    val status = classifyInputLevel(levels)
    val color = inputLevelColor(status)
    val statusText = stringResource(inputLevelStatusText(status))

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

/**
 * Status color: grey = no input, blue = too low, green = good, yellow = hot,
 * red = clipping. Same semantics as the legacy meter's AudioLevelDisplay
 * palette, in this theme's colors.
 */
internal fun inputLevelColor(status: AudioInputLevel.Status): Color = when (status) {
    AudioInputLevel.Status.SILENT -> TextMuted
    AudioInputLevel.Status.LOW -> StatusWorked
    AudioInputLevel.Status.GOOD -> StatusConfirmed
    AudioInputLevel.Status.HIGH -> StatusWarn
    AudioInputLevel.Status.CLIPPING -> StatusBad
}

/**
 * Status word resource. SILENT reuses the "too low" wording — the actionable
 * advice (turn the input up / check the cable) is the same, and the muted
 * color already distinguishes a dead input from a merely-quiet one.
 */
internal fun inputLevelStatusText(status: AudioInputLevel.Status): Int = when (status) {
    AudioInputLevel.Status.SILENT,
    AudioInputLevel.Status.LOW -> R.string.input_level_too_low
    AudioInputLevel.Status.GOOD -> R.string.input_level_good
    AudioInputLevel.Status.HIGH -> R.string.input_level_too_high
    AudioInputLevel.Status.CLIPPING -> R.string.input_level_clipping
}
