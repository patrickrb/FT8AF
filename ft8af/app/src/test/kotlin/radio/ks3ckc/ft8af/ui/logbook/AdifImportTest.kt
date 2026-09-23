package radio.ks3ckc.ft8af.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the in-app ADIF import wiring: the per-record insert tally and its
 * mapping into the import dialog's UI state (see AdifImport.kt).
 */
class AdifImportTest {

    @Test
    fun tallyCountsNewInsertAsAdded() {
        val tally = AdifImportTally()
        tally.doAfterInsert(false, true)
        assertThat(tally.added).isEqualTo(1)
        assertThat(tally.updated).isEqualTo(0)
        assertThat(tally.invalid).isEqualTo(0)
    }

    @Test
    fun tallyCountsExistingMatchAsUpdated() {
        val tally = AdifImportTally()
        tally.doAfterInsert(false, false)
        assertThat(tally.added).isEqualTo(0)
        assertThat(tally.updated).isEqualTo(1)
        assertThat(tally.invalid).isEqualTo(0)
    }

    @Test
    fun tallyInvalidWinsOverNewFlag() {
        // DatabaseOpr reports an invalid record (no callsign) as
        // doAfterInsert(true, true) — the invalid flag must win so a rejected
        // record is never also counted as added.
        val tally = AdifImportTally()
        tally.doAfterInsert(true, true)
        assertThat(tally.added).isEqualTo(0)
        assertThat(tally.updated).isEqualTo(0)
        assertThat(tally.invalid).isEqualTo(1)
    }

    @Test
    fun tallyAccumulatesMixedSequence() {
        val tally = AdifImportTally()
        tally.doAfterInsert(false, true)   // added
        tally.doAfterInsert(false, true)   // added
        tally.doAfterInsert(false, false)  // updated
        tally.doAfterInsert(true, true)    // invalid
        tally.doAfterInsert(false, false)  // updated
        assertThat(tally.added).isEqualTo(2)
        assertThat(tally.updated).isEqualTo(2)
        assertThat(tally.invalid).isEqualTo(1)
    }

    @Test
    fun startedStateIsInProgressWithNoCounts() {
        val state = startedImportState()
        assertThat(state.inProgress).isTrue()
        assertThat(state.finished).isFalse()
        assertThat(state.total).isEqualTo(0)
        assertThat(state.failedMessage).isNull()
    }

    @Test
    fun finishedStateCarriesTallyCounts() {
        val tally = AdifImportTally()
        tally.doAfterInsert(false, true)
        tally.doAfterInsert(false, false)
        tally.doAfterInsert(true, true)
        val state = finishedImportState(3, tally)
        assertThat(state.inProgress).isFalse()
        assertThat(state.finished).isTrue()
        assertThat(state.total).isEqualTo(3)
        assertThat(state.position).isEqualTo(3)
        assertThat(state.added).isEqualTo(1)
        assertThat(state.updated).isEqualTo(1)
        assertThat(state.invalid).isEqualTo(1)
        assertThat(state.failedMessage).isNull()
    }

    @Test
    fun finishedStateWithEmptyFileHasZeroTotal() {
        val state = finishedImportState(0, AdifImportTally())
        assertThat(state.finished).isTrue()
        assertThat(state.total).isEqualTo(0)
        assertThat(state.added).isEqualTo(0)
    }

    @Test
    fun failedStateKeepsMessageAndIsDismissible() {
        val state = failedImportState("boom")
        assertThat(state.inProgress).isFalse()
        assertThat(state.finished).isTrue()
        assertThat(state.failedMessage).isEqualTo("boom")
    }

    @Test
    fun failedStateWithNullMessageIsNonNull() {
        // The dialog renders failedMessage directly; a null from the backend
        // must not disable the failure branch.
        val state = failedImportState(null)
        assertThat(state.failedMessage).isEqualTo("")
    }

    @Test
    fun importChangedLogOnlyWhenRecordsLanded() {
        val tally = AdifImportTally()
        assertThat(importChangedLog(finishedImportState(0, tally))).isFalse()
        tally.doAfterInsert(true, true) // invalid only — nothing to refresh
        assertThat(importChangedLog(finishedImportState(1, tally))).isFalse()
        tally.doAfterInsert(false, false) // an update lands
        assertThat(importChangedLog(finishedImportState(2, tally))).isTrue()

        val addedOnly = AdifImportTally()
        addedOnly.doAfterInsert(false, true)
        assertThat(importChangedLog(finishedImportState(1, addedOnly))).isTrue()
    }
}
