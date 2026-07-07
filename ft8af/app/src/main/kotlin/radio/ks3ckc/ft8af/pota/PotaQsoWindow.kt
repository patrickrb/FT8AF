package radio.ks3ckc.ft8af.pota

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared SQL + formatting for scoping QSLTable rows to a POTA activation's time
 * window. Used by both [PotaActivationDao.getActivationQsos] (the in-app
 * contacts list) and PotaAdifExporter.buildActivationAdif (export/upload) so the
 * two always agree on which QSOs belong to an activation — the export bug this
 * came from was the exporter drifting from the DAO, so the window logic lives in
 * one place now.
 */
internal object PotaQsoWindow {

    /**
     * SQL expression producing a fixed-width `yyyyMMddHHmmss` stamp for a QSLTable
     * row, for string comparison against [stamp] bounds.
     *
     * `time_on` is stored variable-width: app-logged rows are HHMMSS, but
     * imported or hand-edited ADIF rows may carry HHMM or drop a leading zero
     * (e.g. `"815"` for 08:15). A naive `qso_date || time_on` would then compare
     * incorrectly against the 14-char bounds (`"20240601" || "815"` sorts as if
     * it were past 08:00 but the raw compare misplaces it relative to 09:00).
     * Normalize to 6 digits first — same rule DatabaseOpr uses for its dedupe
     * ORDER BY: pad even-width times to HHMMSS; prepend a zero to odd-width times
     * (a dropped leading zero) before padding.
     */
    const val ROW_STAMP =
        "(qso_date || CASE " +
            "WHEN time_on IS NULL OR time_on = '' THEN '000000' " +
            "WHEN length(time_on) % 2 = 1 THEN substr('0' || time_on || '000000', 1, 6) " +
            "ELSE substr(time_on || '000000', 1, 6) END)"

    /** Upper bound for an open (still-active) activation that has no end time. */
    const val OPEN_END = "99991231235959"

    /** Format an epoch-millis instant as the matching `yyyyMMddHHmmss` GMT stamp. */
    fun stamp(ms: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(ms))
}
