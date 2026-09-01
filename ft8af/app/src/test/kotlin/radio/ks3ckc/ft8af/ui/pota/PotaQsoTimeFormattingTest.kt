package radio.ks3ckc.ft8af.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.TimeZone

/**
 * Coverage for the formatting helpers behind the POTA contact row:
 *
 *   - normalizeAdifTimeOn — widen a variable-width time_on to HHMMSS, the one
 *     padding rule shared by the parse and display paths (and PotaQsoWindow).
 *   - parseQsoUtcMs — turn stored qso_date/time_on into an epoch instant so we
 *     can compute deltas. time_on is variable-width in the DB (HHMMSS, HHMM, or
 *     odd-length with a dropped leading zero), matching PotaQsoWindow. Returns
 *     null for anything that is not a real calendar instant, which is the
 *     caller's cue to fall back to the raw UTC readout.
 *   - formatQsoTimeAgo — the primary display: "just now", "5m ago", "2h ago",
 *     "3d ago". There is no upper bucket; an old QSO keeps counting in days.
 *   - formatQsoTimeUtc — the long-press readout: HH:MMz.
 *   - formatContactDetails — the row's mode · band · grid subtitle.
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

    @Test
    fun parseQsoUtcMs_impossibleCalendarDate_returnsNull() {
        // Lenient SimpleDateFormat rolled 2026-02-31 forward into March and
        // showed a confident, wrong "ago" delta. It has to be un-datable so
        // the row falls back to the raw UTC readout.
        assertThat(parseQsoUtcMs("20260231", "1445")).isNull()
    }

    @Test
    fun parseQsoUtcMs_impossibleClockTime_returnsNull() {
        // Same for a rolled-over clock: "256000" used to become 01:00 the
        // following day.
        assertThat(parseQsoUtcMs("20260831", "256000")).isNull()
        assertThat(parseQsoUtcMs("20260831", "146500")).isNull()
    }

    @Test
    fun parseQsoUtcMs_realLeapDay_stillParses() {
        // Non-lenient must not over-reject: 2024 is a leap year.
        assertThat(parseQsoUtcMs("20240229", "1445"))
            .isEqualTo(utcMs(2024, 2, 29, 14, 45, 0))
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
    fun formatQsoTimeUtc_evenWidthShort_padsLikeRowStamp() {
        // "14" pads to 140000 in ROW_STAMP and in parseQsoUtcMs, so the
        // long-press readout has to agree rather than echoing the raw field.
        assertThat(formatQsoTimeUtc("14")).isEqualTo("14:00z")
    }

    @Test
    fun formatQsoTimeUtc_oddWidth_recoversDroppedLeadingZero() {
        // The bug: "815" (08:15 with the leading zero dropped) used to fall
        // through the length<4 guard and render as the raw "815".
        assertThat(formatQsoTimeUtc("815")).isEqualTo("08:15z")
    }

    @Test
    fun formatQsoTimeUtc_oddWidthFiveDigits_doesNotMisreadHhMm() {
        // The other half of the bug: "81530" used to render "81:53z" by
        // slicing the unnormalized string.
        assertThat(formatQsoTimeUtc("81530")).isEqualTo("08:15z")
    }

    @Test
    fun formatQsoTimeUtc_outOfRangeClock_returnsRaw() {
        // Nothing sensible to show for an impossible clock reading, and
        // parseQsoUtcMs rejects it too, so both paths agree on the raw value.
        assertThat(formatQsoTimeUtc("256000")).isEqualTo("256000")
        assertThat(formatQsoTimeUtc("14:45")).isEqualTo("14:45")
    }

    @Test
    fun formatQsoTimeUtc_empty_returnsEmpty() {
        assertThat(formatQsoTimeUtc("")).isEqualTo("")
    }

    // -- normalizeAdifTimeOn --------------------------------------------------

    @Test
    fun normalizeAdifTimeOn_matchesRowStampRule() {
        assertThat(normalizeAdifTimeOn("144530")).isEqualTo("144530")
        assertThat(normalizeAdifTimeOn("1445")).isEqualTo("144500")
        assertThat(normalizeAdifTimeOn("815")).isEqualTo("081500")
        assertThat(normalizeAdifTimeOn("81530")).isEqualTo("081530")
        assertThat(normalizeAdifTimeOn("")).isEqualTo("000000")
    }

    @Test
    fun normalizeAdifTimeOn_nonNumeric_returnsNull() {
        assertThat(normalizeAdifTimeOn("14:45")).isNull()
    }

    // -- formatContactDetails -------------------------------------------------

    @Test
    fun formatContactDetails_allPresent_joinsInOrder() {
        assertThat(formatContactDetails("FT8", "20m", "EM28"))
            .isEqualTo("FT8 · 20m · EM28")
    }

    @Test
    fun formatContactDetails_blankMiddleField_suppressesItsSeparator() {
        assertThat(formatContactDetails("FT8", "", "EM28")).isEqualTo("FT8 · EM28")
    }

    @Test
    fun formatContactDetails_blankLeadingAndTrailing_leavesNoOrphanSeparator() {
        assertThat(formatContactDetails("", "20m", "")).isEqualTo("20m")
    }

    @Test
    fun formatContactDetails_allBlank_isEmpty() {
        assertThat(formatContactDetails("", "", "")).isEmpty()
    }

    @Test
    fun formatContactDetails_whitespaceOnlyCountsAsBlank() {
        assertThat(formatContactDetails("  ", "20m", "	")).isEqualTo("20m")
    }
}
