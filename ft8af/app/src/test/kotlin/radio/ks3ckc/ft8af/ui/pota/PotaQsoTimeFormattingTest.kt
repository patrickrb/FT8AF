package radio.ks3ckc.ft8af.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.TimeZone

/**
 * Coverage for the three time-formatting helpers behind the POTA contact row:
 *
 *   - parseQsoUtcMs — turn stored qso_date/time_on into an epoch instant so we
 *     can compute deltas. time_on is variable-width in the DB (HHMMSS, HHMM, or
 *     odd-length with a dropped leading zero), matching PotaQsoWindow.
 *   - formatQsoTimeAgo — the primary display: "just now", "5m ago", "2h ago",
 *     "3d ago". Anything older than ~a year falls back to the raw UTC string.
 *   - formatQsoTimeUtc — the long-press readout: HH:MMz.
 */
class PotaQsoTimeFormattingTest {

    private fun utcMs(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("GMT"))
        cal.clear()
        cal.set(y, mo - 1, d, h, mi, s)
        return cal.timeInMillis
    }

    // -- parseQsoUtcMs --------------------------------------------------------

    @Test
    fun parseQsoUtcMs_hhmmss_returnsGmtEpoch() {
        val ms = parseQsoUtcMs("20260831", "144530")
        assertThat(ms).isEqualTo(utcMs(2026, 8, 31, 14, 45, 30))
    }

    @Test
    fun parseQsoUtcMs_hhmm_padsSecondsToZero() {
        val ms = parseQsoUtcMs("20260831", "1445")
        assertThat(ms).isEqualTo(utcMs(2026, 8, 31, 14, 45, 0))
    }

    @Test
    fun parseQsoUtcMs_oddWidth_treatsAsMissingLeadingZero() {
        // "815" is the dropped-leading-zero form of "0815" — 08:15, per
        // PotaQsoWindow's ROW_STAMP rule.
        val ms = parseQsoUtcMs("20260831", "815")
        assertThat(ms).isEqualTo(utcMs(2026, 8, 31, 8, 15, 0))
    }

    @Test
    fun parseQsoUtcMs_emptyDate_returnsNull() {
        assertThat(parseQsoUtcMs("", "144530")).isNull()
    }

    @Test
    fun parseQsoUtcMs_shortDate_returnsNull() {
        assertThat(parseQsoUtcMs("2026083", "144530")).isNull()
    }

    @Test
    fun parseQsoUtcMs_emptyTime_returnsMidnight() {
        val ms = parseQsoUtcMs("20260831", "")
        assertThat(ms).isEqualTo(utcMs(2026, 8, 31, 0, 0, 0))
    }

    @Test
    fun parseQsoUtcMs_garbageTime_returnsNull() {
        assertThat(parseQsoUtcMs("20260831", "abcd")).isNull()
    }

    // -- formatQsoTimeAgo -----------------------------------------------------

    @Test
    fun formatQsoTimeAgo_under30s_showsJustNow() {
        val now = utcMs(2026, 8, 31, 14, 45, 30)
        val qso = utcMs(2026, 8, 31, 14, 45, 15)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("just now")
    }

    @Test
    fun formatQsoTimeAgo_secondsBucket_showsSeconds() {
        val now = utcMs(2026, 8, 31, 14, 46, 15)
        val qso = utcMs(2026, 8, 31, 14, 45, 30)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("45s ago")
    }

    @Test
    fun formatQsoTimeAgo_minutesBucket_showsMinutes() {
        val now = utcMs(2026, 8, 31, 14, 50, 30)
        val qso = utcMs(2026, 8, 31, 14, 45, 0)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("5m ago")
    }

    @Test
    fun formatQsoTimeAgo_hoursBucket_showsHours() {
        val now = utcMs(2026, 8, 31, 17, 0, 0)
        val qso = utcMs(2026, 8, 31, 14, 45, 0)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("2h ago")
    }

    @Test
    fun formatQsoTimeAgo_daysBucket_showsDays() {
        val now = utcMs(2026, 9, 3, 15, 0, 0)
        val qso = utcMs(2026, 8, 31, 14, 45, 0)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("3d ago")
    }

    @Test
    fun formatQsoTimeAgo_futureTimestamp_showsJustNow() {
        // Guard against clock skew — a QSO logged "in the future" (clock
        // drift, timezone bug, etc.) should not render "-2m ago".
        val now = utcMs(2026, 8, 31, 14, 45, 0)
        val qso = utcMs(2026, 8, 31, 14, 47, 0)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("just now")
    }

    @Test
    fun formatQsoTimeAgo_boundaryAt60Seconds_switchesToMinutes() {
        val now = utcMs(2026, 8, 31, 14, 46, 0)
        val qso = utcMs(2026, 8, 31, 14, 45, 0)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("1m ago")
    }

    @Test
    fun formatQsoTimeAgo_boundaryAt60Minutes_switchesToHours() {
        val now = utcMs(2026, 8, 31, 15, 45, 0)
        val qso = utcMs(2026, 8, 31, 14, 45, 0)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("1h ago")
    }

    @Test
    fun formatQsoTimeAgo_boundaryAt24Hours_switchesToDays() {
        val now = utcMs(2026, 9, 1, 14, 45, 0)
        val qso = utcMs(2026, 8, 31, 14, 45, 0)
        assertThat(formatQsoTimeAgo(qso, now)).isEqualTo("1d ago")
    }

    // -- formatQsoTimeUtc -----------------------------------------------------

    @Test
    fun formatQsoTimeUtc_hhmmss_showsHHMMz() {
        assertThat(formatQsoTimeUtc("144530")).isEqualTo("14:45z")
    }

    @Test
    fun formatQsoTimeUtc_hhmm_showsHHMMz() {
        assertThat(formatQsoTimeUtc("1445")).isEqualTo("14:45z")
    }

    @Test
    fun formatQsoTimeUtc_short_returnsRaw() {
        assertThat(formatQsoTimeUtc("14")).isEqualTo("14")
    }

    @Test
    fun formatQsoTimeUtc_empty_returnsEmpty() {
        assertThat(formatQsoTimeUtc("")).isEqualTo("")
    }
}
