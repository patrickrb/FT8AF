package radio.ks3ckc.ft8us.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure display helpers extracted from [QsoRow] so the logbook's
 * recent entries render the real UTC time, a short date, a color-coded band meter,
 * and an un-clipped frequency. These are plain string transforms, so they run on the
 * bare JVM with no Robolectric.
 *
 * [formatQsoTime] previously sliced the date column (showing "20:26" from the year
 * 2026's "2026"); it now reads the normalized `time_on`.
 */
class LogbookRowLogicTest {

    @Test
    fun formatQsoTime_rendersHoursAndMinutes() {
        assertThat(formatQsoTime("202600")).isEqualTo("20:26")
        assertThat(formatQsoTime("2026")).isEqualTo("20:26")
        assertThat(formatQsoTime("083015")).isEqualTo("08:30")
    }

    @Test
    fun formatQsoTime_restoresDroppedLeadingZero() {
        // "81500" is 08:15:00 with the hour's leading zero dropped — normalizeTimeOn
        // must restore it rather than read "81" as the hour.
        assertThat(formatQsoTime("81500")).isEqualTo("08:15")
    }

    @Test
    fun formatQsoTime_blankOrNonNumericIsPlaceholder() {
        assertThat(formatQsoTime("")).isEqualTo("--:--")
        assertThat(formatQsoTime("abc")).isEqualTo("--:--")
    }

    @Test
    fun formatQsoDate_rendersShortDate() {
        assertThat(formatQsoDate("20260611")).isEqualTo("11 Jun")
        assertThat(formatQsoDate("20260101")).isEqualTo("1 Jan")
        assertThat(formatQsoDate("20251231")).isEqualTo("31 Dec")
    }

    @Test
    fun formatQsoDate_blankOrMalformedIsEmpty() {
        assertThat(formatQsoDate("")).isEqualTo("")
        assertThat(formatQsoDate("2026")).isEqualTo("")
        assertThat(formatQsoDate("20261301")).isEqualTo("") // month 13
    }

    @Test
    fun parseBandMeter_extractsMeterFromPackedColumn() {
        assertThat(parseBandMeter("20M(14.084 MHz)")).isEqualTo("20M")
        assertThat(parseBandMeter("20M")).isEqualTo("20M")
        assertThat(parseBandMeter("")).isEqualTo("")
    }

    @Test
    fun parseFreqMhz_extractsFrequencyFromPackedColumn() {
        assertThat(parseFreqMhz("20M(14.084 MHz)")).isEqualTo("14.084 MHz")
        assertThat(parseFreqMhz("20M")).isEqualTo("")
        assertThat(parseFreqMhz("")).isEqualTo("")
    }
}
