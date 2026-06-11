package radio.ks3ckc.ft8us.ui.logbook

import com.bg7yoz.ft8cn.log.QSLCallsignRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [sortQsosByDateTimeDesc], the pure ordering extracted from
 * RecentTab so the logbook's recent QSO list shows newest contacts first.
 *
 * The records expose [QSLCallsignRecord.lastTime] (YYYYMMDD) and
 * [QSLCallsignRecord.timeOn] (HHMMSS); the sort must order by the combined
 * date+time descending, so same-day QSOs order by time of day. QSLCallsignRecord
 * is a plain POJO, so these run on the bare JVM with no Robolectric.
 */
class LogbookSortTest {

    private fun record(callsign: String, date: String, time: String): QSLCallsignRecord {
        val r = QSLCallsignRecord()
        r.setCallsign(callsign)
        r.lastTime = date
        r.timeOn = time
        return r
    }

    private fun calls(records: List<QSLCallsignRecord>): List<String?> =
        records.map { it.callsign }

    @Test
    fun sortsByDateNewestFirst() {
        val sorted = sortQsosByDateTimeDesc(
            listOf(
                record("OLD", "20230101", "120000"),
                record("NEW", "20240615", "120000"),
                record("MID", "20231231", "120000"),
            ),
        )
        assertThat(calls(sorted)).containsExactly("NEW", "MID", "OLD").inOrder()
    }

    @Test
    fun sameDaySortsByTimeNewestFirst() {
        // The whole point of carrying time_on: a busy day must order by time.
        val sorted = sortQsosByDateTimeDesc(
            listOf(
                record("MORNING", "20240615", "081500"),
                record("EVENING", "20240615", "231000"),
                record("NOON", "20240615", "120000"),
            ),
        )
        assertThat(calls(sorted)).containsExactly("EVENING", "NOON", "MORNING").inOrder()
    }

    @Test
    fun missingTimeSortsAtStartOfDayButAfterPriorDay() {
        // A row with no recorded time pads to 000000 — earliest within its own
        // day, but still ahead of every QSO on an older day.
        val sorted = sortQsosByDateTimeDesc(
            listOf(
                record("PREV_DAY_LATE", "20240614", "235900"),
                record("NO_TIME", "20240615", ""),
                record("SAME_DAY_EARLY", "20240615", "000500"),
            ),
        )
        assertThat(calls(sorted))
            .containsExactly("SAME_DAY_EARLY", "NO_TIME", "PREV_DAY_LATE").inOrder()
    }

    @Test
    fun shortAdifTimeIsPaddedConsistently() {
        // ADIF imports may carry HHMM (4 digits); it must pad to HHMM00 so it
        // compares correctly against full HHMMSS values on the same day.
        val sorted = sortQsosByDateTimeDesc(
            listOf(
                record("HHMMSS_LATER", "20240615", "143005"),
                record("HHMM_EARLIER", "20240615", "1430"),
            ),
        )
        assertThat(calls(sorted)).containsExactly("HHMMSS_LATER", "HHMM_EARLIER").inOrder()
    }

    @Test
    fun emptyListReturnsEmpty() {
        assertThat(sortQsosByDateTimeDesc(emptyList())).isEmpty()
    }

    @Test
    fun sortIsStableForIdenticalTimestamps() {
        // Genuine ties (same date+time) keep their incoming order.
        val sorted = sortQsosByDateTimeDesc(
            listOf(
                record("FIRST", "20240615", "120000"),
                record("SECOND", "20240615", "120000"),
            ),
        )
        assertThat(calls(sorted)).containsExactly("FIRST", "SECOND").inOrder()
    }
}
