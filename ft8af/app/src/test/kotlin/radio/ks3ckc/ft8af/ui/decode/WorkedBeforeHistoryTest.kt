package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.log.PriorQso
import org.junit.Test

/**
 * Unit tests for the pure "Worked before" summariser logic. [PriorQso] is a
 * plain POJO and [summarizeWorkedBefore]/[formatQsoDate] touch no Android types,
 * so no Robolectric runner is needed.
 */
class WorkedBeforeHistoryTest {

    private fun qso(
        date: String,
        time: String = "120000",
        band: String? = "20m",
        mode: String? = "FT8",
    ) = PriorQso(date, time, band, mode)

    // ---- summarizeWorkedBefore ----

    @Test
    fun `empty log yields null so nothing renders`() {
        assertThat(summarizeWorkedBefore(emptyList())).isNull()
    }

    @Test
    fun `single QSO reports count 1 and a formatted recap line`() {
        val s = summarizeWorkedBefore(listOf(qso("20250612", "143000", "20m", "FT8")))!!
        assertThat(s.count).isEqualTo(1)
        assertThat(s.lastLine).isEqualTo("2025-06-12 · 20m · FT8")
        assertThat(s.bands).containsExactly("20m")
    }

    @Test
    fun `most recent QSO is chosen regardless of input order`() {
        val s = summarizeWorkedBefore(
            listOf(
                qso("20240101", "090000", "40m", "FT8"),
                qso("20260724", "010000", "15m", "FT4"),
                qso("20250612", "143000", "20m", "FT8"),
            ),
        )!!
        assertThat(s.count).isEqualTo(3)
        // 2026-07-24 is the newest, so its band/mode drive the recap line.
        assertThat(s.lastLine).isEqualTo("2026-07-24 · 15m · FT4")
    }

    @Test
    fun `same-day QSOs break the tie on time_on`() {
        val s = summarizeWorkedBefore(
            listOf(
                qso("20250612", "235900", "20m", "FT8"),
                qso("20250612", "000100", "40m", "FT8"),
            ),
        )!!
        assertThat(s.lastLine).isEqualTo("2025-06-12 · 20m · FT8")
    }

    @Test
    fun `distinct bands are kept in first-worked order`() {
        val s = summarizeWorkedBefore(
            listOf(
                qso("20240101", "090000", "40m"),
                qso("20250101", "090000", "20m"),
                qso("20250601", "090000", "40m"), // repeat 40m — deduped
                qso("20260101", "090000", "15m"),
            ),
        )!!
        assertThat(s.bands).containsExactly("40m", "20m", "15m").inOrder()
    }

    @Test
    fun `blank band and mode parts are skipped in the recap line`() {
        val s = summarizeWorkedBefore(listOf(qso("20250612", "120000", "  ", null)))!!
        assertThat(s.lastLine).isEqualTo("2025-06-12")
        assertThat(s.bands).isEmpty()
    }

    @Test
    fun `unparseable date is passed through so the recap is never empty`() {
        val s = summarizeWorkedBefore(listOf(qso("2025-06-12", "120000", "20m", "FT8")))!!
        assertThat(s.lastLine).isEqualTo("2025-06-12 · 20m · FT8")
    }

    // ---- formatQsoDate ----

    @Test
    fun `formatQsoDate turns YYYYMMDD into YYYY-MM-DD`() {
        assertThat(formatQsoDate("20250612")).isEqualTo("2025-06-12")
    }

    @Test
    fun `formatQsoDate leaves non-8-digit values untouched and trims`() {
        assertThat(formatQsoDate(" 2025 ")).isEqualTo("2025")
        assertThat(formatQsoDate("2025-06-12")).isEqualTo("2025-06-12")
        assertThat(formatQsoDate("abcdefgh")).isEqualTo("abcdefgh")
        assertThat(formatQsoDate(null)).isEqualTo("")
        assertThat(formatQsoDate("")).isEqualTo("")
    }
}
