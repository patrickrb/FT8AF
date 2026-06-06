package radio.ks3ckc.ft8us.ui.decode

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bg7yoz.ft8cn.Ft8Message
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.timer.UtcTimer
import radio.ks3ckc.ft8us.theme.*
import radio.ks3ckc.ft8us.ui.components.EmptyStateWaves
import radio.ks3ckc.ft8us.ui.components.FilterChips
import radio.ks3ckc.ft8us.ui.components.TopBar
import radio.ks3ckc.ft8us.ui.components.TopBarSubtitle

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

    // Filter state
    val filterOptions = listOf("All", "CQ Calls", "CQ POTA", "New DXCC", "Needed", "For Me")
    var selectedFilter by rememberSaveable { mutableStateOf("All") }

    // Keep the POTA spots cache warm while the user is browsing decodes so the
    // CQ POTA filter and the green POTA pill on spotted activators work even
    // without visiting the POTA tab. Ref-counted with the POTA screen.
    DisposableEffect(Unit) {
        radio.ks3ckc.ft8us.pota.PotaSpotsRepository.start()
        onDispose { radio.ks3ckc.ft8us.pota.PotaSpotsRepository.stop() }
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

    // Apply filter
    val filteredMessages = remember(messages, selectedFilter) {
        filterMessages(messages, selectedFilter)
    }

    // Track which keys are new since the previous render (animated on entry only once).
    var seenKeys by remember { mutableStateOf(emptySet<String>()) }
    val currentKeys = remember(filteredMessages) {
        filteredMessages.mapIndexed { i, m ->
            "${m.utcTime}_${m.callsignFrom}_${m.freq_hz}_$i"
        }.toSet()
    }
    val newKeys = remember(currentKeys) { currentKeys - seenKeys }
    LaunchedEffect(currentKeys) {
        seenKeys = seenKeys + currentKeys
    }

    // Auto-scroll state
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.size > previousCount && filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.size - 1)
        }
        previousCount = filteredMessages.size
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
        selectedFilter = "All"
        mainViewModel.qsoSheetCallsign.postValue(cs)
        mainViewModel.qsoSheetMinimized.postValue(false)
        mainViewModel.mutablePreselectCallsign.postValue(null)   // consume (allow re-trigger)
    }

    // Compact mode — persisted via GeneralVariables.simpleCallItemMode / DB key "msgMode"
    var compactMode by rememberSaveable { mutableStateOf(GeneralVariables.simpleCallItemMode) }

    // Format UTC time for the subtitle
    val utcString = if (utcTime > 0L) {
        UtcTimer.getTimeStr(utcTime)
    } else {
        "UTC : --:--:--"
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgApp),
        ) {
            // Top bar
            TopBar(
                title = "Decode",
                subtitle = {
                    TopBarSubtitle(
                        text = "$utcString  \u2022  $decodedCount decoded this cycle",
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            compactMode = !compactMode
                            GeneralVariables.simpleCallItemMode = compactMode
                            mainViewModel.databaseOpr.writeConfig("msgMode", if (compactMode) "1" else "0", null)
                        },
                    ) {
                        if (compactMode) {
                            radio.ks3ckc.ft8us.ui.components.FT8USIcons.ViewExpanded(color = TextMuted)
                        } else {
                            radio.ks3ckc.ft8us.ui.components.FT8USIcons.ViewCompact(color = TextMuted)
                        }
                    }
                    IconButton(
                        onClick = { mainViewModel.clearFt8MessageList() },
                        enabled = messageList?.isNotEmpty() == true,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Clear decode list",
                            tint = TextMuted,
                        )
                    }
                },
            )

            // Filter chips
            FilterChips(
                options = filterOptions,
                selected = selectedFilter,
                onSelected = { selectedFilter = it },
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Message list or empty state
            if (filteredMessages.isEmpty()) {
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
                        items = filteredMessages,
                        key = { index, msg -> "${msg.utcTime}_${msg.callsignFrom}_${msg.freq_hz}_$index" },
                    ) { index, message ->
                        val rowKey = "${message.utcTime}_${message.callsignFrom}_${message.freq_hz}_$index"

                        // Group messages by FT8 cycle (15s slots). Draw a labeled
                        // divider whenever we cross into a new slot.
                        val prevSlot = if (index > 0) filteredMessages[index - 1].utcTime / 15000L else null
                        val thisSlot = message.utcTime / 15000L
                        if (prevSlot == null || prevSlot != thisSlot) {
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
            text = "$timeStr UTC",
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
// Filter Logic
// ---------------------------------------------------------------------------

/**
 * Apply the selected filter to the message list.
 *
 * Filters:
 *  - All: no filtering
 *  - CQ Calls: only CQ messages
 *  - New DXCC: CQ from a DXCC entity not yet in the operator's worked list
 *  - Needed: need QSL confirmation (not in QSL callsign list)
 *  - For Me: callsignTo matches operator's callsign
 */
private fun filterMessages(
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

    return when (filter) {
        "CQ Calls" -> base.filter { it.checkIsCQ() }
        "CQ POTA" -> base.filter {
            // Match three signals: (1) explicit "POTA" suffix on a CQ, (2) any CQ from a
            // station currently spotted on pota.app (activators often drop the suffix to
            // save chars), (3) free-text fragments like "CQ POT" that decoders garble
            // when the activator's call is long.
            it.checkIsCQ() && (
                it.modifier == "POTA" ||
                    radio.ks3ckc.ft8us.pota.PotaSpotsRepository.parkRefFor(it.callsignFrom) != null ||
                    (it.callsignTo?.startsWith("CQ POT", ignoreCase = true) == true)
                )
        }
        "New DXCC" -> base.filter { it.checkIsCQ() && it.fromDxcc }
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
private fun EmptyState(
    selectedFilter: String,
    modifier: Modifier = Modifier,
) {
    val (title, subtitle) = when (selectedFilter) {
        "CQ Calls" -> "No CQ calls" to "No stations are calling CQ on this band right now."
        "CQ POTA" -> "No POTA spots" to "No park activations decoded on this band yet — open POTA → Hunt for the spot list."
    "New DXCC" -> "No new DXCC" to "No unworked DXCC entities have been decoded yet."
        "Needed" -> "Nothing needed" to "No stations needing confirmation found."
        "For Me" -> "No calls for you" to "No stations are calling your callsign right now."
        else -> "No signals decoded" to "Waiting for FT8 signals to appear..."
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
