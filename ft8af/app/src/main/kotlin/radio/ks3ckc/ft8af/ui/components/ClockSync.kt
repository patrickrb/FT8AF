package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.StatusWarn
import radio.ks3ckc.ft8af.theme.TextMuted
import java.util.Locale
import kotlin.math.abs

/**
 * Clock-sync health derived from the mean decode DT (time offset, in seconds).
 *
 * FT8 tolerates only ~2 s of clock error before decoding collapses — both ways: a
 * mis-set clock stops you decoding others *and* pushes your transmissions to the edge
 * of everyone else's receive window so they can't decode you either. Yet the app never
 * surfaced this on the operating screen; the operator had to open Settings → Time Sync
 * to notice. The mean DT of the stations you decode is a good proxy for your own clock
 * offset: most stations are themselves synced, so a consistent bias across all of them
 * is almost certainly yours.
 */
internal enum class ClockSyncLevel { GOOD, FAIR, POOR, UNKNOWN }

/** |DT| at/below this (seconds) is a healthy, in-the-sweet-spot clock. */
internal const val CLOCK_SYNC_GOOD_SEC = 0.3f

/** |DT| at/below this (seconds) still decodes but is worth a resync; above it is POOR. */
internal const val CLOCK_SYNC_FAIR_SEC = 1.0f

/**
 * Classify the mean decode DT into a sync-health level. A `null` value means no decode
 * cycle has reported yet this session; a non-finite value is treated the same way so a bad
 * reading can never mis-color the pill.
 */
internal fun clockSyncLevel(offsetSec: Float?): ClockSyncLevel {
    if (offsetSec == null || offsetSec.isNaN() || offsetSec.isInfinite()) return ClockSyncLevel.UNKNOWN
    val mag = abs(offsetSec)
    return when {
        mag <= CLOCK_SYNC_GOOD_SEC -> ClockSyncLevel.GOOD
        mag <= CLOCK_SYNC_FAIR_SEC -> ClockSyncLevel.FAIR
        else -> ClockSyncLevel.POOR
    }
}

/**
 * Signed, one-decimal-second DT label (WSJT-X style), e.g. "+0.1 s", "-1.3 s", "0.0 s",
 * or an em dash when unknown. A value that rounds to zero renders unsigned (never "-0.0 s").
 */
internal fun clockSyncOffsetLabel(offsetSec: Float?): String {
    if (offsetSec == null || offsetSec.isNaN() || offsetSec.isInfinite()) return "—"
    val mag = String.format(Locale.US, "%.1f", abs(offsetSec))
    val sign = when {
        mag == "0.0" -> ""
        offsetSec < 0f -> "-"
        else -> "+"
    }
    return "$sign$mag s"
}

/** Green in-sync, amber worth-a-resync, red clock-off, grey unknown. */
internal fun clockSyncColor(level: ClockSyncLevel): Color = when (level) {
    ClockSyncLevel.GOOD -> StatusConfirmed
    ClockSyncLevel.FAIR -> StatusWarn
    ClockSyncLevel.POOR -> StatusBad
    ClockSyncLevel.UNKNOWN -> TextMuted
}

/** Short status word resource for accessibility / screen readers. */
internal fun clockSyncStatusText(level: ClockSyncLevel): Int = when (level) {
    ClockSyncLevel.GOOD -> R.string.clock_sync_good
    ClockSyncLevel.FAIR -> R.string.clock_sync_fair
    ClockSyncLevel.POOR -> R.string.clock_sync_poor
    ClockSyncLevel.UNKNOWN -> R.string.clock_sync_unknown
}

/**
 * Which automatic clock source, if any, is disciplining the clock right now — shown as a
 * small badge on the pill so the operator can see the DT is being handled for them.
 * GPS or NTP wins over self-sync when both are on, because the estimator stands down
 * while either discipline is active (see ClockSelfSync.mayRun). GPS and NTP are mutually
 * exclusive in the settings UI; GPS is listed first only as the tie-break for a
 * hand-edited config. Null means manual only.
 */
internal fun clockSyncAutoBadge(
    autoSyncFromDecodes: Boolean,
    disciplineFromGps: Boolean,
    disciplineFromNtp: Boolean = false,
): Int? =
    when {
        disciplineFromGps -> R.string.clock_sync_badge_gps
        disciplineFromNtp -> R.string.clock_sync_badge_ntp
        autoSyncFromDecodes -> R.string.clock_sync_badge_auto
        else -> null
    }

/**
 * Compact clock-sync pill for the slot-timer bar: a color-coded dot, the "DT" label
 * operators know from WSJT-X, the signed offset, and — when something automatic owns the
 * clock — an AUTO/GPS badge. All decision/format logic lives in the plain functions above;
 * this composable only draws and wires accessibility.
 */
@Composable
internal fun ClockSyncIndicator(
    offsetSec: Float?,
    modifier: Modifier = Modifier,
    autoSyncFromDecodes: Boolean = false,
    disciplineFromGps: Boolean = false,
    disciplineFromNtp: Boolean = false,
) {
    val level = clockSyncLevel(offsetSec)
    val color = clockSyncColor(level)
    val offsetLabel = clockSyncOffsetLabel(offsetSec)
    val statusWord = stringResource(clockSyncStatusText(level))
    val label = stringResource(R.string.clock_sync_label)
    val badgeRes = clockSyncAutoBadge(autoSyncFromDecodes, disciplineFromGps, disciplineFromNtp)
    val badge = badgeRes?.let { stringResource(it) }
    val cd = if (badge == null) {
        stringResource(R.string.clock_sync_cd, offsetLabel, statusWord)
    } else {
        stringResource(R.string.clock_sync_cd_auto, offsetLabel, statusWord, badge)
    }

    Row(
        modifier = modifier.semantics { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.sp,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = offsetLabel,
            color = color,
            fontFamily = GeistMonoFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (badge != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = badge,
                color = TextMuted,
                fontFamily = GeistMonoFamily,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.06.sp,
            )
        }
    }
}
