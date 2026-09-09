package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [mergeRxLog] / [qsoLogKey] — the accumulation that makes the QSO panel's
 * RX history durable.
 *
 * Reported symptom: "sometimes the little QSO details pane would lose the RX messages I
 * had received and it would resize smaller."
 *
 * Cause: the panel owned its TX rows (a remembered state list) but *derived* its RX rows
 * from the shared decode list on every recomposition. That list is capped
 * (`trimToMessageCount` drops from the front at `MESSAGE_COUNT`, roughly an hour of
 * busy-band operating), wiped by the clear-every-cycle setting, and emptied outright by a
 * band change or the Clear button — so any of those retroactively erased conversation the
 * operator had already read, and the log box, which sizes to its content, visibly shrank.
 *
 * These tests pin the merge behaviour that fixes it. Pure JVM: no Android types.
 */
class QsoPanelRxHistoryTest {

    private fun rx(time: Long, text: String) =
        QsoLogEntry(QsoLogEntry.Direction.RX, time, text)

    private fun busy(time: Long, text: String) =
        QsoLogEntry(QsoLogEntry.Direction.BUSY, time, text)

    // ---------------------------------------------------------------
    // The bug: history must survive the shared list losing entries
    // ---------------------------------------------------------------

    @Test
    fun historySurvivesTheDecodeListBeingCleared() {
        // Three exchanges accumulated, then the shared decode list is wiped
        // (clear-every-cycle, band change, or the Clear button) so the next snapshot
        // derives nothing. The panel must still show what it already showed.
        var log = mergeRxLog(emptyList(), listOf(rx(1_000, "K1AF RA3XYZ -12")))
        log = mergeRxLog(log, listOf(rx(2_000, "K1AF RA3XYZ R-09")))
        log = mergeRxLog(log, listOf(rx(3_000, "K1AF RA3XYZ RR73")))
        assertThat(log).hasSize(3)

        val afterClear = mergeRxLog(log, emptyList())

        assertThat(afterClear).hasSize(3)
        assertThat(afterClear.map { it.messageText })
            .containsExactly("K1AF RA3XYZ -12", "K1AF RA3XYZ R-09", "K1AF RA3XYZ RR73")
            .inOrder()
    }

    @Test
    fun historySurvivesTheOldestEntriesBeingTrimmedAway() {
        // trimToMessageCount drops from the FRONT of the shared list, so a later
        // snapshot can be missing the QSO's earliest messages. They must not vanish
        // from the panel.
        val full = mergeRxLog(emptyList(), listOf(rx(1_000, "first"), rx(2_000, "second")))
        val trimmedSnapshot = listOf(rx(2_000, "second"))

        val merged = mergeRxLog(full, trimmedSnapshot)

        assertThat(merged.map { it.messageText }).containsExactly("first", "second").inOrder()
    }

    // ---------------------------------------------------------------
    // Duplicates are expected input, not an error
    // ---------------------------------------------------------------

    @Test
    fun cumulativeSnapshotsDoNotDuplicateRows() {
        // The decode list is cumulative, so every later snapshot re-presents the same
        // messages. Re-merging one must be a no-op.
        val first = mergeRxLog(emptyList(), listOf(rx(1_000, "K1AF RA3XYZ -12")))
        val again = mergeRxLog(first, listOf(rx(1_000, "K1AF RA3XYZ -12")))

        assertThat(again).hasSize(1)
    }

    @Test
    fun lateFullSlotPassRedeliveryDoesNotDuplicate() {
        // The late pass re-delivers a slot's messages alongside newly recovered ones:
        // the known row must not double, the new one must land.
        val afterFast = mergeRxLog(emptyList(), listOf(rx(1_000, "K1AF RA3XYZ -12")))
        val afterLate = mergeRxLog(
            afterFast,
            listOf(rx(1_000, "K1AF RA3XYZ -12"), rx(1_000, "W9ABC RA3XYZ -05")),
        )

        assertThat(afterLate).hasSize(2)
    }

    @Test
    fun sameTextInDifferentCyclesGetsItsOwnRow() {
        // Two real transmissions, e.g. an unanswered report repeated next cycle.
        // Mirrors how TX rows are logged per transmission rather than per unique text.
        val log = mergeRxLog(
            emptyList(),
            listOf(rx(1_000, "K1AF RA3XYZ RR73"), rx(16_000, "K1AF RA3XYZ RR73")),
        )

        assertThat(log).hasSize(2)
    }

    @Test
    fun directionIsPartOfIdentity() {
        // Same text and time but a different classification is a different row.
        val log = mergeRxLog(emptyList(), listOf(rx(1_000, "CQ RA3XYZ"), busy(1_000, "CQ RA3XYZ")))

        assertThat(log).hasSize(2)
        assertThat(qsoLogKey(rx(1_000, "x"))).isNotEqualTo(qsoLogKey(busy(1_000, "x")))
    }

    // ---------------------------------------------------------------
    // Identity contract the composable's write-skip depends on
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    // Metadata on a known row can still improve
    // ---------------------------------------------------------------

    @Test
    fun aBetterSnrForAKnownRowReplacesIt() {
        // FT8SignalListener.checkMessageSame upgrades a stored message's SNR in place
        // when a later pass decodes the same text better ("prefer known SNR over
        // unknown; when both are known, keep the higher"), so the same key legitimately
        // arrives again carrying a better report. Discarding it would pin the panel to
        // the first — often unknown — value for the rest of the QSO.
        val first = mergeRxLog(
            emptyList(),
            listOf(QsoLogEntry(QsoLogEntry.Direction.RX, 1_000, "K1AF RA3XYZ -12", null)),
        )
        val updated = mergeRxLog(
            first,
            listOf(QsoLogEntry(QsoLogEntry.Direction.RX, 1_000, "K1AF RA3XYZ -12", -9)),
        )

        assertThat(updated).hasSize(1)
        assertThat(updated.single().snr).isEqualTo(-9)
        assertThat(updated).isNotSameInstanceAs(first)
    }

    @Test
    fun anUnchangedRepeatDoesNotCountAsAnUpdate() {
        // The common case every cycle: identical row, including SNR. Must stay a no-op
        // so it costs no recomposition.
        val entry = QsoLogEntry(QsoLogEntry.Direction.RX, 1_000, "K1AF RA3XYZ -12", -9)
        val first = mergeRxLog(emptyList(), listOf(entry))

        assertThat(mergeRxLog(first, listOf(entry))).isSameInstanceAs(first)
    }

    @Test
    fun updatingARowDoesNotDisturbOrderOrCount() {
        var log = mergeRxLog(
            emptyList(),
            listOf(rx(1_000, "first"), rx(2_000, "second"), rx(3_000, "third")),
        )
        log = mergeRxLog(
            log,
            listOf(QsoLogEntry(QsoLogEntry.Direction.RX, 2_000, "second", -3)),
        )

        assertThat(log.map { it.messageText }).containsExactly("first", "second", "third").inOrder()
        assertThat(log[1].snr).isEqualTo(-3)
    }

    @Test
    fun mergeReturnsTheSameInstanceWhenNothingIsAdded() {
        // ActiveQsoPanel skips the snapshot write (and the recomposition it triggers)
        // on referential equality, so this is load-bearing, not an optimisation detail.
        val existing = mergeRxLog(emptyList(), listOf(rx(1_000, "a")))

        assertThat(mergeRxLog(existing, emptyList())).isSameInstanceAs(existing)
        assertThat(mergeRxLog(existing, listOf(rx(1_000, "a")))).isSameInstanceAs(existing)
    }

    @Test
    fun mergeReturnsANewInstanceWhenSomethingIsAdded() {
        val existing = mergeRxLog(emptyList(), listOf(rx(1_000, "a")))

        assertThat(mergeRxLog(existing, listOf(rx(2_000, "b")))).isNotSameInstanceAs(existing)
    }

    // ---------------------------------------------------------------
    // Ordering and the growth bound
    // ---------------------------------------------------------------

    @Test
    fun mergedRowsAreOrderedByTime() {
        // Out-of-order arrival is normal: the late pass recovers a message from earlier
        // in the slot after the fast pass has already delivered a later one.
        val log = mergeRxLog(
            mergeRxLog(emptyList(), listOf(rx(3_000, "third"))),
            listOf(rx(1_000, "first"), rx(2_000, "second")),
        )

        assertThat(log.map { it.messageText }).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun growthIsBoundedKeepingTheNewestRows() {
        // A target that never completes must not grow the log without limit.
        var log: List<QsoLogEntry> = emptyList()
        for (i in 1..MAX_RX_LOG_ENTRIES + 20) {
            log = mergeRxLog(log, listOf(rx(i.toLong() * 1_000, "msg$i")))
        }

        assertThat(log).hasSize(MAX_RX_LOG_ENTRIES)
        // Oldest dropped, newest kept — the recent exchange is what matters on screen.
        assertThat(log.first().messageText).isEqualTo("msg21")
        assertThat(log.last().messageText).isEqualTo("msg${MAX_RX_LOG_ENTRIES + 20}")
    }

    @Test
    fun trimAndAppendInOneMergeStillReturnsANewInstance() {
        // The case a size-only change check would miss: at the cap, a merge that appends
        // one row and drops one row leaves the size identical but the content different.
        var log: List<QsoLogEntry> = emptyList()
        for (i in 1..MAX_RX_LOG_ENTRIES) {
            log = mergeRxLog(log, listOf(rx(i.toLong() * 1_000, "msg$i")))
        }
        assertThat(log).hasSize(MAX_RX_LOG_ENTRIES)

        val merged = mergeRxLog(log, listOf(rx(999_000, "newest")))

        assertThat(merged).hasSize(MAX_RX_LOG_ENTRIES)
        assertThat(merged).isNotSameInstanceAs(log)
        assertThat(merged.last().messageText).isEqualTo("newest")
        assertThat(merged.first().messageText).isEqualTo("msg2")
    }
}
