package radio.ks3ckc.ft8af.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.withFrameMillis
import com.k1af.ft8af.timer.UtcTimer
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.TextMuted

@Composable
fun SlotTimerBar(
    slotMillis: Long = 15_000L,
    // Mean decode DT (seconds) for the live clock-sync pill; null until the first decode
    // cycle reports, in which case no pill is shown (the bar looks exactly as before).
    offsetSec: Float? = null,
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

    val state = slotTimerState(nowMs, slotMillis)
    val fillColor = slotBarColor(state.currentSlot)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (offsetSec != null) {
            ClockSyncIndicator(offsetSec = offsetSec)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .drawBehind {
                    drawRect(color = Border, size = Size(size.width, size.height))
                    drawRect(
                        color = fillColor,
                        size = Size(size.width * state.progress, size.height),
                    )
                },
        )
        Text(
            text = "${state.secondsRemaining}s",
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

/**
 * Color the slot bar by cycle parity, so the two alternating FT8 slots are visually
 * distinguishable at a glance: the even slot ([UtcTimer.sequential] == 0) is the warm
 * [Accent] (orange), the odd slot ([UtcTimer.sequential] == 1) is the cool [Signal] (blue).
 *
 * Extracted as a pure function so the parity→color mapping can be unit-tested without
 * Compose, and so it can't silently regress to a single color again.
 */
internal fun slotBarColor(currentSlot: Int): Color =
    if (currentSlot % 2 == 0) Accent else Signal

/** The fill fraction, countdown label, and slot index the bar renders for a given instant. */
internal data class SlotTimerState(
    val progress: Float,
    val secondsRemaining: Int,
    val currentSlot: Int,
)

/**
 * Pure slot-progress math, extracted so it can be unit-tested without Compose.
 *
 * [slotMillis] is a public composable parameter, so guard it: a 0 or negative value would
 * crash the modulo/division below (e.g. `nowMs % 0`). Fall back to the FT8 default so an
 * unexpected value can never take the UI down — the bar just renders a 15 s cycle instead.
 */
internal fun slotTimerState(nowMs: Long, slotMillis: Long): SlotTimerState {
    val slot = if (slotMillis > 0L) slotMillis else 15_000L
    val slotMs = ((nowMs % slot) + slot) % slot
    val progress = slotMs / slot.toFloat()
    // Round the slot length UP so a 7.5s FT4 slot peaks at 8s, not 7 (integer division
    // would truncate the half-second).
    val maxSeconds = ((slot + 999L) / 1000L).toInt()
    val secondsRemaining = (((slot - slotMs) + 999L) / 1000L).toInt().coerceIn(0, maxSeconds)
    // Derive the slot index from the same slot length the bar is rendering, so they can't
    // disagree if the bar is ever driven by a non-global slot.
    val currentSlot = UtcTimer.sequential(nowMs, slot.toInt())
    return SlotTimerState(progress, secondsRemaining, currentSlot)
}
