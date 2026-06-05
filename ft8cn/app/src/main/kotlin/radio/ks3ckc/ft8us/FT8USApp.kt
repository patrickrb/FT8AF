package radio.ks3ckc.ft8us

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.database.OperationBand
import com.bg7yoz.ft8cn.rigs.BaseRigOperation
import radio.ks3ckc.ft8us.theme.BgApp
import radio.ks3ckc.ft8us.ui.components.ActiveQsoPanel
import radio.ks3ckc.ft8us.ui.components.FT8USTab
import radio.ks3ckc.ft8us.ui.components.FrequencyPickerSheet
import radio.ks3ckc.ft8us.ui.components.formatMhz
import radio.ks3ckc.ft8us.ui.components.QsoCelebration
import radio.ks3ckc.ft8us.ui.components.SlotTimerBar
import radio.ks3ckc.ft8us.ui.components.TabBar
import radio.ks3ckc.ft8us.ui.components.TransmitGlow
import radio.ks3ckc.ft8us.ui.components.TxStrip
import radio.ks3ckc.ft8us.ui.components.selectBandIndex
import radio.ks3ckc.ft8us.ui.decode.DecodeScreen
import radio.ks3ckc.ft8us.ui.logbook.LogbookScreen
import radio.ks3ckc.ft8us.ui.map.MapScreen
import radio.ks3ckc.ft8us.ui.pota.PotaScreen
import radio.ks3ckc.ft8us.ui.settings.SettingsScreen
import radio.ks3ckc.ft8us.ui.waterfall.WaterfallScreen

@Composable
fun FT8USApp(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    var activeTab by rememberSaveable { mutableStateOf(FT8USTab.DECODE) }

    // Observe transmit state
    val isTransmitting by mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observeAsState(false)
    val isActivated by mainViewModel.ft8TransmitSignal.mutableIsActivated.observeAsState(false)
    val txSlot by mainViewModel.ft8TransmitSignal.mutableSequential.observeAsState(mainViewModel.ft8TransmitSignal.sequential)
    val qsoCompletedAt by mainViewModel.ft8TransmitSignal.mutableQsoCompletedAt.observeAsState()

    // QSO panel expand/collapse state
    var qsoPanelExpanded by rememberSaveable { mutableStateOf(false) }

    // Hunt / auto-answer-CQ mode. Mirrors GeneralVariables.autoFollowCQ (also
    // editable in Settings, which provides the persisted default at startup).
    var huntEnabled by remember { mutableStateOf(GeneralVariables.autoFollowCQ) }

    // Frequency picker sheet state
    var showFrequencyPicker by rememberSaveable { mutableStateOf(false) }

    // Auto-expand when activated, auto-collapse when deactivated
    LaunchedEffect(isActivated) {
        if (isActivated) {
            qsoPanelExpanded = true
        } else {
            qsoPanelExpanded = false
        }
    }

    // Pill label combines MHz frequency and band name, e.g. "14.074 MHz · 20m".
    // bandIndex is observed so the pill recomposes when the user retunes.
    val bandIndex by GeneralVariables.mutableBandChange.observeAsState(GeneralVariables.bandListIndex)
    val freq = GeneralVariables.band
    val bandName = OperationBand.bandList.getOrNull(bandIndex)?.waveLength
        ?: OperationBand.bandList.firstOrNull { it.band == freq }?.waveLength
        ?: BaseRigOperation.getMeterFromFreq(freq)
        ?: ""
    val frequencyLabel = buildString {
        append(formatMhz(freq))
        append(" MHz")
        if (bandName.isNotBlank()) {
            append(" · ")
            append(bandName)
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Main content area (takes remaining space).
            // Note: AndroidView-wrapped legacy views (waterfall/columnar) interact badly with
            // AnimatedContent's graphicsLayer translations during enter/exit, so tab switching
            // here is a plain swap. The TabBar selection itself still animates.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (activeTab) {
                    FT8USTab.DECODE -> DecodeScreen(mainViewModel)
                    FT8USTab.MAP -> MapScreen(mainViewModel)
                    FT8USTab.WATERFALL -> WaterfallScreen(mainViewModel)
                    FT8USTab.POTA -> PotaScreen(mainViewModel)
                    FT8USTab.LOG -> LogbookScreen(mainViewModel)
                    FT8USTab.SETTINGS -> SettingsScreen(mainViewModel)
                }
            }

            // Active QSO panel — slides up above TxStrip when a QSO is in progress
            ActiveQsoPanel(
                mainViewModel = mainViewModel,
                expanded = qsoPanelExpanded,
                onCollapse = { qsoPanelExpanded = false },
                onReopenSheet = {
                    // Switch to the Decode tab so the bottom sheet is visible,
                    // then expand it (clears minimized flag, ensures a callsign
                    // is bound to the current TX target).
                    activeTab = FT8USTab.DECODE
                    val target = mainViewModel.ft8TransmitSignal.mutableToCallsign.value?.callsign
                    if (!target.isNullOrEmpty() && target != "CQ") {
                        if (mainViewModel.qsoSheetCallsign.value != target) {
                            mainViewModel.qsoSheetCallsign.postValue(target)
                        }
                    }
                    mainViewModel.qsoSheetMinimized.postValue(false)
                },
            )

            // Slot timer bar — fills 0→100% across each 15s FT8 slot
            SlotTimerBar(
                activeTxSlot = txSlot,
                isActivated = isActivated,
            )

            // TX status strip — always visible above tab bar
            TxStrip(
                isTransmitting = isTransmitting,
                isActivated = isActivated,
                frequencyLabel = frequencyLabel,
                txSlot = txSlot,
                huntEnabled = huntEnabled,
                expanded = qsoPanelExpanded,
                onCallCQ = {
                    if (GeneralVariables.myCallsign.isNullOrEmpty()) {
                        Toast.makeText(context, "Set your callsign in Settings before calling CQ", Toast.LENGTH_SHORT).show()
                    } else {
                        mainViewModel.ft8TransmitSignal.userResetToCQ()
                        mainViewModel.ft8TransmitSignal.setActivated(true)
                        GeneralVariables.resetLaunchSupervision()
                    }
                },
                onStop = {
                    mainViewModel.ft8TransmitSignal.setActivated(false)
                },
                onToggleSlot = {
                    val current = mainViewModel.ft8TransmitSignal.sequential
                    val newSlot = if (current == 0) 1 else 0
                    mainViewModel.ft8TransmitSignal.sequential = newSlot
                    mainViewModel.ft8TransmitSignal.mutableSequential.postValue(newSlot)
                },
                onToggleHunt = {
                    val newVal = !huntEnabled
                    huntEnabled = newVal
                    GeneralVariables.autoFollowCQ = newVal
                    mainViewModel.databaseOpr.writeConfig(
                        "autoFollowCQ", if (newVal) "1" else "0", null,
                    )
                    Toast.makeText(
                        context,
                        if (newVal) "Hunt on — auto-answering CQs"
                        else "Hunt off — running CQ, working answers only",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onOpenFrequencyPicker = { showFrequencyPicker = true },
                onToggleExpand = { qsoPanelExpanded = !qsoPanelExpanded },
            )

            // Bottom tab bar
            TabBar(
                activeTab = activeTab,
                onTabSelected = { activeTab = it },
            )
        }

        // Transmit breathing border — sibling overlay so its per-frame invalidations
        // don't bubble into the waterfall composable. Pointer events pass through.
        TransmitGlow(isTransmitting = isTransmitting)

        // One-shot particle burst when a QSO completes.
        QsoCelebration(triggerAt = qsoCompletedAt)

        // Frequency picker — sibling overlay so the scrim and sheet sit above the
        // tab bar and TxStrip.
        FrequencyPickerSheet(
            visible = showFrequencyPicker,
            currentBandIndex = bandIndex,
            onDismiss = { showFrequencyPicker = false },
            onSelect = { idx ->
                selectBandIndex(mainViewModel, context, idx)
                showFrequencyPicker = false
            },
        )
    }
}
