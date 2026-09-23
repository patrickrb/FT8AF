package radio.ks3ckc.ft8af.ui.logbook

import com.k1af.ft8af.database.AfterInsertQSLData

/**
 * UI state for the in-app ADIF import dialog (progress while records stream in,
 * then the added / updated / invalid summary). Held as a nullable
 * `mutableStateOf` in LogbookScreen, mirroring SyncDialogState.
 */
internal data class AdifImportUiState(
    val inProgress: Boolean,
    val total: Int = 0,
    val position: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val invalid: Int = 0,
    val finished: Boolean = false,
    val failedMessage: String? = null,
)

/**
 * Tallies per-record insert outcomes during an ADIF import. DatabaseOpr's
 * insert callback reports (isInvalid, isNewQSL); an invalid record (no
 * callsign) also arrives with isNewQSL=true, so the invalid flag must win
 * before the new-vs-update split. Only touched from the single import thread.
 */
internal class AdifImportTally : AfterInsertQSLData {
    var added = 0
        private set
    var updated = 0
        private set
    var invalid = 0
        private set

    override fun doAfterInsert(isInvalid: Boolean, isNewQSL: Boolean) {
        when {
            isInvalid -> invalid++
            isNewQSL -> added++
            else -> updated++
        }
    }
}

internal fun startedImportState(): AdifImportUiState = AdifImportUiState(inProgress = true)

/** The terminal success state: progress complete, summary counts from the tally. */
internal fun finishedImportState(total: Int, tally: AdifImportTally): AdifImportUiState =
    AdifImportUiState(
        inProgress = false,
        total = total,
        position = total,
        added = tally.added,
        updated = tally.updated,
        invalid = tally.invalid,
        finished = true,
    )

/** The terminal failure state: dialog dismissible, showing [message]. */
internal fun failedImportState(message: String?): AdifImportUiState =
    AdifImportUiState(
        inProgress = false,
        finished = true,
        failedMessage = message ?: "",
    )

/**
 * Whether an import produced work worth refreshing the logbook for — any record
 * that was added or merged into an existing QSO. Invalid-only or empty imports
 * skip the reload.
 */
internal fun importChangedLog(state: AdifImportUiState): Boolean =
    state.added > 0 || state.updated > 0
