package radio.ks3ckc.ft8af.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.ft8transmit.FunctionOfTransmit
import com.k1af.ft8af.ft8transmit.QueuedCaller
import radio.ks3ckc.ft8af.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Data class representing a single message in the QSO log.
 */
internal data class QsoLogEntry(
    val direction: Direction,
    val utcTime: Long,
    val messageText: String,
    val snr: Int? = null,
) {
    enum class Direction { TX, RX, BUSY }
}

/**
 * Build the ordered QSO conversation log for [displayCallsign] from the decoded
 * [messageList] plus our own synthesized TX entries ([synthTx]).
 *
 * Classification:
 *  - RX   — from the target, addressed to us.
 *  - BUSY — from the target, addressed to someone else (context for the hunter).
 *  - TX   — our own transmissions, sourced *solely* from [synthTx]. Own-callsign
 *           loopback echoes never appear here because they are filtered upstream
 *           in MainViewModel.afterDecode (see OwnTxEchoFilter), so there is no
 *           decoded-list TX branch and no de-dup against loopback to do.
 *
 * Extracted from the composable so the (pure) classification/ordering is unit
 * testable. Entries are returned sorted ascending by [QsoLogEntry.utcTime].
 */
internal fun buildQsoLog(
    messageList: List<Ft8Message>?,
    displayCallsign: String,
    myCallsign: String,
    synthTx: List<QsoLogEntry>,
): List<QsoLogEntry> {
    if (displayCallsign.isEmpty()) return emptyList()

    val entries = mutableListOf<QsoLogEntry>()
    messageList?.forEach { msg ->
        // getCallsignFrom() handles null and strips angle brackets from
        // hashed callsigns (e.g. "<CALL>" → "CALL") so they match displayCallsign. (#255)
        val from = msg.getCallsignFrom()
        // Only the target station's traffic appears in this panel. Own-callsign
        // loopback (from == us) is filtered upstream (OwnTxEchoFilter) and there
        // is deliberately no decoded-list TX branch, so anything not from the
        // target is skipped here — TX rows come solely from synthTx below.
        if (!from.equals(displayCallsign, ignoreCase = true)) return@forEach

        // Skip CQ/DE/QRZ messages — the target is calling for contacts, not
        // working someone else, so they are neither RX nor BUSY. (#254)
        // Guard: checkIsCQ() calls callsignTo.trim() which NPEs on null.
        if (msg.callsignTo != null && msg.checkIsCQ()) return@forEach

        val to = msg.callsignTo ?: ""
        val toIsMe = to.equals(myCallsign, ignoreCase = true) ||
            GeneralVariables.checkIsMyCallsign(to)

        // RX when the target is calling us, BUSY when it's working someone else.
        entries.add(
            QsoLogEntry(
                direction = if (toIsMe) QsoLogEntry.Direction.RX else QsoLogEntry.Direction.BUSY,
                utcTime = msg.utcTime,
                messageText = msg.getMessageText(),
                snr = if (msg.hasSnr()) msg.snr else null,
            )
        )
    }

    // Our own transmissions — the sole source of TX rows.
    entries.addAll(synthTx)

    return entries.sortedBy { it.utcTime }
}

/**
 * Upper bound on the accumulated RX log for a single target, so a QSO that never
 * completes (a stuck target on a busy frequency) can't grow the list without limit.
 * Far above any real exchange — a standard QSO is 4-6 rows, and a stubborn one with
 * repeats is a couple of dozen.
 */
internal const val MAX_RX_LOG_ENTRIES = 100

/**
 * Stable identity for one conversation row, used to de-duplicate across snapshots.
 *
 * <p>Time is part of the key on purpose: a station that sends the same text in two
 * different cycles produced two real transmissions and gets two rows, matching how TX
 * rows are logged per transmission rather than per unique message.
 */
internal fun qsoLogKey(entry: QsoLogEntry): String =
    "${entry.direction}|${entry.utcTime}|${entry.messageText}"

/**
 * Merge freshly-decoded rows into the rows this panel has already shown.
 *
 * <p>This is what makes the panel's RX history durable. The decode list it is derived
 * from is shared, capped, and periodically emptied — `trimToMessageCount` drops from the
 * front once it hits `MESSAGE_COUNT` (about an hour of busy-band operating), the
 * clear-every-cycle setting wipes it at each slot boundary, a band change or the Clear
 * button empties it outright. Re-deriving the conversation from it on every recomposition
 * meant any of those retroactively erased messages the operator had already read, and the
 * log box — which sizes to its content — visibly shrank. TX rows never had this problem
 * because they live in the composable's own state; now RX rows don't either.
 *
 * <p>Duplicates are expected input, not an error: the decode list is cumulative, so the
 * same message reappears in every later snapshot, and the late full-slot pass re-delivers
 * a slot's messages alongside newly recovered ones.
 *
 * @param existing rows already accumulated for the current target
 * @param incoming rows just derived from the live decode list
 * @return the union, ascending by time, trimmed to [MAX_RX_LOG_ENTRIES] newest
 */
internal fun mergeRxLog(
    existing: List<QsoLogEntry>,
    incoming: List<QsoLogEntry>,
): List<QsoLogEntry> {
    if (incoming.isEmpty()) return existing
    val seen = existing.mapTo(HashSet()) { qsoLogKey(it) }
    val merged = ArrayList(existing)
    incoming.forEach { if (seen.add(qsoLogKey(it))) merged.add(it) }
    if (merged.size == existing.size) return existing
    merged.sortBy { it.utcTime }
    if (merged.size <= MAX_RX_LOG_ENTRIES) return merged
    return merged.subList(merged.size - MAX_RX_LOG_ENTRIES, merged.size).toList()
}

/**
 * Outcome of one evaluation of the synthesized-TX-log effect.
 *
 * @property shouldLog            append a TX row now (with the current transmit message)
 * @property pendingAfter         carry a "still owe a log for this transmission" flag forward
 * @property wasTransmittingAfter the transmitting state to remember for the next evaluation
 */
internal data class TxLogDecision(
    val shouldLog: Boolean,
    val pendingAfter: Boolean,
    val wasTransmittingAfter: Boolean,
)

/**
 * Decide whether to append one TX row to the mini-log, given the previous and current
 * transmit state. We log exactly **one row per transmission** — keyed off the rising edge
 * of [isTransmitting] — rather than de-duplicating by message text. That's the fix for
 * "repeated RR73s only showed once": each time a (possibly identical) message is sent in a
 * new cycle it is a fresh transmission and gets its own row.
 *
 * A "pending" flag bridges the case where [isTransmitting] flips true a frame before the
 * transmit-message string is populated: the rising edge arms a pending log, and we emit the
 * row on the first subsequent evaluation that actually has a non-empty message (and a live
 * target). The pending flag clears when the row is logged or when transmit ends, so it can
 * never leak a duplicate into the next transmission.
 *
 * @param wasTransmitting transmitting state at the previous evaluation
 * @param isTransmitting  transmitting state now
 * @param pending         whether a log is already owed for the in-flight transmission
 * @param message         the current transmit-message text (empty until populated)
 * @param hasTarget       whether a real target callsign is set (CQ has no mini-log)
 */
internal fun decideTxLog(
    wasTransmitting: Boolean,
    isTransmitting: Boolean,
    pending: Boolean,
    message: String,
    hasTarget: Boolean,
): TxLogDecision {
    val armed = pending || (isTransmitting && !wasTransmitting) // rising edge arms a new log
    val shouldLog = armed && isTransmitting && message.isNotEmpty() && hasTarget
    val pendingAfter = when {
        !isTransmitting -> false   // transmit ended — drop any unfulfilled pending log
        shouldLog -> false         // just logged this transmission
        else -> armed              // armed but message not ready yet; keep waiting
    }
    return TxLogDecision(shouldLog, pendingAfter, isTransmitting)
}

/**
 * Collapsible panel showing the active QSO with live RX/TX message history
 * and TX message selector buttons.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ActiveQsoPanel(
    mainViewModel: MainViewModel,
    expanded: Boolean,
    onCollapse: () -> Unit = {},
    onReopenSheet: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val toCallsign by mainViewModel.ft8TransmitSignal.mutableToCallsign.observeAsState()
    val isActivated by mainViewModel.ft8TransmitSignal.mutableIsActivated.observeAsState(false)
    val isTransmitting by mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observeAsState(false)
    val functions by mainViewModel.ft8TransmitSignal.mutableFunctions.observeAsState(arrayListOf())
    val functionOrder by mainViewModel.ft8TransmitSignal.mutableFunctionOrder.observeAsState(6)
    val messageList by mainViewModel.mutableFt8MessageList.observeAsState(arrayListOf())
    val transmittingMessage by mainViewModel.ft8TransmitSignal.mutableTransmittingMessage.observeAsState("")
    val callerQueue by mainViewModel.ft8TransmitSignal.mutableCallerQueue.observeAsState(arrayListOf())

    val liveCallsign = toCallsign?.callsign ?: "CQ"

    // displayCallsign is the live target when one is set, null when calling CQ.
    // We used to remember the last non-CQ callsign across resetToCQ() so the
    // panel "stayed visible" after a QSO completed — but that made the panel
    // lie ("Waiting for W1XYZ" while we were actually back to CQ). Now we
    // just show real state: either a real target or the CQ state below.
    val displayCallsign = liveCallsign.takeIf { it != "CQ" && it.isNotEmpty() }
    val hasTarget = displayCallsign != null
    val isCallingCq = isActivated && !hasTarget
    // Hunt mode listens for someone else's CQ (silent) rather than transmitting
    // CQ. The Hunt+CQ hybrid (huntCallsCQ) does transmit CQ, so "Calling CQ" is
    // correct there; only the pure autoFollowCQ case is a listening/hunting state.
    val isHunting = isCallingCq && GeneralVariables.autoFollowCQ && !GeneralVariables.huntCallsCQ

    // Synthesized TX log: append the message we're currently transmitting the moment TX
    // begins, so the operator sees it without waiting for the loopback decode (15–30s) to
    // catch up. We log one row per transmission (the rising edge of isTransmitting) rather
    // than de-duplicating by text, so repeated messages — e.g. several unanswered RR73s —
    // each get their own row. State resets per target. See decideTxLog for the rule.
    //
    // NOTE: no key parameter on remember — keying on displayCallsign caused the TX log
    // to be lost on tab switches when LiveData observers briefly emit null. Instead we
    // track the target manually and clear only on genuine target changes. (#250)
    val synthTxLog = remember { mutableStateListOf<QsoLogEntry>() }
    var wasTransmitting by remember { mutableStateOf(false) }
    var pendingTxLog by remember { mutableStateOf(false) }
    var synthTxTarget by remember { mutableStateOf<String?>(null) }

    // Accumulated RX/BUSY rows for the current target, held here for the same reason
    // synthTxLog is: the panel must not lose conversation it has already shown. These
    // used to be re-derived from the shared decode list on every recomposition, so a
    // trim, a clear-every-cycle wipe, a band change, or the Clear button silently
    // erased them mid-QSO and the log box shrank. See mergeRxLog.
    val rxLog = remember { mutableStateListOf<QsoLogEntry>() }

    // Clear TX state when the target genuinely changes to a different station.
    // Also handles the same-callsign-new-QSO edge case: when a QSO ends
    // (displayCallsign → null for > 500ms) and the same station is worked
    // again, the old TX rows must not carry over. A short delay distinguishes
    // real QSO ends from tab-switch flickers (LiveData re-subscription emits
    // null for 1–2 frames). LaunchedEffect cancels the pending delay when the
    // key changes, so flickers never clear synthTxTarget. (#250 follow-up)
    LaunchedEffect(displayCallsign) {
        if (displayCallsign != null) {
            if (displayCallsign != synthTxTarget) {
                synthTxLog.clear()
                rxLog.clear()
                wasTransmitting = false
                pendingTxLog = false
            }
            synthTxTarget = displayCallsign
        } else if (synthTxTarget != null) {
            // Target went null (CQ reset). Wait briefly — tab-switch flickers
            // resolve within a frame (~16ms). If still null after the delay,
            // it's a real QSO end; clear synthTxTarget so a subsequent QSO
            // with the same callsign is treated as fresh.
            kotlinx.coroutines.delay(500)
            synthTxTarget = null
        }
    }

    LaunchedEffect(isTransmitting, transmittingMessage, displayCallsign) {
        val msg = transmittingMessage.orEmpty()
        val decision = decideTxLog(
            wasTransmitting = wasTransmitting,
            isTransmitting = isTransmitting,
            pending = pendingTxLog,
            message = msg,
            hasTarget = displayCallsign != null,
        )
        wasTransmitting = decision.wasTransmittingAfter
        pendingTxLog = decision.pendingAfter
        if (decision.shouldLog) {
            synthTxLog.add(
                QsoLogEntry(
                    direction = QsoLogEntry.Direction.TX,
                    utcTime = System.currentTimeMillis(),
                    messageText = msg,
                )
            )
        }
    }

    // The target the conversation log belongs to. Falls back to synthTxTarget — which
    // LaunchedEffect(displayCallsign) only clears after a 500ms settle — so the same
    // LiveData null-flicker on tab switches that #250 fixed for the TX rows can no
    // longer blank the RX rows either. A genuine QSO end still clears both, one target
    // change later, exactly as before.
    val logTarget = displayCallsign ?: synthTxTarget

    // Fold this snapshot's decodes for the target into the durable RX log. Only new
    // rows survive the merge, so the cumulative decode list re-presenting the same
    // messages every cycle is a no-op rather than a source of duplicates.
    LaunchedEffect(messageList, messageList?.size, logTarget) {
        val target = logTarget ?: return@LaunchedEffect
        val incoming = buildQsoLog(
            messageList = messageList,
            displayCallsign = target,
            myCallsign = GeneralVariables.myCallsign ?: "",
            synthTx = emptyList(),
        )
        val existing = rxLog.toList()
        val merged = mergeRxLog(existing, incoming)
        // mergeRxLog returns the caller's OWN instance when it added nothing, so this
        // identity check keeps snapshot writes (and the recomposition each triggers) to
        // real changes. Size alone would miss a merge that both added and trimmed at
        // MAX_RX_LOG_ENTRIES.
        if (merged !== existing) {
            rxLog.clear()
            rxLog.addAll(merged)
        }
    }

    // The conversation shown: durable RX/BUSY rows plus our own TX rows, in time order.
    // Computed inline rather than remember()d — these are snapshot state lists, so
    // reading them here registers the dependency and any change recomposes. A remember
    // keyed on sizes would miss a merge that trims as it appends.
    val qsoMessages: List<QsoLogEntry> =
        if (logTarget == null) emptyList()
        else (rxLog + synthTxLog).sortedBy { it.utcTime }

    AnimatedVisibility(
        visible = expanded && (hasTarget || isActivated),
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .drawBehind {
                    drawLine(
                        color = Border,
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = 1f,
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Station header. When a sheet exists but the user has
            // minimized it, the header acts as the "reopen" affordance.
            StationHeader(
                targetCallsign = when {
                    isHunting -> stringResource(R.string.qsopanel_hunting)
                    isCallingCq -> stringResource(R.string.qsopanel_calling_cq)
                    displayCallsign == null -> stringResource(R.string.qsopanel_searching)
                    isTransmitting -> stringResource(R.string.qsopanel_qsoing_with, displayCallsign)
                    else -> stringResource(R.string.qsopanel_waiting_for, displayCallsign)
                },
                snr = if (displayCallsign != null) toCallsign?.snr else null,
                onClick = if (displayCallsign != null) onReopenSheet else null,
                onLog = if (displayCallsign != null) {
                    {
                        mainViewModel.ft8TransmitSignal.forceLogAndMoveOn()
                        mainViewModel.qsoSheetCallsign.postValue(null)
                        mainViewModel.qsoSheetMinimized.postValue(false)
                    }
                } else null,
                onClear = if (displayCallsign != null) {
                    {
                        mainViewModel.ft8TransmitSignal.userResetToCQ()
                        mainViewModel.qsoSheetCallsign.postValue(null)
                        mainViewModel.qsoSheetMinimized.postValue(false)
                    }
                } else null,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Message log
            MessageLog(
                entries = qsoMessages,
                transmittingMessage = transmittingMessage.orEmpty(),
                isTransmitting = isTransmitting,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // TX message selector
            TxSelector(
                functions = functions ?: arrayListOf(),
                currentOrder = functionOrder,
                onSelectOrder = { order ->
                    mainViewModel.ft8TransmitSignal.setCurrentFunctionOrder(order)
                    mainViewModel.ft8TransmitSignal.mutableFunctionOrder.postValue(order)
                },
            )

            // Caller queue display — tap a callsign to log current QSO and work them next
            CallerQueueBar(
                queue = callerQueue ?: arrayListOf(),
                onCallerTap = if (displayCallsign != null) { callsign ->
                    mainViewModel.ft8TransmitSignal.forceLogAndMoveOn(callsign)
                    mainViewModel.qsoSheetCallsign.postValue(null)
                    mainViewModel.qsoSheetMinimized.postValue(false)
                } else null,
            )
        }
    }
}

@Composable
private fun StationHeader(
    targetCallsign: String,
    snr: Int?,
    onClick: (() -> Unit)? = null,
    onLog: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                text = "QSO",
                color = TextFaint,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.04.sp,
            )
            Text(
                text = targetCallsign,
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
            )
            if (snr != null) {
                Text(
                    text = if (snr >= 0) "+${snr}dB" else "${snr}dB",
                    color = Signal,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onClick != null) {
                Text(
                    text = stringResource(R.string.qsopanel_tap_to_view),
                    color = Accent,
                    fontSize = 10.sp,
                    fontFamily = GeistMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (onLog != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SignalSoft)
                        .clickable(onClick = onLog)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.qsopanel_log_action),
                        color = Signal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                        letterSpacing = 0.04.sp,
                    )
                }
            }
            if (onClear != null) {
                // Tap target uses its own Box so the parent header's reopen
                // clickable doesn't swallow this gesture.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FT8AFIcons.Close(color = TextMuted, size = 14.dp)
                }
            }
        }
    }
}

@Composable
private fun MessageLog(
    entries: List<QsoLogEntry>,
    transmittingMessage: String,
    isTransmitting: Boolean,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    if (entries.isEmpty()) {
        val placeholder = if (isTransmitting && transmittingMessage.isNotEmpty()) {
            stringResource(R.string.qsopanel_tx_message, transmittingMessage)
        } else {
            stringResource(R.string.qsopanel_waiting_messages)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = placeholder,
                color = if (isTransmitting && transmittingMessage.isNotEmpty()) Accent else TextFaint,
                fontSize = 11.sp,
                fontFamily = GeistMonoFamily,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BgApp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(entries) { entry ->
                MessageLogRow(entry)
            }
        }
    }
}

@Composable
private fun MessageLogRow(entry: QsoLogEntry) {
    val utcFormat = remember {
        SimpleDateFormat("HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val timeStr = remember(entry.utcTime) {
        utcFormat.format(Date(entry.utcTime))
    }

    val isTx = entry.direction == QsoLogEntry.Direction.TX
    val isBusy = entry.direction == QsoLogEntry.Direction.BUSY
    val dirColor = when (entry.direction) {
        QsoLogEntry.Direction.TX -> Accent
        QsoLogEntry.Direction.RX -> Signal
        QsoLogEntry.Direction.BUSY -> TextMuted
    }
    val dirLabel = when (entry.direction) {
        QsoLogEntry.Direction.TX -> "TX"
        QsoLogEntry.Direction.RX -> "RX"
        QsoLogEntry.Direction.BUSY -> "BUSY"
    }
    // Dim the whole row when the target is talking to someone else — it's
    // context, not part of our QSO.
    val rowAlpha = if (isBusy) 0.6f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Direction badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(dirColor.copy(alpha = 0.15f * rowAlpha))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dirLabel,
                color = dirColor.copy(alpha = rowAlpha),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // UTC time
        Text(
            text = timeStr,
            color = TextFaint.copy(alpha = rowAlpha),
            fontSize = 10.sp,
            fontFamily = GeistMonoFamily,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Message text
        Text(
            text = entry.messageText,
            color = TextPrimary.copy(alpha = rowAlpha),
            fontSize = 11.sp,
            fontFamily = GeistMonoFamily,
            modifier = Modifier.weight(1f),
        )

        // SNR for received messages (RX or BUSY)
        if (entry.snr != null && !isTx) {
            Text(
                text = if (entry.snr >= 0) "+${entry.snr}" else "${entry.snr}",
                color = Signal.copy(alpha = rowAlpha),
                fontSize = 10.sp,
                fontFamily = GeistMonoFamily,
            )
        }
    }
}

@Composable
private fun TxSelector(
    functions: ArrayList<FunctionOfTransmit>,
    currentOrder: Int,
    onSelectOrder: (Int) -> Unit,
) {
    if (functions.isEmpty()) return

    // Short labels for each function order
    val labels = mapOf(
        1 to "GRID",
        2 to "RPT",
        3 to "R-RPT",
        4 to "RR73",
        5 to "73",
        6 to "CQ",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "TX:",
            color = TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            modifier = Modifier.align(Alignment.CenterVertically),
        )

        functions.forEach { func ->
            val order = func.functionOrder
            val isActive = order == currentOrder
            val isCompleted = func.isCompleted
            val label = labels[order] ?: "$order"

            val bgColor = when {
                isActive -> AccentSoft
                isCompleted -> SignalSoft
                else -> BgSurface3
            }
            val borderColor = when {
                isActive -> Accent.copy(alpha = 0.5f)
                else -> Color.Transparent
            }
            val textColor = when {
                isActive -> Accent
                isCompleted -> Signal
                else -> TextMuted
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor)
                    .then(
                        if (isActive) Modifier.border(1.dp, borderColor, RoundedCornerShape(6.dp))
                        else Modifier
                    )
                    .clickable { onSelectOrder(order) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 10.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                )
            }
        }
    }
}

/**
 * Order the pileup caller chips for display. When [strongestFirst] the queue is
 * sorted by SNR descending (best copy first) — matching the auto-dequeue policy
 * in [com.k1af.ft8af.ft8transmit.CallerQueueOrdering] — so the chip the operator
 * is nudged to tap next is the same station the engine would pick. [sortedByDescending]
 * is stable, so equal-SNR callers keep their first-heard order. Off preserves the
 * raw queue (first-heard) order. Extracted for unit testing.
 */
internal fun orderCallerQueue(
    queue: List<QueuedCaller>,
    strongestFirst: Boolean,
): List<QueuedCaller> =
    if (strongestFirst) queue.sortedByDescending { it.snr } else queue

/**
 * Signed SNR label for a queued caller chip (e.g. "+5", "-12"). A queued caller
 * with no reported SNR carries 0, which renders as "+0".
 */
internal fun callerSnrLabel(snr: Int): String = if (snr >= 0) "+$snr" else "$snr"

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CallerQueueBar(
    queue: ArrayList<QueuedCaller>,
    onCallerTap: ((String) -> Unit)? = null,
) {
    if (queue.isEmpty()) return

    Spacer(modifier = Modifier.height(6.dp))

    // Display order tracks the auto-dequeue policy so the leftmost chip is the
    // station the engine would work next. Computed inline (the queue is capped at
    // ~10 callers) rather than remember()d, so an SNR update that mutates a queued
    // caller in place still re-sorts on the next recomposition.
    val ordered = orderCallerQueue(queue, GeneralVariables.pileupStrongestFirst)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.qsopanel_queue),
            color = TextFaint,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.04.sp,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ordered.forEach { caller ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SignalSoft)
                        .then(
                            if (onCallerTap != null) Modifier.clickable { onCallerTap(caller.callsign) }
                            else Modifier
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = caller.callsign,
                        color = Signal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GeistMonoFamily,
                    )
                    Text(
                        text = callerSnrLabel(caller.snr),
                        color = Signal.copy(alpha = 0.65f),
                        fontSize = 9.sp,
                        fontFamily = GeistMonoFamily,
                    )
                }
            }
        }
    }
}

private val GeistMonoFamily = radio.ks3ckc.ft8af.theme.GeistMonoFamily
