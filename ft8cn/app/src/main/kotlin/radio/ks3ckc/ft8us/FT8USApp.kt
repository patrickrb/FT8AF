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
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.ModeProfile
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.database.OperationBand
import com.bg7yoz.ft8cn.ft8transmit.FT8TransmitSignal
import com.bg7yoz.ft8cn.rigs.CatConnectionState
import com.bg7yoz.ft8cn.rigs.BaseRigOperation
import radio.ks3ckc.ft8us.theme.BgApp
import radio.ks3ckc.ft8us.ui.components.ActiveQsoPanel
import radio.ks3ckc.ft8us.ui.components.shouldShowCatChip
import radio.ks3ckc.ft8us.ui.components.FT8USTab
import radio.ks3ckc.ft8us.ui.components.FrequencyPickerSheet
import radio.ks3ckc.ft8us.ui.components.HoundSetupSheet
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
import radio.ks3ckc.ft8us.ui.waterfall.WaterfallBottomStripHeight
import radio.ks3ckc.ft8us.ui.waterfall.WaterfallScreen

/**
 * Whether the active QSO panel should float over the screen content (true)
 * rather than dock below it in the main column (false).
 *
 * The Waterfall tab overlays it: docking the panel shrinks the content area,
 * and resizing the waterfall's AndroidView rescales it and wipes its
 * accumulated history (see WaterfallView.onSizeChanged). Floating keeps the
 * waterfall at a constant height. List-style screens (decode, log, settings,
 * POTA, map) dock it, where pushing their content up is harmless.
 */
internal fun qsoPanelOverlaysContent(tab: FT8USTab): Boolean = tab == FT8USTab.WATERFALL

/**
 * Whether the sequencer should be armed for Hunt on app startup. True when the
 * persisted Hunt setting is "on" and the operator has set a callsign (Hunt
 * transmits replies, so a callsign is required).
 */
internal fun shouldArmHuntOnStartup(huntEnabled: Boolean, callsign: String?): Boolean =
    huntEnabled && !callsign.isNullOrEmpty()

@Composable
fun FT8USApp(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    var activeTab by rememberSaveable { mutableStateOf(FT8USTab.DECODE) }

    // Observe transmit state
    val isTransmitting by mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observeAsState(false)
    val isActivated by mainViewModel.ft8TransmitSignal.mutableIsActivated.observeAsState(false)
    val txSlot by mainViewModel.ft8TransmitSignal.mutableSequential.observeAsState(mainViewModel.ft8TransmitSignal.sequential)
    val qsoCompletedAt by mainViewModel.ft8TransmitSignal.mutableQsoCompletedAt.observeAsState()
    // CAT connection status for the TX-strip chip. Hidden for VOX / audio-only
    // setups (see shouldShowCatChip); tap reconnects (handy for Bluetooth, which
    // often only connects on the second attempt).
    val catState by mainViewModel.mutableCatConnectionState.observeAsState(CatConnectionState.DISCONNECTED)
    // Observe control mode so the chip shows/hides immediately when the user
    // switches VOX <-> CAT/RTS/DTR in Settings (seeds from the current value).
    val controlMode by GeneralVariables.mutableControlMode.observeAsState(GeneralVariables.controlMode)
    val showCatChip = shouldShowCatChip(controlMode, catState)
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

    // DXpedition Hound mode. Mirrors GeneralVariables.houndMode; the setup sheet
    // collects the Fox call + call frequency before starting.
    var dxEnabled by remember { mutableStateOf(GeneralVariables.houndMode) }
    var showHoundSetup by remember { mutableStateOf(false) }

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

    // Wait for config (callsign, autoFollowCQ, etc.) to finish loading from
    // the database before arming Hunt. LaunchedEffect(Unit) would race with
    // the async config load and see an empty callsign. (#231)
    val configLoaded by mainViewModel.mutableConfigLoaded.observeAsState(false)
    LaunchedEffect(configLoaded) {
        if (configLoaded) {
            // Always sync the UI toggle from the persisted value so the Hunt
            // chip reflects the correct state even when arming is skipped
            // (e.g. callsign not yet configured).
            huntEnabled = GeneralVariables.autoFollowCQ
            if (shouldArmHuntOnStartup(
                    GeneralVariables.autoFollowCQ, GeneralVariables.myCallsign)) {
                mainViewModel.ft8TransmitSignal.armForHunt()
                mainViewModel.ft8TransmitSignal.setActivated(true)
                GeneralVariables.resetLaunchSupervision()
            }
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

    // Reopen the full QSO sheet from the panel header: jump to the Decode tab,
    // bind the current TX target, and clear the minimized flag.
    val reopenQsoSheet: () -> Unit = {
        activeTab = FT8USTab.DECODE
        val target = mainViewModel.ft8TransmitSignal.mutableToCallsign.value?.callsign
        if (!target.isNullOrEmpty() && target != "CQ") {
            if (mainViewModel.qsoSheetCallsign.value != target) {
                mainViewModel.qsoSheetCallsign.postValue(target)
            }
        }
        mainViewModel.qsoSheetMinimized.postValue(false)
    }

    // Single definition of the panel, placed either docked in the column or
    // floated over the content (see qsoPanelOverlaysContent).
    val qsoPanel: @Composable (Modifier) -> Unit = { panelModifier ->
        ActiveQsoPanel(
            mainViewModel = mainViewModel,
            expanded = qsoPanelExpanded,
            onCollapse = { qsoPanelExpanded = false },
            onReopenSheet = reopenQsoSheet,
            modifier = panelModifier,
        )
    }

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

                // On the Waterfall tab the QSO panel floats over the bottom of
                // the waterfall instead of docking below it, so the waterfall
                // keeps its full height (docking would resize the AndroidView,
                // rescaling and wiping it). It sits directly above the waterfall's
                // own bottom info/toggle strip (offset by WaterfallBottomStripHeight)
                // so those controls stay reachable during an active QSO instead of
                // being covered — still without resizing the AndroidView.
                if (qsoPanelOverlaysContent(activeTab)) {
                    qsoPanel(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = WaterfallBottomStripHeight),
                    )
                }
            }

            // Active QSO panel (docked) — slides up above TxStrip when a QSO is
            // in progress. On the Waterfall tab it is floated over the content
            // above instead (see qsoPanelOverlaysContent) so it never resizes
            // the waterfall.
            if (!qsoPanelOverlaysContent(activeTab)) {
                qsoPanel(Modifier)
            }

            // Slot timer bar — fills 0→100% across each slot (15s FT8 / 7.5s FT4)
            SlotTimerBar(
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
                dxEnabled = dxEnabled,
                catState = catState,
                showCatChip = showCatChip,
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
                    // In Hound mode the STOP button leaves Hound entirely;
                    // otherwise it just deactivates the normal sequencer.
                    if (GeneralVariables.houndMode) {
                        mainViewModel.stopHoundMode()
                        dxEnabled = false
                    } else {
                        mainViewModel.ft8TransmitSignal.setActivated(false)
                        // If Hunt armed this run, STOP ends Hunt too, so the buttons can't be
                        // left showing Hunt "on" while the sequencer is stopped (and idle).
                        if (huntEnabled) {
                            huntEnabled = false
                            GeneralVariables.autoFollowCQ = false
                            mainViewModel.databaseOpr.writeConfig("autoFollowCQ", "0", null)
                        }
                    }
                },
                onToggleDx = {
                    if (dxEnabled || GeneralVariables.houndMode) {
                        mainViewModel.stopHoundMode()
                        dxEnabled = false
                    } else {
                        showHoundSetup = true
                    }
                },
                onToggleSlot = {
                    val current = mainViewModel.ft8TransmitSignal.sequential
                    val newSlot = if (current == 0) 1 else 0
                    mainViewModel.ft8TransmitSignal.sequential = newSlot
                    mainViewModel.ft8TransmitSignal.mutableSequential.postValue(newSlot)
                    // Switching slots mid-QSO abandons the current contact.
                    val target = mainViewModel.ft8TransmitSignal.mutableToCallsign.value
                    if (FT8TransmitSignal.shouldResetTargetOnSlotToggle(target?.callsign)) {
                        mainViewModel.ft8TransmitSignal.userResetToCQ()
                    }
                },
                onToggleHunt = {
                    val newVal = !huntEnabled
                    if (newVal && GeneralVariables.myCallsign.isNullOrEmpty()) {
                        // Hunt transmits replies, so it needs a callsign just like CQ does.
                        Toast.makeText(context, context.getString(R.string.app_set_callsign_first), Toast.LENGTH_SHORT).show()
                    } else {
                        huntEnabled = newVal
                        GeneralVariables.autoFollowCQ = newVal
                        mainViewModel.databaseOpr.writeConfig(
                            "autoFollowCQ", if (newVal) "1" else "0", null,
                        )
                        if (newVal) {
                            // Arm the sequencer so Hunt actually answers CQs. It stays silent
                            // (transmit path suppresses calling CQ) until it hears a CQ to work.
                            // armForHunt (not userResetToCQ) so Hunt can answer on the very next
                            // cycle instead of skipping one via pendingUserCQ.
                            mainViewModel.ft8TransmitSignal.armForHunt()
                            mainViewModel.ft8TransmitSignal.setActivated(true)
                            GeneralVariables.resetLaunchSupervision()
                        } else {
                            mainViewModel.ft8TransmitSignal.setActivated(false)
                        }
                        Toast.makeText(
                            context,
                            if (newVal) context.getString(R.string.app_hunt_on)
                            else context.getString(R.string.app_hunt_off),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onCycleMode = {
                    // Cycle through the shipped ModeProfile entries in declaration order
                    // (FT8 -> FT4 -> FT2 -> ...), wrapping around. An unknown current mode
                    // (indexOfFirst == -1) falls back to the first entry (FT8).
                    val modes = ModeProfile.values()
                    val curIdx = modes.indexOfFirst { it.id == operatingMode }
                    val next = modes[(curIdx + 1) % modes.size].id
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
                onReconnectCat = { mainViewModel.reconnectRig() },
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

        // DXpedition Hound setup — collects the Fox call + call frequency, then
        // starts calling (disabling Hunt, which is mutually exclusive).
        HoundSetupSheet(
            visible = showHoundSetup,
            initialFoxCall = GeneralVariables.houndFoxCall,
            onDismiss = { showHoundSetup = false },
            onStart = { foxCall, callFreqHz ->
                if (GeneralVariables.myCallsign.isNullOrEmpty()) {
                    Toast.makeText(context, context.getString(R.string.app_set_callsign_first), Toast.LENGTH_SHORT).show()
                } else {
                    mainViewModel.startHoundMode(foxCall, callFreqHz)
                    dxEnabled = true
                    huntEnabled = false
                    showHoundSetup = false
                }
            },
        )
    }
}
