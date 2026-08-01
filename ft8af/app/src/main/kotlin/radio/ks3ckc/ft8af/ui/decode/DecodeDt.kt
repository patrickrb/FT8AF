package radio.ks3ckc.ft8af.ui.decode

import radio.ks3ckc.ft8af.ui.components.CLOCK_SYNC_FAIR_SEC
import java.util.Locale
import kotlin.math.abs

/**
 * DT — how far into our receive window a decoded signal actually started, in
 * seconds. WSJT-X shows this on every decode line, and it is the single most
 * useful number for diagnosing a clock problem.
 *
 * Read it as a fleet, not one at a time. Other stations are mostly synced, so a
 * *consistent* bias across every decode is almost certainly our own clock, while
 * one outlier is just a station with a bad clock (or a signal that arrived via a
 * long path). The mean of these is what drives the sync pill on the slot bar and
 * the correction suggestion in Time Sync settings; showing the per-decode value
 * as well is what lets an operator tell "everyone is at -1.3" (my clock) from
 * "one guy is at -1.3" (his clock).
 *
 * FT8 tolerates roughly ±2 s before decoding collapses, so anything past about
 * ±1 s is worth acting on.
 */

/**
 * A decode's DT, WSJT-X style: signed, one decimal, no unit — `-1.3`, `+0.2`,
 * `0.0`.
 *
 * Prefixed "DT" by the caller rather than suffixed with a unit, because the
 * metadata row already carries an "ago" time and a bare "-1.3 s" next to it
 * reads as another duration rather than an offset.
 *
 * A value that rounds to zero renders unsigned: "-0.0" is noise, and an operator
 * scanning a column of numbers for a sign should not have to discount it.
 */
internal fun formatDecodeDt(seconds: Float): String {
    if (seconds.isNaN() || seconds.isInfinite()) return "--"
    val rounded = Math.round(seconds * 10f) / 10f
    if (abs(rounded) < 0.05f) return "0.0"
    return String.format(Locale.US, "%+.1f", rounded)
}

/**
 * True when a decode's DT is far enough out to be worth the operator's eye.
 *
 * Used only to colour the label. Shares [CLOCK_SYNC_FAIR_SEC] with the slot
 * bar's sync pill rather than repeating its value, so a row can never call a
 * reading alarming while the averaged indicator above it still calls it fair —
 * the two would otherwise be free to drift apart the moment either is retuned.
 */
internal fun isDecodeDtNotable(seconds: Float): Boolean =
    !seconds.isNaN() && !seconds.isInfinite() && abs(seconds) > CLOCK_SYNC_FAIR_SEC
