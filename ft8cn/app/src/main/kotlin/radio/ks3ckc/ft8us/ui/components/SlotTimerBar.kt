package radio.ks3ckc.ft8us.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.withFrameMillis
import com.bg7yoz.ft8cn.timer.UtcTimer
import radio.ks3ckc.ft8us.theme.Accent
import radio.ks3ckc.ft8us.theme.Border
import radio.ks3ckc.ft8us.theme.GeistMonoFamily
import radio.ks3ckc.ft8us.theme.Signal
import radio.ks3ckc.ft8us.theme.TextMuted

@Composable
fun SlotTimerBar(
    activeTxSlot: Int,
    isActivated: Boolean,
    slotMillis: Long = 15_000L,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(UtcTimer.getSystemTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis {
                nowMs = UtcTimer.getSystemTime()
            }
        }
    }

    val slotMs = ((nowMs % slotMillis) + slotMillis) % slotMillis
    val progress = slotMs / slotMillis.toFloat()
    // Round the slot length UP so a 7.5s FT4 slot peaks at 8s, not 7 (integer
    // division would truncate the half-second).
    val maxSeconds = ((slotMillis + 999L) / 1000L).toInt()
    val secondsRemaining = (((slotMillis - slotMs) + 999L) / 1000L).toInt().coerceIn(0, maxSeconds)
    // Derive the slot index from the same slot length the bar is rendering, so
    // they can't disagree if the bar is ever driven by a non-global slot.
    val currentSlot = UtcTimer.sequential(nowMs, slotMillis.toInt())
    val fillColor = if (isActivated && currentSlot == activeTxSlot) Accent else Signal

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .drawBehind {
                    drawRect(color = Border, size = Size(size.width, size.height))
                    drawRect(
                        color = fillColor,
                        size = Size(size.width * progress, size.height),
                    )
                },
        )
        Text(
            text = "${secondsRemaining}s",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.02.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(26.dp),
        )
    }
}
