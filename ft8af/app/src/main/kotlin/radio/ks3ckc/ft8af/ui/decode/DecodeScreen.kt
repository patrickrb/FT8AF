package radio.ks3ckc.ft8af.ui.decode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.R
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.ModeProfile
import com.k1af.ft8af.timer.UtcTimer
import com.k1af.ft8af.ui.WaterfallTimestampGate
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.EmptyStateWaves
import radio.ks3ckc.ft8af.ui.components.FilterChips
import radio.ks3ckc.ft8af.ui.components.TopBar
import radio.ks3ckc.ft8af.ui.components.TopBarSubtitle

/**
 * Main decode screen. Observes the ViewModel's LiveData for decoded FT8 messages,
 * shows a filter bar, and renders a scrolling list of [DecodeRow] items.
 * Tapping a row opens the [QsoSheet] bottom sheet with station details.
 */
@Composable
fun DecodeScreen(
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    // Observe LiveData
    val messageList by mainViewModel.mutableFt8MessageList.observeAsState(arrayListOf())
    val decodedCount by mainViewModel.mutable_Decoded_Counter.observeAsState(0)
    val utcTime by mainViewModel.timerSec.observeAsState(0L)

    // Filter state. Backed by the ViewModel so the chosen filter survives
    // navigation away from Decode and back (the screen is recreated by the
    // tab switch, which would otherwise reset a local rememberSaveable).
    val filterOptions = listOf("All", "CQ Calls", "CQ POTA", "New DXCC", "New Zone", "New State", "New Grid", "New Prefix", "Needed", "For Me")
    val selectedFilter by mainViewModel.decodeFilter.observeAsState("All")

    // Couple the "CQ POTA" display filter to Hunt: while it's selected, the auto-call
    // path (FT8TransmitSignal) restricts itself to POTA CQs and won't call general
    // stations. decodeFilter is only ever mutated from this screen, so syncing here on
    // every change — including the reset to "All" — keeps the flag accurate (issue #333).
    LaunchedEffect(selectedFilter) {
        GeneralVariables.huntPotaOnly = selectedFilter == "CQ POTA"
    }

    // Keep the POTA spots cache warm while the user is browsing decodes so the
    // CQ POTA filter and the green POTA pill on spotted activators work even
    // without visiting the POTA tab. Ref-counted with the POTA screen.
    DisposableEffect(Unit) {
        radio.ks3ckc.ft8af.pota.PotaSpotsRepository.start()
        onDispose { radio.ks3ckc.ft8af.pota.PotaSpotsRepository.stop() }
    }

    // Bottom-sheet state lives in the ViewModel so it survives navigation
    // away from this screen during an active QSO.
    val sheetCallsign by mainViewModel.qsoSheetCallsign.observeAsState()
    val sheetMinimized by mainViewModel.qsoSheetMinimized.observeAsState(false)

    val txToCallsign by mainViewModel.ft8TransmitSignal.mutableToCallsign.observeAsState()
    val txActivated by mainViewModel.ft8TransmitSignal.mutableIsActivated.observeAsState(false)
    val txFunctionOrder by mainViewModel.ft8TransmitSignal.mutableFunctionOrder.observeAsState(6)

    // Auto-open the sheet when our CQ is answered.
    LaunchedEffect(txToCallsign?.callsign, txActivated) {
        val cs = txToCallsign?.callsign
        if (txActivated && !cs.isNullOrEmpty() && cs != "CQ" && sheetCallsign != cs) {
            mainViewModel.qsoSheetCallsign.postValue(cs)
            mainViewModel.qsoSheetMinimized.postValue(false)
        }
    }

    // Track the last non-CQ function order so we can tell whether the QSO
    // reached order 5 (73 sent) before reverting to CQ. Retry-limit give-ups
    // jump straight from order 1-3 to 6; normal completions pass through 5.
    var lastQsoFunctionOrder by remember { mutableStateOf(txFunctionOrder) }
    LaunchedEffect(txFunctionOrder) {
        if (txFunctionOrder != 6) lastQsoFunctionOrder = txFunctionOrder
    }

    // Linger then clear: when the operator reaches the final TX
    // (functionOrder == 5, sending 73) leave the sheet up for a few
    // seconds so the operator can register completion, then clear it.
    // STOP-deactivations don't trigger this — the sheet stays put until
    // the user slides it down, matching their expectation that pressing
    // STOP only halts TX.
    LaunchedEffect(txFunctionOrder, sheetCallsign) {
        if (sheetCallsign != null && txFunctionOrder == 5) {
            kotlinx.coroutines.delay(5000)
            mainViewModel.qsoSheetCallsign.postValue(null)
            mainViewModel.qsoSheetMinimized.postValue(false)
        }
    }

    // Clear the sheet when retry limit is exhausted: the TX layer jumps
    // straight from order 1-3 to 6 (CQ) without passing through order 5,
    // so the above effect never fires. Detect this by watching the target
    // callsign revert to "CQ" while still activated (not a user STOP).
    // Guard with lastQsoFunctionOrder != 5 so normal 73-completion still
    // gets the full 5s linger from the effect above. (#249)
    LaunchedEffect(txToCallsign?.callsign, txActivated, sheetCallsign) {
        if (sheetCallsign != null && txActivated && txToCallsign?.callsign == "CQ"
            && lastQsoFunctionOrder != 5) {
            kotlinx.coroutines.delay(2000)
            mainViewModel.qsoSheetCallsign.postValue(null)
            mainViewModel.qsoSheetMinimized.postValue(false)
        }
    }

    // The message bound to the current sheet (latest decode from that
    // callsign). Recomputes when the decode list updates.
    val selectedMessage: Ft8Message? = remember(sheetCallsign, messageList, messageList?.size) {
        val cs = sheetCallsign ?: return@remember null
        (messageList ?: arrayListOf())
            .lastOrNull { it.callsignFrom.equals(cs, ignoreCase = true) }
    }
    val sheetVisible = sheetCallsign != null && !sheetMinimized && selectedMessage != null

    // Take a snapshot of the list for stable rendering (ArrayList is mutable)
    val messages: List<Ft8Message> = remember(messageList, messageList?.size) {
        ArrayList(messageList ?: arrayListOf())
    }

    // Sort mode for the collapsed list — persisted via GeneralVariables.decodeSortMode
    // / DB key "decodeSortMode" (same pattern as msgMode / clearDecodesEveryCycle).
    var sortMode by rememberSaveable { mutableStateOf(DecodeSortMode.fromConfig(GeneralVariables.decodeSortMode)) }

    // Apply filter, THEN collapse to one row per station. Filtering first keeps
    // the visible-station count consistent with the active chip (a station whose
    // latest decode is directed but earlier one was a CQ still shows under
    // "CQ Calls"). See collapseByStation / filterMessages.
    val filteredMessages = remember(messages, selectedFilter) {
        filterMessages(messages, selectedFilter)
    }
    val collapsedMessages = remember(filteredMessages, sortMode) {
        collapseByStation(filteredMessages, sortMode)
    }

    // Precompute, once per list change, where the mode-aware time-group dividers
    // fall (FT8 15s, FT4 7.5s, FT2 3.75s) so each row looks its flag up by index
    // instead of recomputing a slot boundary inline. Computed over the collapsed
    // list this LazyColumn iterates, so index is always in bounds. The dividers
    // only render in last-heard order (see showTimeGroupDividers) — that gate is
    // applied at draw time below.
    val timeGroupDividers = remember(collapsedMessages) {
        computeTimeGroupDividers(collapsedMessages)
    }

    // Track which station rows are new-or-just-updated since the previous render
    // (animated once). Keyed by normalized station + utcTime so a station
    // re-decoded in a later cycle (new utcTime, same station) re-triggers the
    // highlight — that's the "row just updated in place" cue. Only the visible
    // keys are retained: accumulating them would grow one entry per station per
    // cycle for as long as the screen is open. See advanceRowAnimation.
    var seenKeys by remember { mutableStateOf(emptySet<String>()) }
    val currentKeys = remember(collapsedMessages) {
        collapsedMessages.map { rowAnimationKey(it) }.toSet()
    }
    val newKeys = remember(currentKeys) { advanceRowAnimation(seenKeys, currentKeys).new }
    LaunchedEffect(currentKeys) {
        seenKeys = advanceRowAnimation(seenKeys, currentKeys).seen
    }

    // Auto-scroll state. Only "last heard" ordering auto-scrolls (to the top,
    // where the newest station sits); the other sorts would yank the viewport.
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(collapsedMessages.size, sortMode) {
        val target = autoScrollTargetIndex(sortMode, collapsedMessages.size, previousCount)
        if (target != null) {
            listState.animateScrollToItem(target)
        }
        previousCount = collapsedMessages.size
    }

    // A tapped Needed-DX notification asks us to pre-select that station: reset to the
    // "All" filter so it's visible, scroll to its latest decode, and open the QSO sheet
    // (which shows the station detail + a Call button). We do NOT auto-transmit.
    val preselectCallsign by mainViewModel.mutablePreselectCallsign.observeAsState()
    LaunchedEffect(preselectCallsign, messageList?.size) {
        val cs = preselectCallsign
        if (cs.isNullOrBlank()) return@LaunchedEffect
        val present = (messageList ?: arrayListOf())
            .any { it.callsignFrom.equals(cs, ignoreCase = true) }
        if (!present) return@LaunchedEffect          // wait until the station is in the list
        mainViewModel.decodeFilter.postValue("All")
        mainViewModel.qsoSheetCallsign.postValue(cs)
        mainViewModel.qsoSheetMinimized.postValue(false)
        mainViewModel.mutablePreselectCallsign.postValue(null)   // consume (allow re-trigger)
    }

    // Compact mode — persisted via GeneralVariables.simpleCallItemMode / DB key "msgMode"
    var compactMode by rememberSaveable { mutableStateOf(GeneralVariables.simpleCallItemMode) }

    // Clear-every-cycle mode — when on, the decode list is wiped at the start of
    // each cycle so it only shows the current slot. Persisted via
    // GeneralVariables.clearDecodesEveryCycle / DB key "clearDecodesEveryCycle".
    var clearEachCycle by rememberSaveable { mutableStateOf(GeneralVariables.clearDecodesEveryCycle) }

    // Format UTC time for the subtitle
    val utcString = if (utcTime > 0L) {
        UtcTimer.getTimeStr(utcTime)
    } else {
        stringResource(R.string.decode_utc_placeholder)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgApp),
        ) {
            // Top bar
            TopBar(
                title = stringResource(R.string.decode_title),
                subtitle = {
                    TopBarSubtitle(
                        text = stringResource(R.string.decode_subtitle, utcString, decodedCount),
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            sortMode = nextSortMode(sortMode)
                            GeneralVariables.decodeSortMode = sortMode.configValue
                            mainViewModel.databaseOpr.writeConfig(
                                "decodeSortMode", sortMode.configValue.toString(), null,
                            )
                        },
                    ) {
                        SortModeLabel(sortMode)
                    }
                    IconButton(
                        onClick = {
                            clearEachCycle = !clearEachCycle
                            GeneralVariables.clearDecodesEveryCycle = clearEachCycle
                            mainViewModel.databaseOpr.writeConfig(
                                "clearDecodesEveryCycle", if (clearEachCycle) "1" else "0", null,
                            )
                        },
                    ) {
                        radio.ks3ckc.ft8af.ui.components.FT8AFIcons.AutoClear(
                            color = if (clearEachCycle) Accent else TextMuted,
                        )
                    }
                    IconButton(
                        onClick = {
                            compactMode = !compactMode
                            GeneralVariables.simpleCallItemMode = compactMode
                            mainViewModel.databaseOpr.writeConfig("msgMode", if (compactMode) "1" else "0", null)
                        },
                    ) {
                        if (compactMode) {
                            radio.ks3ckc.ft8af.ui.components.FT8AFIcons.ViewExpanded(color = TextMuted)
                        } else {
                            radio.ks3ckc.ft8af.ui.components.FT8AFIcons.ViewCompact(color = TextMuted)
                        }
                    }
                    IconButton(
                        onClick = { mainViewModel.clearFt8MessageList() },
                        enabled = messageList?.isNotEmpty() == true,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.decode_clear_list),
                            tint = TextMuted,
                        )
                    }
                },
            )

            // Filter chips. FilterChips renders each option string AND passes it
            // back through onSelected, so we feed it localized labels for display
            // but translate the tapped label back to its stable English key before
            // writing it to mainViewModel.decodeFilter, which selectedFilter
            // observes and the filter logic switches on.
            val localizedLabels = filterOptions.map { filterLabel(it) }
            val labelToKey = filterOptions.indices.associate { localizedLabels[it] to filterOptions[it] }
            FilterChips(
                options = localizedLabels,
                selected = filterLabel(selectedFilter),
                onSelected = { label -> mainViewModel.decodeFilter.postValue(labelToKey[label] ?: selectedFilter) },
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Message list or empty state
            if (collapsedMessages.isEmpty()) {
                EmptyState(
                    selectedFilter = selectedFilter,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    itemsIndexed(
                        items = collapsedMessages,
                        // Key on the station callsign (stable across cycles) so
                        // Compose reuses the same row and updates it in place when
                        // a station is re-decoded, rather than adding a new row.
                        // Normalized via stationKey so a station whose callsign
                        // arrives with different case/padding across cycles keeps
                        // the same key (and so the same row) — see stationKey.
                        key = { index, msg -> stationKey(msg) ?: "row_$index" },
                    ) { index, message ->
                        val rowKey = rowAnimationKey(message)

                        // Group rows by receive slot (mode-aware: 15s FT8, 7.5s
                        // FT4, 3.75s FT2), but only in "last heard" ordering where
                        // rows stay time-ordered — the other sorts would scatter
                        // the dividers. Boundaries were precomputed in
                        // timeGroupDividers above over this same collapsed list, so
                        // index is always in bounds.
                        if (showTimeGroupDividers(sortMode) && timeGroupDividers[index]) {
                            TimeGroupDivider(utcTime = message.utcTime, compact = compactMode)
                        }

                        // Target highlight: this row is from the station the
                        // operator is currently calling. Ignore the idle "CQ"
                        // sentinel that lives in txToCallsign between QSOs.
                        val targetCs = txToCallsign?.callsign?.takeIf {
                            it.isNotEmpty() && !it.equals("CQ", ignoreCase = true)
                        }
                        val isTarget = targetCs != null &&
                            message.callsignFrom?.equals(targetCs, ignoreCase = true) == true

                        DecodeRow(
                            message = message,
                            animateEntry = rowKey in newKeys,
                            nowMillis = utcTime,
                            isTarget = isTarget,
                            compact = compactMode,
                            // Single tap = immediately call this station (fast reply to
                            // a CQ). Long-press opens the info sheet (QRZ, country, etc.).
                            onClick = {
                                mainViewModel.callStation(message)
                            },
                            onLongClick = {
                                mainViewModel.qsoSheetCallsign.postValue(message.callsignFrom)
                                mainViewModel.qsoSheetMinimized.postValue(false)
                            },
                        )
                    }
                }
            }
        }

        // QSO bottom sheet (overlays on top). Slide-down minimizes when a
        // QSO is active so the user can reopen via the ActiveQsoPanel; when
        // no QSO is active, dismiss fully so future row taps start fresh.
        QsoSheet(
            message = selectedMessage,
            mainViewModel = mainViewModel,
            visible = sheetVisible,
            onDismiss = {
                if (txActivated) {
                    mainViewModel.qsoSheetMinimized.postValue(true)
                } else {
                    mainViewModel.qsoSheetCallsign.postValue(null)
                    mainViewModel.qsoSheetMinimized.postValue(false)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Time Group Divider
// ---------------------------------------------------------------------------

/**
 * For each message in [messages], whether a time-group divider should be drawn
 * above it. A divider marks the start of a new receive slot (cycle): the first
 * message always gets one, and every later message gets one when it falls in a
 * different slot than its predecessor.
 *
 * The slot length is per-message and mode-aware — FT8 15s, FT4 7.5s, FT2 3.75s
 * (see [ModeProfile.slotMillis]) — so a fast-mode list gets its finer grid
 * instead of collapsing 2-4 real cycles under a single hard-coded 15s divider.
 * The boundary math is shared with the waterfall gridline via
 * [WaterfallTimestampGate.slotPeriod] so the two views stay on the same grid;
 * for FT8's 15000ms slot it reproduces the previous `utcTime / 15000` exactly.
 * Two messages of different modes never share a group even if their slot indices
 * happen to coincide, because the (slot length, index) pair differs.
 */
internal fun computeTimeGroupDividers(messages: List<Ft8Message>): BooleanArray {
    val dividers = BooleanArray(messages.size)
    var prevSlotMillis = -1L
    var prevSlotIndex = 0L
    for (i in messages.indices) {
        val slotMillis = ModeProfile.fromId(messages[i].signalFormat).slotMillis.toLong()
        val slotIndex = WaterfallTimestampGate.slotPeriod(messages[i].utcTime, slotMillis)
        dividers[i] = i == 0 || slotMillis != prevSlotMillis || slotIndex != prevSlotIndex
        prevSlotMillis = slotMillis
        prevSlotIndex = slotIndex
    }
    return dividers
}

@Composable
private fun TimeGroupDivider(utcTime: Long, compact: Boolean = false) {
    val timeStr = remember(utcTime) { UtcTimer.getTimeHHMMSS(utcTime) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (compact) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Border),
        )
        Text(
            text = stringResource(R.string.decode_time_group_utc, timeStr),
            color = TextFaint,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.08.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Border),
        )
    }
}

// ---------------------------------------------------------------------------
// Sort control
// ---------------------------------------------------------------------------

/**
 * Compact label rendered inside the top-bar sort button, showing the active
 * [DecodeSortMode] (TIME / CALL / SNR / DX). The button cycles modes on tap; the
 * accessibility content description announces the current mode.
 */
@Composable
internal fun SortModeLabel(sortMode: DecodeSortMode) {
    val label = stringResource(
        when (sortMode) {
            DecodeSortMode.LAST_HEARD -> R.string.decode_sort_label_last_heard
            DecodeSortMode.CALLSIGN -> R.string.decode_sort_label_callsign
            DecodeSortMode.SNR -> R.string.decode_sort_label_snr
            DecodeSortMode.DISTANCE -> R.string.decode_sort_label_distance
        },
    )
    val cd = stringResource(
        when (sortMode) {
            DecodeSortMode.LAST_HEARD -> R.string.decode_sort_cd_last_heard
            DecodeSortMode.CALLSIGN -> R.string.decode_sort_cd_callsign
            DecodeSortMode.SNR -> R.string.decode_sort_cd_snr
            DecodeSortMode.DISTANCE -> R.string.decode_sort_cd_distance
        },
    )
    Text(
        text = label,
        modifier = Modifier.semantics { contentDescription = cd },
        color = Accent,
        fontFamily = GeistMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.04.sp,
    )
}

// ---------------------------------------------------------------------------
// Filter Labels
// ---------------------------------------------------------------------------

/**
 * Map a stable English filter key (used in [filterMessages] and stored in
 * selectedFilter) to its localized display label. Keep the keys English so the
 * filter switch logic and selection comparisons never depend on the locale.
 */
@Composable
private fun filterLabel(key: String): String = when (key) {
    "CQ Calls" -> stringResource(R.string.decode_filter_cq_calls)
    "CQ POTA" -> stringResource(R.string.decode_filter_cq_pota)
    "New DXCC" -> stringResource(R.string.decode_filter_new_dxcc)
    "New Zone" -> stringResource(R.string.decode_filter_new_zone)
    "New State" -> stringResource(R.string.decode_filter_new_state)
    "New Grid" -> stringResource(R.string.decode_filter_new_grid)
    "New Prefix" -> stringResource(R.string.decode_filter_new_prefix)
    "Needed" -> stringResource(R.string.decode_filter_needed)
    "For Me" -> stringResource(R.string.decode_filter_for_me)
    else -> stringResource(R.string.decode_filter_all)
}

// ---------------------------------------------------------------------------
// Filter Logic
// ---------------------------------------------------------------------------

/**
 * Apply the selected filter to the message list.
 *
 * Filters:
 *  - All: no filtering
 *  - CQ Calls: only CQ messages
 *  - New DXCC: CQ from a DXCC entity not yet in the operator's worked list
 *  - New Zone: CQ from a CQ zone not yet in the operator's worked list (WAZ)
 *  - New State: CQ from a US state not yet in the operator's worked list (WAS)
 *  - New Grid: CQ from a Maidenhead grid field not yet in the operator's worked list
 *  - Needed: need QSL confirmation (not in QSL callsign list)
 *  - For Me: callsignTo matches operator's callsign
 */
internal fun filterMessages(
    messages: List<Ft8Message>,
    filter: String,
): List<Ft8Message> {
    // Our own transmission's echoes (full-duplex monitoring) are a measurement,
    // not a station to work: they belong on the unfiltered list, where the
    // operator reads their own SNR and offset, and nowhere else. Every chip and
    // every "show only" setting is a question about other stations — a CQ chip
    // must not offer our own CQ, "Needed" must not list us, and "DX only" must
    // not hide the very row the feature was turned on to see. So echoes bypass
    // the whole pipeline under "All" and are dropped under any chip.
    val others = messages.filterNot { it.isOwnEcho }
    val filtered = filterOtherStations(others, filter)
    if (filter != "All" || others.size == messages.size) return filtered
    // Re-merge in list order so the echo sits where it decoded, not at the end.
    val kept = filtered.toHashSet()
    return messages.filter { it.isOwnEcho || it in kept }
}

/** [filterMessages] for the decodes that are other stations — see there. */
private fun filterOtherStations(
    messages: List<Ft8Message>,
    filter: String,
): List<Ft8Message> {
    // Base stage: always-on blocklist + settings-driven "show only" filters.
    // Applied before the chip switch so they AND with whatever chip is selected.
    var base = messages.filterNot { GeneralVariables.checkIsBlockedMessage(it) }
    if (GeneralVariables.filterShowOnlyCQ) {
        base = base.filter { it.checkIsCQ() }
    }
    if (GeneralVariables.filterDxOnly) {
        base = base.filter {
            it.continent != null && GeneralVariables.myContinent != null &&
                !it.continent.equals(GeneralVariables.myContinent, ignoreCase = true)
        }
    }
    if (GeneralVariables.filterNeededOnly) {
        base = base.filter {
            !it.isQSL_Callsign &&
                !GeneralVariables.checkQSLCallsign(it.callsignFrom ?: "")
        }
    }
    if (GeneralVariables.filterByContinent) {
        base = base.filter {
            it.continent != null &&
                it.continent.equals(GeneralVariables.filterContinent, ignoreCase = true)
        }
    }
    if (GeneralVariables.filterDirectionalCQ) {
        base = base.filter { GeneralVariables.directionalCQIsForMe(it.callsignTo) }
    }
    // WSJT-X-style "hide worked stations": when the worked-station mode is HIDE,
    // drop stations that count as worked under the configured scope. Stations
    // calling us are kept (see isHiddenAsWorked). Only walk the list at all in
    // HIDE mode so the default/legacy path skips this pass entirely.
    if (effectiveWorkedMode() == WorkedStationMode.HIDE) {
        base = base.filterNot { isHiddenAsWorked(it) }
    }

    return when (filter) {
        "CQ Calls" -> base.filter { it.checkIsCQ() }
        // Same predicate the Hunt auto-call path uses (PotaCqClassifier), so selecting
        // this filter and hunting agree on who counts as a POTA station — see issue #333.
        "CQ POTA" -> base.filter { radio.ks3ckc.ft8af.pota.PotaCqClassifier.isPotaCq(it) }
        "New DXCC" -> base.filter { it.checkIsCQ() && it.fromDxcc }
        // Mirror of "New DXCC" for zone chasers (Worked All Zones): only CQ
        // stations from a CQ zone the operator hasn't logged yet. fromCq is the
        // decode-time unworked-zone flag, computed alongside fromDxcc.
        "New Zone" -> base.filter { it.checkIsCQ() && it.fromCq }
        // Mirror of "New DXCC" for state chasers (Worked All States): only CQ
        // stations from a US state the operator hasn't logged yet. fromNewState is
        // the decode-time unworked-state flag (US-grid → state, US-only table).
        "New State" -> base.filter { it.checkIsCQ() && it.fromNewState }
        // Mirror of "New DXCC" for grid chasers (VUCC / grid hunting): only CQ
        // stations whose grid field the operator hasn't logged yet, so the list
        // becomes a one-tap "who's calling from a grid I still need" view.
        "New Grid" -> base.filter { it.checkIsCQ() && isNewGridStation(it) }
        // Mirror of "New DXCC" for prefix chasers (Worked All Prefixes / WPX):
        // only CQ stations whose callsign prefix the operator hasn't logged yet,
        // so the list becomes a one-tap "who's a new prefix" view.
        "New Prefix" -> base.filter { it.checkIsCQ() && isNewPrefixStation(it) }
        "Needed" -> base.filter {
            !it.isQSL_Callsign &&
                !GeneralVariables.checkQSLCallsign(it.callsignFrom ?: "")
        }
        "For Me" -> base.filter {
            GeneralVariables.checkIsMyCallsign(it.callsignTo ?: "")
        }
        else -> base // "All"
    }
}

// ---------------------------------------------------------------------------
// Empty State
// ---------------------------------------------------------------------------

@Composable
internal fun EmptyState(
    selectedFilter: String,
    modifier: Modifier = Modifier,
) {
    val (title, subtitle) = when (selectedFilter) {
        "CQ Calls" -> stringResource(R.string.decode_empty_cq_title) to stringResource(R.string.decode_empty_cq_body)
        "CQ POTA" -> stringResource(R.string.decode_empty_pota_title) to stringResource(R.string.decode_empty_pota_body)
        "New DXCC" -> stringResource(R.string.decode_empty_dxcc_title) to stringResource(R.string.decode_empty_dxcc_body)
        "New Zone" -> stringResource(R.string.decode_empty_zone_title) to stringResource(R.string.decode_empty_zone_body)
        "New State" -> stringResource(R.string.decode_empty_state_title) to stringResource(R.string.decode_empty_state_body)
        "New Grid" -> stringResource(R.string.decode_empty_grid_title) to stringResource(R.string.decode_empty_grid_body)
        "New Prefix" -> stringResource(R.string.decode_empty_prefix_title) to stringResource(R.string.decode_empty_prefix_body)
        "Needed" -> stringResource(R.string.decode_empty_needed_title) to stringResource(R.string.decode_empty_needed_body)
        "For Me" -> stringResource(R.string.decode_empty_forme_title) to stringResource(R.string.decode_empty_forme_body)
        else -> stringResource(R.string.decode_empty_default_title) to stringResource(R.string.decode_empty_default_body, GeneralVariables.currentMode().displayName)
    }

    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyStateWaves(size = 180.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            color = TextMuted,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = TextFaint,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}
