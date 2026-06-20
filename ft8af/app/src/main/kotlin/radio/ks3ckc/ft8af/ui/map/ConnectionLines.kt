package radio.ks3ckc.ft8af.ui.map

import androidx.compose.ui.graphics.Color
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.Signal

/**
 * A line drawn from the operator to a station. The "from" end is always the
 * operator (same for every line, and the canvas already knows its screen pos),
 * so only the far endpoint + style are carried here.
 *
 * Replaces the old behaviour where a line was only ever drawn to the *selected*
 * marker. GridTracker-style, we now persistently draw a solid line to the station
 * we're actively calling and dashed lines from anyone calling us.
 */
internal enum class LineStyle { SOLID_TX, DASHED_CALLING_ME }

internal data class ConnectionLine(
    val toLat: Double,
    val toLon: Double,
    val style: LineStyle,
    val color: Color,
    val callsign: String,
)

/** Minimal view of a station the line builder needs — [StationMarker] implements it. */
internal interface StationLine {
    val callsign: String
    val lat: Double
    val lon: Double
    val isToMe: Boolean
}

/**
 * Build the connection lines to draw:
 *  - a SOLID line to the station we're actively calling ([txTargetCallsign]), but
 *    only if it's among the decoded [stations] — we can only place a callsign we
 *    have a grid for. An unmatched target draws nothing.
 *  - a DASHED line from every station whose decode is directed at us, skipping the
 *    TX target so it isn't drawn twice.
 *
 * The caller resolves [txTargetCallsign] from the sequencer (null when calling CQ
 * or idle), so this stays pure and unit-testable.
 */
internal fun buildConnectionLines(
    stations: List<StationLine>,
    txTargetCallsign: String?,
): List<ConnectionLine> {
    // Defensive normalization: trim and treat the CQ placeholder (any case) as "no
    // target" even if a caller passes an un-normalized value.
    val target = txTargetCallsign?.trim()?.takeIf { it.isNotEmpty() && !it.equals("CQ", ignoreCase = true) }
    val out = ArrayList<ConnectionLine>()

    if (target != null) {
        stations.firstOrNull { it.callsign.equals(target, ignoreCase = true) }?.let {
            out.add(ConnectionLine(it.lat, it.lon, LineStyle.SOLID_TX, Accent, it.callsign))
        }
    }

    for (s in stations) {
        if (s.isToMe && (target == null || !s.callsign.equals(target, ignoreCase = true))) {
            out.add(ConnectionLine(s.lat, s.lon, LineStyle.DASHED_CALLING_ME, Signal, s.callsign))
        }
    }

    return out
}
