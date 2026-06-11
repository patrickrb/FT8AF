package radio.ks3ckc.ft8us.ui.settings

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure decision/format logic for the manual time-correction control. Kept out of
 * the Composable so it can be unit-tested directly (Compose/DrawScope code can't).
 *
 * The correction is a millisecond offset that drives `UtcTimer.delay`; positive
 * shifts the app's clock forward.
 *
 * The range has to cover the *device clock error*, not FT8's much tighter decode
 * tolerance: a phone that's been offline for a while (no NTP) can drift several
 * seconds, and the correction is what pulls that back toward 0. A field report of
 * a Samsung A50 needing over 3 s while offline is why this is ±5 s, not ±2 s.
 */

/** Inclusive bounds for the manual correction, in milliseconds. */
internal const val TIME_CORRECTION_MIN_MS = -5000
internal const val TIME_CORRECTION_MAX_MS = 5000

/** Coerce an arbitrary correction into the allowed range. */
internal fun clampCorrectionMs(ms: Int): Int =
    ms.coerceIn(TIME_CORRECTION_MIN_MS, TIME_CORRECTION_MAX_MS)

/** Apply a stepper nudge (e.g. +100 or -500 ms) and clamp to range. */
internal fun stepCorrectionMs(current: Int, deltaMs: Int): Int =
    clampCorrectionMs(current + deltaMs)

/**
 * Suggest a new correction from the average decode DT of the stations we're
 * hearing — the only time reference available offline.
 *
 * `avgDtSec` is WSJT-style DT (seconds): how far into our RX window the decoded
 * signals landed. If decodes cluster at a positive DT our window opened early
 * (clock fast), so we subtract that amount; a negative DT means we open late
 * (clock slow), so we add. The goal is to push the measured DT toward zero.
 *
 * Sign is verified on-device (apply, watch the live DT trend toward 0) — if it
 * worsens, flip the `-` here.
 */
internal fun suggestedCorrectionMs(currentDelayMs: Int, avgDtSec: Float): Int =
    clampCorrectionMs(currentDelayMs - (avgDtSec * 1000f).roundToInt())

/**
 * Human-readable offset, e.g. "+0.6 s", "-0.3 s", "0.0 s". Used for both the
 * current correction and the measured/suggested DT.
 */
internal fun formatOffsetMs(ms: Int): String {
    val sign = when {
        ms > 0 -> "+"
        ms < 0 -> "-"
        else -> ""
    }
    return String.format(Locale.US, "%s%.1f s", sign, abs(ms) / 1000.0)
}
