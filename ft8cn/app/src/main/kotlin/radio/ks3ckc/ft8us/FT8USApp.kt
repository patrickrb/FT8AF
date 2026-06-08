package radio.ks3ckc.ft8us

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bg7yoz.ft8cn.FT8Common
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.ModeProfile
import com.bg7yoz.ft8cn.R
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
    // Consume the one-shot celebration signal so LiveData doesn't replay it
    // on recomposition / resubscription.
    LaunchedEffect(qsoCompletedAt) {
        if (qsoCompletedAt != null) {
            mainViewModel.ft8TransmitSignal.mutableQsoCompletedAt.postValue(null)
        }
    }

    // QSO panel expand/collapse state
    var qsoPanelExpanded by rememberSaveable { mutableStateOf(false) }

    // Hunt / auto-answer-CQ mode. Mirrors GeneralVariables.autoFollowCQ (also
    // editable in Settings, which provides the persisted default at startup).
    var huntEnabled by remember { mutableStateOf(GeneralVariables.autoFollowCQ) }

    // Frequency picker sheet state
    var showFrequencyPicker by rememberSaveable { mutableStateOf(false) }

    // A tapped Needed-DX notification asks us to jump to the Decode tab (DecodeScreen
    // then scrolls to + highlights the alerted station).
    val preselectCallsign by mainViewModel.mutablePreselectCallsign.observeAsState()
    LaunchedEffect(preselectCallsign) {
        if (!preselectCallsign.isNullOrBlank()) {
            activeTab = FT8USTab.DECODE
        }
    }

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
    // Operating mode (FT8/FT4) — observed so the mode pill, countdown, and freq picker
    // recompose when the mode changes.
    val operatingMode by mainViewModel.mutableOperatingMode.observeAsState(GeneralVariables.operatingMode)
    val modeName = ModeProfile.fromId(operatingMode).displayName

    // Observe SWR lockout state
    val swrLocked by mainViewModel.meterProtectionController.swrLockout.observeAsState(false)
    val lockoutSwrRatio by mainViewModel.meterProtectionController.lockoutSwrRatio.observeAsState("")

    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // SWR lockout banner — red warning at top when SWR halt triggered
            if (swrLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFCC2222))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.swr_lockout_title, lockoutSwrRatio),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = stringResource(R.string.swr_lockout_body),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            mainViewModel.meterProtectionController.clearSwrLockout()
                        },
                    ) {
                        Text(
                            stringResource(R.string.swr_lockout_dismiss),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

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

            // Slot timer bar — fills 0→100% across each slot (15s FT8 / 7.5s FT4)
            SlotTimerBar(
                activeTxSlot = txSlot,
                isActivated = isActivated,
                slotMillis = ModeProfile.fromId(operatingMode).slotMillis.toLong(),
            )

            // TX status strip — always visible above tab bar
            TxStrip(
                isTransmitting = isTransmitting,
                isActivated = isActivated,
                frequencyLabel = frequencyLabel,
                txSlot = txSlot,
                huntEnabled = huntEnabled,
                modeName = modeName,
                modeSwitchEnabled = !isTransmitting,
                expanded = qsoPanelExpanded,
                onCallCQ = {
                    if (GeneralVariables.myCallsign.isNullOrEmpty()) {
                        Toast.makeText(context, context.getString(R.string.app_set_callsign_first), Toast.LENGTH_SHORT).show()
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
                        if (newVal) context.getString(R.string.app_hunt_on)
                        else context.getString(R.string.app_hunt_off),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onCycleMode = {
                    // v1 cycles FT8 <-> FT4. When FT2 ships, widen this to iterate the
                    // shipped ModeProfile entries.
                    val next = if (operatingMode == FT8Common.FT8_MODE) FT8Common.FT4_MODE
                    else FT8Common.FT8_MODE
                    if (mainViewModel.setOperatingMode(next)) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.app_mode_switched, ModeProfile.fromId(next).displayName),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.app_mode_switch_busy),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
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
