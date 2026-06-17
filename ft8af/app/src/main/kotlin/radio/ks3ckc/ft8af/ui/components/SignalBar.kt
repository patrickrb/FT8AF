package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import radio.ks3ckc.ft8af.theme.*

/**
 * 5-bar signal strength indicator.
 * SNR range: -30 (weak) to +5 (strong).
 */
@Composable
fun SignalBar(
    snr: Int,
    modifier: Modifier = Modifier,
    width: Dp = 32.dp,
    height: Dp = 14.dp,
) {
    val bars = 5
    val norm = ((snr + 25).toFloat() / 30f).coerceIn(0f, 1f)
    val filled = (norm * bars).toInt().coerceIn(0, bars)
    val color = when {
        snr >= -5 -> StatusConfirmed  // green
        snr >= -12 -> Signal          // cyan
        snr >= -18 -> Accent          // amber
        else -> StatusBad              // red
    }
    val emptyColor = Color(0x2694A3B8) // rgba(148,163,184,0.15)

    val barWidth = (width - (bars - 1).dp * 2) / bars
    val shape = RoundedCornerShape(1.dp)

    Row(
        modifier = modifier.height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until bars) {
            val barHeight = 4.dp + (height - 4.dp) * (i.toFloat() / (bars - 1).toFloat())
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .clip(shape)
                    .background(if (i < filled) color else emptyColor)
            )
        }
    }
}
