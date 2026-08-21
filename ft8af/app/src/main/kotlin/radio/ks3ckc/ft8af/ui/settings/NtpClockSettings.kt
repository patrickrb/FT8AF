package radio.ks3ckc.ft8af.ui.settings

import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R

/** Trims [raw] and falls back to the default NTP server when blank. */
internal fun normalizeNtpServer(raw: String): String =
    raw.trim().ifEmpty { GeneralVariables.DEFAULT_NTP_SERVER }

/**
 * Which "clock locked" string to show above the manual correction stepper in
 * [TimeSyncSettings], or null when manual entry is live. GPS takes precedence if both are
 * somehow on at once (should not normally happen — the toggle handlers enforce mutual
 * exclusion — but a hand-edited or pre-feature config restore could persist both).
 */
internal fun clockLockedMessageRes(disciplineFromGps: Boolean, disciplineFromNtp: Boolean): Int? = when {
    disciplineFromGps -> R.string.settings_time_correction_gps_locked
    disciplineFromNtp -> R.string.settings_time_correction_ntp_locked
    else -> null
}
