package radio.ks3ckc.ft8us.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bg7yoz.ft8cn.Ft8Message
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.log.ThirdPartyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.connector.CableSerialPort
import com.bg7yoz.ft8cn.location.GridLocationUpdater
import com.bg7yoz.ft8cn.connector.ConnectMode
import com.bg7yoz.ft8cn.database.ControlMode
import com.bg7yoz.ft8cn.database.OperationBand
import com.bg7yoz.ft8cn.database.RigNameList
import com.bg7yoz.ft8cn.ft8signal.FT8Package
import com.bg7yoz.ft8cn.ft8transmit.MeterProtectionController
import com.bg7yoz.ft8cn.rigs.BaseRigOperation
import com.bg7yoz.ft8cn.rigs.InstructionSet
import com.bg7yoz.ft8cn.ui.AudioDeviceSpinnerAdapter
import radio.ks3ckc.ft8us.theme.*
import radio.ks3ckc.ft8us.ui.components.GlassCard
import radio.ks3ckc.ft8us.ui.components.SettingsRow
import radio.ks3ckc.ft8us.ui.components.Toggle
import radio.ks3ckc.ft8us.ui.components.TopBar
import radio.ks3ckc.ft8us.ui.components.selectBandIndex

/**
 * BCP-47 language tags for the in-app Language picker, parallel to the label list
 * built in the picker dialog. Index 0 ("") means "System default" (empty locale
 * list). Keep in sync with res/xml/locales_config.xml and the values-<locale>/ dirs.
 */
private val LANGUAGE_TAGS = listOf("", "en", "zh-CN", "zh-TW", "ru", "es", "fr", "ja")

/**
 * Settings screen that replaces the legacy ConfigFragment.
 * Reads state from [GeneralVariables] static fields and persists changes
 * via [MainViewModel.databaseOpr.writeConfig].
 */
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Observe reactive fields from GeneralVariables
    val gridLive by GeneralVariables.mutableMyMaidenheadGrid.observeAsState(
        GeneralVariables.getMyMaidenheadGrid(),
    )
    val bandIndexLive by GeneralVariables.mutableBandChange.observeAsState(
        GeneralVariables.bandListIndex,
    )
    val baseFreqLive by GeneralVariables.mutableBaseFrequency.observeAsState(
        GeneralVariables.getBaseFrequency(),
    )

    // Local mutable state backed by GeneralVariables statics
    var synFrequency by remember { mutableStateOf(GeneralVariables.synFrequency) }
    var autoFollowCQ by remember { mutableStateOf(GeneralVariables.autoFollowCQ) }
    var autoCallFollow by remember { mutableStateOf(GeneralVariables.autoCallFollow) }
    var earlyDecode by remember { mutableStateOf(GeneralVariables.earlyDecode) }
    var autoUpdateGridFromGPS by remember { mutableStateOf(GeneralVariables.autoUpdateGridFromGPS) }
    var enableCloudlog by remember { mutableStateOf(GeneralVariables.enableCloudlog) }
    var enableQRZ by remember { mutableStateOf(GeneralVariables.enableQRZ) }
    var enablePskReporter by remember { mutableStateOf(GeneralVariables.enablePskReporter) }
    var saveSWLMessage by remember { mutableStateOf(GeneralVariables.saveSWLMessage) }
    var saveSWL_QSO by remember { mutableStateOf(GeneralVariables.saveSWL_QSO) }

    // Decode-list highlight toggles
    var highlightNewDxcc by remember { mutableStateOf(GeneralVariables.highlightNewDxcc) }
    var highlightNewGrid by remember { mutableStateOf(GeneralVariables.highlightNewGrid) }
    var highlightNewBand by remember { mutableStateOf(GeneralVariables.highlightNewBand) }
    var highlightWorked by remember { mutableStateOf(GeneralVariables.highlightWorked) }
    var highlightPota by remember { mutableStateOf(GeneralVariables.highlightPota) }
    var distanceInMiles by remember { mutableStateOf(GeneralVariables.distanceInMiles) }

    // Callsign blocklist (comma-separated entries) + decode display filters
    var blockedExact by remember { mutableStateOf(GeneralVariables.getBlockedExactCallsigns()) }
    var blockedPrefixes by remember { mutableStateOf(GeneralVariables.getExcludeCallsigns()) }
    var blockedKeywords by remember { mutableStateOf(GeneralVariables.getBlockedKeywords()) }
    var filterShowOnlyCQ by remember { mutableStateOf(GeneralVariables.filterShowOnlyCQ) }
    var filterDxOnly by remember { mutableStateOf(GeneralVariables.filterDxOnly) }
    var filterNeededOnly by remember { mutableStateOf(GeneralVariables.filterNeededOnly) }
    var filterByContinent by remember { mutableStateOf(GeneralVariables.filterByContinent) }
    var filterContinent by remember { mutableStateOf(GeneralVariables.filterContinent) }
    var respectDirectionalCQ by remember { mutableStateOf(GeneralVariables.respectDirectionalCQ) }
    var filterDirectionalCQ by remember { mutableStateOf(GeneralVariables.filterDirectionalCQ) }
    var alertNewDxcc by remember { mutableStateOf(GeneralVariables.alertNewDxcc) }
    var alertNewState by remember { mutableStateOf(GeneralVariables.alertNewState) }

    // Continent codes (stored on the message) and their display names, parallel lists.
    val continentCodes = listOf("NA", "SA", "EU", "AF", "AS", "OC", "AN")
    val continentNames = listOf(
        stringResource(R.string.continent_na),
        stringResource(R.string.continent_sa),
        stringResource(R.string.continent_eu),
        stringResource(R.string.continent_af),
        stringResource(R.string.continent_as),
        stringResource(R.string.continent_oc),
        stringResource(R.string.continent_an),
    )

    // Observe serial ports for USB Cable picker
    val serialPorts by mainViewModel.mutableSerialPorts.observeAsState()
    var showSerialPortPicker by remember { mutableStateOf(false) }

    // Dialog visibility state
    var showEditOperator by remember { mutableStateOf(false) }
    var showConnectionMode by remember { mutableStateOf(false) }
    var showBandPicker by remember { mutableStateOf(false) }
    var showEnabledBands by remember { mutableStateOf(false) }
    // Mirror of GeneralVariables.excludedBands so the dialog + the "N of M enabled"
    // label recompose as the user toggles bands.
    var excludedBands by remember { mutableStateOf(GeneralVariables.excludedBands.toSet()) }
    var showAudioFreq by remember { mutableStateOf(false) }
    var showSpectrumWidth by remember { mutableStateOf(false) }
    var showWatchdog by remember { mutableStateOf(false) }
    var showStopAfter by remember { mutableStateOf(false) }
    var showPttDelay by remember { mutableStateOf(false) }
    var showTxDelay by remember { mutableStateOf(false) }
    var showLateStart by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showDebugScreen by remember { mutableStateOf(false) }
    var debugEnabled by remember { mutableStateOf(GeneralVariables.debugModeEnabled) }
    var showCloudlog by remember { mutableStateOf(false) }
    var showQrzCreds by remember { mutableStateOf(false) }
    var qrzXmlUser by remember { mutableStateOf(GeneralVariables.qrzXmlUsername.orEmpty()) }
    var qrzXmlPass by remember { mutableStateOf(GeneralVariables.qrzXmlPassword.orEmpty()) }
    var showRigModelPicker by remember { mutableStateOf(false) }
    var showControlModePicker by remember { mutableStateOf(false) }
    var showAudioInputPicker by remember { mutableStateOf(false) }
    var showAudioOutputPicker by remember { mutableStateOf(false) }
    var showBaudRatePicker by remember { mutableStateOf(false) }
    var showTxVolume by remember { mutableStateOf(false) }
    var showBlockExactDialog by remember { mutableStateOf(false) }
    var showBlockPrefixDialog by remember { mutableStateOf(false) }
    var showBlockKeywordDialog by remember { mutableStateOf(false) }
    var showContinentPicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showBluetoothPicker by remember { mutableStateOf(false) }
    var showFlexRadioPicker by remember { mutableStateOf(false) }
    var showXieguRadioPicker by remember { mutableStateOf(false) }
    var showIcomLogin by remember { mutableStateOf(false) }

    // TX Protection state
    var autoVolumeEnabled by remember { mutableStateOf(GeneralVariables.autoVolumeEnabled) }
    var swrHaltEnabled by remember { mutableStateOf(GeneralVariables.swrHaltEnabled) }
    var swrHaltThreshold by remember { mutableIntStateOf(GeneralVariables.swrHaltThreshold) }
    var alcTargetLow by remember { mutableIntStateOf(GeneralVariables.alcTargetLow) }
    var alcTargetHigh by remember { mutableIntStateOf(GeneralVariables.alcTargetHigh) }

    // Operator identity edit state
    var callsignState by remember { mutableStateOf(GeneralVariables.myCallsign.orEmpty()) }
    var gridState by remember { mutableStateOf(GeneralVariables.getMyMaidenheadGrid().orEmpty()) }
    var antennaState by remember { mutableStateOf(GeneralVariables.myAntenna.orEmpty()) }
    var powerWattsState by remember { mutableIntStateOf(GeneralVariables.myPowerWatts) }

    // Mutable state for settings that need to trigger recomposition on change
    var watchdogMs by remember { mutableIntStateOf(GeneralVariables.launchSupervision) }
    var autoCQAfterQSO by remember { mutableStateOf(GeneralVariables.autoCQAfterQSO) }
    var noReplyLimit by remember { mutableIntStateOf(GeneralVariables.noReplyLimit) }
    var pttDelay by remember { mutableIntStateOf(GeneralVariables.pttDelay) }
    var txDelay by remember { mutableIntStateOf(GeneralVariables.transmitDelay) }
    var lateStartMs by remember { mutableIntStateOf(GeneralVariables.lateStartTolerance) }
    var connectMode by remember { mutableIntStateOf(GeneralVariables.connectMode) }
    var cloudlogAddress by remember { mutableStateOf(GeneralVariables.cloudlogServerAddress.orEmpty()) }
    var controlMode by remember { mutableIntStateOf(GeneralVariables.controlMode) }
    var modelNo by remember { mutableIntStateOf(GeneralVariables.modelNo) }
    var baudRate by remember { mutableIntStateOf(GeneralVariables.baudRate) }
    var spectrumWidth by remember { mutableIntStateOf(GeneralVariables.getSpectrumWidth()) }

    // TX Volume state – observe LiveData so hardware button changes update the UI
    val volumeLive by GeneralVariables.mutableVolumePercent.observeAsState(
        GeneralVariables.volumePercent,
    )
    var txVolume by remember { mutableIntStateOf((GeneralVariables.volumePercent * 100).toInt()) }
    // Keep txVolume in sync when hardware buttons (or other sources) update the LiveData
    LaunchedEffect(volumeLive) {
        txVolume = ((volumeLive ?: GeneralVariables.volumePercent) * 100).toInt()
    }

    // Derived display strings
    val callsign = callsignState
    val grid = gridLive.orEmpty()
    val connectModeStr = ConnectMode.getModeStr(connectMode)
    val bandStr = BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)
    val audioFreqStr = stringResource(
        R.string.settings_hz_str_format, GeneralVariables.getBaseFrequencyStr(),
    )
    val txDelayStr = stringResource(R.string.settings_milliseconds_format, txDelay)
    val pttDelayStr = stringResource(R.string.settings_milliseconds_format, pttDelay)
    val watchdogMinutes = watchdogMs / 60000
    val watchdogStr = if (watchdogMinutes == 0) stringResource(R.string.common_off)
        else stringResource(R.string.settings_minutes_format, watchdogMinutes)
    val rigConnected = mainViewModel.isRigConnected()
    val rigName = if (rigConnected) {
        mainViewModel.baseRig?.javaClass?.simpleName ?: "--"
    } else {
        stringResource(R.string.common_not_connected)
    }
    val antennaDisplay = antennaState.ifEmpty { "--" }
    val powerDisplay = if (powerWattsState > 0) "${powerWattsState}W" else "--"
    val baudRateStr = "$baudRate"
    val isCatMode = controlMode == ControlMode.CAT
        || controlMode == ControlMode.RTS
        || controlMode == ControlMode.DTR

    // Rig model list
    val rigNameList = remember { RigNameList.getInstance(context) }
    val rigModelStr = remember(modelNo) {
        rigNameList.getRigNameByIndex(modelNo).name
    }

    // Control mode display
    val controlModeStr = when (controlMode) {
        ControlMode.CAT -> "CAT"
        ControlMode.RTS -> "RTS"
        ControlMode.DTR -> "DTR"
        else -> "VOX"
    }

    // Audio device display names
    val audioInputAdapter = remember { AudioDeviceSpinnerAdapter(context, AudioManager.GET_DEVICES_INPUTS) }
    val audioOutputAdapter = remember { AudioDeviceSpinnerAdapter(context, AudioManager.GET_DEVICES_OUTPUTS) }
    val audioInputPos = remember(GeneralVariables.audioInputDeviceId) {
        audioInputAdapter.getPositionByDeviceId(GeneralVariables.audioInputDeviceId)
    }
    val audioOutputPos = remember(GeneralVariables.audioOutputDeviceId) {
        audioOutputAdapter.getPositionByDeviceId(GeneralVariables.audioOutputDeviceId)
    }
    var audioInputName by remember { mutableStateOf(audioInputAdapter.getDeviceDisplayName(audioInputPos)) }
    var audioOutputName by remember { mutableStateOf(audioOutputAdapter.getDeviceDisplayName(audioOutputPos)) }

    // =====================================================================
    // DIALOGS
    // =====================================================================

    // -- Edit Operator Dialog --
    if (showEditOperator) {
        EditOperatorDialog(
            initialCallsign = callsign,
            initialGrid = grid,
            initialAntenna = antennaState,
            initialPowerWatts = powerWattsState,
            onDismiss = { showEditOperator = false },
            onSave = { newCallsign, newGrid, newAntenna, newPowerWatts ->
                val trimmedCall = newCallsign.uppercase().trim()
                callsignState = trimmedCall
                GeneralVariables.myCallsign = trimmedCall
                mainViewModel.databaseOpr.writeConfig("callsign", trimmedCall, null)
                if (trimmedCall.isNotEmpty()) {
                    Ft8Message.hashList.addHash(FT8Package.getHash22(trimmedCall).toLong(), trimmedCall)
                    Ft8Message.hashList.addHash(FT8Package.getHash12(trimmedCall).toLong(), trimmedCall)
                    Ft8Message.hashList.addHash(FT8Package.getHash10(trimmedCall).toLong(), trimmedCall)
                }

                val formattedGrid = buildString {
                    newGrid.trim().forEachIndexed { i, c ->
                        append(if (i < 2) c.uppercaseChar() else c.lowercaseChar())
                    }
                }
                GeneralVariables.setMyMaidenheadGrid(formattedGrid)
                mainViewModel.databaseOpr.writeConfig("grid", formattedGrid, null)

                val trimmedAntenna = newAntenna.trim()
                antennaState = trimmedAntenna
                GeneralVariables.myAntenna = trimmedAntenna
                mainViewModel.databaseOpr.writeConfig("antenna", trimmedAntenna, null)

                powerWattsState = newPowerWatts
                GeneralVariables.myPowerWatts = newPowerWatts
                mainViewModel.databaseOpr.writeConfig("powerWatts", newPowerWatts.toString(), null)

                showEditOperator = false
            },
        )
    }

    // -- Blocklist: exact whole-call dialog --
    if (showBlockExactDialog) {
        TextListDialog(
            title = stringResource(R.string.settings_block_exact_title),
            description = stringResource(R.string.settings_block_exact_desc),
            initialValue = blockedExact,
            onDismiss = { showBlockExactDialog = false },
            onSave = { text ->
                GeneralVariables.addBlockedExactCallsigns(text)
                blockedExact = GeneralVariables.getBlockedExactCallsigns()
                mainViewModel.databaseOpr.writeConfig("blockedExactCallsigns", blockedExact, null)
                showBlockExactDialog = false
            },
        )
    }

    // -- Blocklist: prefix dialog (legacy excludedCallsigns key) --
    if (showBlockPrefixDialog) {
        TextListDialog(
            title = stringResource(R.string.settings_block_prefix_title),
            description = stringResource(R.string.settings_block_prefix_desc),
            initialValue = blockedPrefixes,
            onDismiss = { showBlockPrefixDialog = false },
            onSave = { text ->
                GeneralVariables.addExcludedCallsigns(text)
                blockedPrefixes = GeneralVariables.getExcludeCallsigns()
                mainViewModel.databaseOpr.writeConfig("excludedCallsigns", blockedPrefixes, null)
                showBlockPrefixDialog = false
            },
        )
    }

    // -- Blocklist: keyword dialog --
    if (showBlockKeywordDialog) {
        TextListDialog(
            title = stringResource(R.string.settings_block_keyword_title),
            description = stringResource(R.string.settings_block_keyword_desc),
            initialValue = blockedKeywords,
            onDismiss = { showBlockKeywordDialog = false },
            onSave = { text ->
                GeneralVariables.addBlockedKeywords(text)
                blockedKeywords = GeneralVariables.getBlockedKeywords()
                mainViewModel.databaseOpr.writeConfig("blockedKeywords", blockedKeywords, null)
                showBlockKeywordDialog = false
            },
        )
    }

    // -- Decode filter: continent picker --
    if (showContinentPicker) {
        val currentIndex = continentCodes.indexOf(filterContinent).coerceAtLeast(0)
        ListPickerDialog(
            title = stringResource(R.string.settings_filter_continent_title),
            items = continentNames,
            selectedIndex = currentIndex,
            onDismiss = { showContinentPicker = false },
            onSelect = { index ->
                showContinentPicker = false
                val code = continentCodes[index]
                filterContinent = code
                GeneralVariables.filterContinent = code
                mainViewModel.databaseOpr.writeConfig("filterContinent", code, null)
            },
        )
    }

    // -- Language Picker --
    // Index 0 = "System default" (empty locale list → follow system). Selecting a
    // language calls AppCompatDelegate.setApplicationLocales, which persists the
    // choice (framework LocaleManager on API 33+, AppCompat autoStore backport on
    // older) and recreates the activity so the new locale takes effect immediately.
    if (showLanguagePicker) {
        val languageTags = LANGUAGE_TAGS
        val languageLabels = listOf(
            stringResource(R.string.settings_language_system),
            stringResource(R.string.language_name_en),
            stringResource(R.string.language_name_zh_cn),
            stringResource(R.string.language_name_zh_tw),
            stringResource(R.string.language_name_ru),
            stringResource(R.string.language_name_es),
            stringResource(R.string.language_name_fr),
            stringResource(R.string.language_name_ja),
        )
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val currentIndex = languageTags.indexOfFirst { it.isNotEmpty() && currentTags.startsWith(it) }
            .let { if (it >= 0) it else 0 }
        ListPickerDialog(
            title = stringResource(R.string.settings_language),
            items = languageLabels,
            selectedIndex = currentIndex,
            onDismiss = { showLanguagePicker = false },
            onSelect = { index ->
                showLanguagePicker = false
                val tag = languageTags[index]
                val locales = if (tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
                AppCompatDelegate.setApplicationLocales(locales)
            },
        )
    }

    // -- Connection Mode Picker --
    if (showConnectionMode) {
        val connectionOptions = listOf(
            stringResource(R.string.settings_conn_usb_cable),
            stringResource(R.string.settings_conn_bluetooth),
            stringResource(R.string.settings_conn_network),
        )
        val currentIndex = GeneralVariables.connectMode.coerceIn(0, 2)
        ListPickerDialog(
            title = stringResource(R.string.settings_connection_mode),
            items = connectionOptions,
            selectedIndex = currentIndex,
            onDismiss = { showConnectionMode = false },
            onSelect = { index ->
                showConnectionMode = false
                GeneralVariables.connectMode = index
                connectMode = index
                mainViewModel.databaseOpr.writeConfig("connectMode", index.toString(), null)
                when (index) {
                    ConnectMode.BLUE_TOOTH -> {
                        showBluetoothPicker = true
                    }
                    ConnectMode.NETWORK -> {
                        when (GeneralVariables.instructionSet) {
                            InstructionSet.FLEX_NETWORK ->
                                showFlexRadioPicker = true
                            InstructionSet.XIEGU_6100_FT8CNS ->
                                showXieguRadioPicker = true
                            else ->
                                showIcomLogin = true
                        }
                    }
                    ConnectMode.USB_CABLE -> {
                        mainViewModel.getUsbDevice()
                        showSerialPortPicker = true
                    }
                }
            },
        )
    }

    // -- Serial Port Picker (USB Cable) --
    if (showSerialPortPicker) {
        val ports = serialPorts
        if (ports.isNullOrEmpty()) {
            InfoDialog(
                title = stringResource(R.string.settings_conn_usb_cable),
                body = stringResource(R.string.settings_no_usb_serial),
                onDismiss = { showSerialPortPicker = false },
            )
        } else {
            SerialPortPickerDialog(
                ports = ports,
                onDismiss = { showSerialPortPicker = false },
                onSelect = { port ->
                    showSerialPortPicker = false
                    mainViewModel.connectCableRig(context, port)
                },
            )
        }
    }

    // -- Bluetooth Picker --
    if (showBluetoothPicker) {
        BluetoothPickerDialog(
            mainViewModel = mainViewModel,
            onDismiss = { showBluetoothPicker = false },
        )
    }

    // -- FlexRadio Picker --
    if (showFlexRadioPicker) {
        FlexRadioPickerDialog(
            mainViewModel = mainViewModel,
            onDismiss = { showFlexRadioPicker = false },
        )
    }

    // -- Xiegu Picker --
    if (showXieguRadioPicker) {
        XieguRadioPickerDialog(
            mainViewModel = mainViewModel,
            onDismiss = { showXieguRadioPicker = false },
        )
    }

    // -- ICOM / Xiegu WiFi Login --
    if (showIcomLogin) {
        IcomLoginDialog(
            mainViewModel = mainViewModel,
            onDismiss = { showIcomLogin = false },
        )
    }

    // -- Band & Frequency Picker --
    if (showBandPicker) {
        // Only show bands the user hasn't hidden. visibleIndices maps the dialog's
        // row position back to the real OperationBand.bandList index.
        val visibleIndices = OperationBand.getVisibleBandIndices()
        val bandItems = visibleIndices.map { i -> OperationBand.getBandInfo(i) }
        // Highlight the active band if it's still visible; otherwise no selection.
        val selectedIndex = visibleIndices.indexOf(GeneralVariables.bandListIndex)
        ListPickerDialog(
            title = stringResource(R.string.settings_band_frequency),
            items = bandItems,
            selectedIndex = selectedIndex,
            onDismiss = { showBandPicker = false },
            onSelect = { position ->
                showBandPicker = false
                selectBandIndex(mainViewModel, context, visibleIndices[position])
            },
        )
    }

    // -- Enabled Bands (per-band visibility toggles) --
    if (showEnabledBands) {
        BandToggleDialog(
            excludedBands = excludedBands,
            onDismiss = { showEnabledBands = false },
            onToggle = { waveLength, enabled ->
                val updated = excludedBands.toMutableSet()
                if (enabled) updated.remove(waveLength) else updated.add(waveLength)
                // Never let the user hide every band; at least one must remain.
                if (updated.size >= OperationBand.getAllWaveLengths().size) return@BandToggleDialog
                excludedBands = updated
                GeneralVariables.excludedBands.clear()
                GeneralVariables.excludedBands.addAll(updated)
                mainViewModel.databaseOpr.writeConfig(
                    "excludedBands", GeneralVariables.excludedBandsToCsv(), null,
                )
            },
        )
    }

    // -- Audio Frequency Editor --
    if (showAudioFreq) {
        val audioFreqMax = spectrumWidth - 100
        NumberInputDialog(
            title = stringResource(R.string.settings_audio_frequency),
            suffix = "Hz",
            initialValue = GeneralVariables.getBaseFrequency().toInt(),
            min = 100,
            max = audioFreqMax,
            onDismiss = { showAudioFreq = false },
            onSave = { value ->
                showAudioFreq = false
                val clamped = value.toFloat().coerceIn(100f, audioFreqMax.toFloat())
                GeneralVariables.setBaseFrequency(clamped)
                mainViewModel.databaseOpr.writeConfig("freq", clamped.toInt().toString(), null)
            },
        )
    }

    if (showSpectrumWidth) {
        NumberInputDialog(
            title = stringResource(R.string.settings_spectrum_width),
            suffix = "Hz",
            initialValue = spectrumWidth,
            min = 2500,
            max = 5000,
            onDismiss = { showSpectrumWidth = false },
            onSave = { value ->
                showSpectrumWidth = false
                spectrumWidth = value
                GeneralVariables.setSpectrumWidth(value)
                mainViewModel.databaseOpr.writeConfig("spectrumWidth", value.toString(), null)
            },
        )
    }

    // -- TX Watchdog Picker --
    if (showWatchdog) {
        // Build the same options as LaunchSupervisionSpinnerAdapter:
        // index 0 = Off (0 ms), index 1..10 = (index*10-5) minutes
        val watchdogOptions = mutableListOf(stringResource(R.string.common_off))
        for (i in 1..10) {
            watchdogOptions.add(stringResource(R.string.settings_minutes_format, i * 10 - 5))
        }
        // Find current selection index from stored ms value
        val currentWatchdogIndex = if (watchdogMs == 0) {
            0
        } else {
            ((watchdogMs - 5 * 60 * 1000) / 60 / 1000 / 10).coerceIn(0, 10)
        }
        ListPickerDialog(
            title = stringResource(R.string.settings_tx_watchdog),
            items = watchdogOptions,
            selectedIndex = currentWatchdogIndex,
            onDismiss = { showWatchdog = false },
            onSelect = { index ->
                showWatchdog = false
                // Same formula as LaunchSupervisionSpinnerAdapter.getTimeOut()
                val ms = if (index == 0) 0 else (index * 10 - 5) * 60 * 1000
                GeneralVariables.launchSupervision = ms
                watchdogMs = ms
                mainViewModel.databaseOpr.writeConfig(
                    "launchSupervision", ms.toString(), null,
                )
            },
        )
    }

    // -- Stop After (No Reply Limit) Picker --
    if (showStopAfter) {
        val stopAfterOptions = mutableListOf(stringResource(R.string.common_off))
        for (i in 1..30) {
            stopAfterOptions.add(stringResource(R.string.settings_tries_format, i))
        }
        ListPickerDialog(
            title = stringResource(R.string.settings_stop_after),
            items = stopAfterOptions,
            selectedIndex = noReplyLimit.coerceIn(0, 30),
            onDismiss = { showStopAfter = false },
            onSelect = { index ->
                showStopAfter = false
                GeneralVariables.noReplyLimit = index
                noReplyLimit = index
                mainViewModel.databaseOpr.writeConfig(
                    "noReplyLimit", index.toString(), null,
                )
            },
        )
    }

    // -- TX Volume Editor --
    // Slider-based dialog with live update: dragging the thumb updates the
    // in-memory volumePercent immediately so the next TX uses the new level,
    // and we persist to the config DB on dismiss. Tapping outside the dialog
    // or "Done" both commit. There's no "Cancel" because muscle-memory adjust
    // is the whole point — if you dragged it, you meant it.
    if (showTxVolume) {
        TxVolumeSliderDialog(
            initialValue = txVolume,
            onChange = { value ->
                txVolume = value
                GeneralVariables.volumePercent = value / 100f
                GeneralVariables.mutableVolumePercent.postValue(value / 100f)
            },
            onDismiss = {
                showTxVolume = false
                mainViewModel.databaseOpr.writeConfig("volumeValue", txVolume.toString(), null)
                mainViewModel.baseRig?.connector?.setRFVolume(txVolume)
            },
        )
    }

    // -- PTT Delay Picker --
    if (showPttDelay) {
        val pttDelayOptions = (0 until 20).map {
            stringResource(R.string.settings_milliseconds_format, it * 10)
        }
        val currentPttIndex = (pttDelay / 10).coerceIn(0, 19)
        ListPickerDialog(
            title = stringResource(R.string.settings_ptt_delay),
            items = pttDelayOptions,
            selectedIndex = currentPttIndex,
            onDismiss = { showPttDelay = false },
            onSelect = { index ->
                showPttDelay = false
                val ms = index * 10
                GeneralVariables.pttDelay = ms
                pttDelay = ms
                mainViewModel.databaseOpr.writeConfig("pttDelay", ms.toString(), null)
            },
        )
    }

    // -- TX Delay Editor --
    if (showTxDelay) {
        NumberInputDialog(
            title = stringResource(R.string.settings_tx_delay),
            suffix = "ms",
            initialValue = txDelay,
            min = 1,
            max = 9999,
            onDismiss = { showTxDelay = false },
            onSave = { value ->
                showTxDelay = false
                val clamped = value.coerceIn(1, 9999)
                GeneralVariables.transmitDelay = clamped
                txDelay = clamped
                mainViewModel.ft8TransmitSignal.setTimer_sec(clamped)
                mainViewModel.databaseOpr.writeConfig("transDelay", clamped.toString(), null)
            },
        )
    }

    // -- Late-start Tolerance Editor --
    if (showLateStart) {
        NumberInputDialog(
            title = stringResource(R.string.settings_late_start_tolerance),
            suffix = "ms",
            initialValue = lateStartMs,
            min = 0,
            max = 4000,
            onDismiss = { showLateStart = false },
            onSave = { value ->
                showLateStart = false
                val clamped = value.coerceIn(0, 4000)
                GeneralVariables.lateStartTolerance = clamped
                lateStartMs = clamped
                mainViewModel.databaseOpr.writeConfig("lateStartTolerance", clamped.toString(), null)
            },
        )
    }

    // -- About / FAQ Dialog --
    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onToggleDebug = {
                val next = !debugEnabled
                GeneralVariables.debugModeEnabled = next
                debugEnabled = next
                mainViewModel.databaseOpr.writeConfig(
                    "debugModeEnabled", if (next) "1" else "0", null,
                )
                Toast.makeText(
                    context,
                    if (next) context.getString(R.string.settings_debug_mode_enabled)
                    else context.getString(R.string.settings_debug_mode_disabled),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    // -- Debug log viewer --
    if (showDebugScreen) {
        DebugLogScreen(onDismiss = { showDebugScreen = false })
    }

    // -- QRZ Credentials Dialog --
    if (showQrzCreds) {
        QrzCredsDialog(
            initialUsername = qrzXmlUser,
            initialPassword = qrzXmlPass,
            onDismiss = { showQrzCreds = false },
            onSave = { user, pass ->
                qrzXmlUser = user
                qrzXmlPass = pass
                GeneralVariables.qrzXmlUsername = user
                GeneralVariables.qrzXmlPassword = pass
                mainViewModel.databaseOpr.writeConfig("qrzXmlUsername", user, null)
                mainViewModel.databaseOpr.writeConfig("qrzXmlPassword", pass, null)
                // Drop cached lookups so retries don't return stale nulls
                // from earlier failed attempts.
                radio.ks3ckc.ft8us.qrz.QrzXmlClient.clearCache()
                radio.ks3ckc.ft8us.qrz.QrzWebClient.clearCache()
                showQrzCreds = false
            },
        )
    }

    // -- Cloudlog Settings Dialog --
    if (showCloudlog) {
        CloudlogSettingsDialog(
            initialAddress = GeneralVariables.cloudlogServerAddress.orEmpty(),
            initialApiKey = GeneralVariables.cloudlogApiKey.orEmpty(),
            initialStationId = GeneralVariables.cloudlogStationID.orEmpty(),
            onDismiss = { showCloudlog = false },
            onSave = { address, apiKey, stationId ->
                GeneralVariables.cloudlogServerAddress = address
                GeneralVariables.cloudlogApiKey = apiKey
                GeneralVariables.cloudlogStationID = stationId
                cloudlogAddress = address
                mainViewModel.databaseOpr.writeConfig("cloudlogServerAddress", address, null)
                mainViewModel.databaseOpr.writeConfig("cloudlogApiKey", apiKey, null)
                mainViewModel.databaseOpr.writeConfig("cloudlogStationID", stationId, null)
                showCloudlog = false
            },
        )
    }

    // -- Rig Model Picker --
    if (showRigModelPicker) {
        val rigItems = rigNameList.rigList
            .mapIndexed { index, rig -> index to rig }
            .filter { (_, rig) -> !rig.modelName.startsWith("#") }
        val rigDisplayNames = rigItems.map { (_, rig) -> rig.name }
        val currentRigIndex = rigItems.indexOfFirst { (index, _) -> index == modelNo }
            .coerceAtLeast(0)
        ListPickerDialog(
            title = stringResource(R.string.settings_rig_model),
            items = rigDisplayNames,
            selectedIndex = currentRigIndex,
            onDismiss = { showRigModelPicker = false },
            onSelect = { selectedDisplayIndex ->
                showRigModelPicker = false
                val (actualIndex, selectedRig) = rigItems[selectedDisplayIndex]
                GeneralVariables.modelNo = actualIndex
                modelNo = actualIndex
                GeneralVariables.instructionSet = selectedRig.instructionSet
                GeneralVariables.civAddress = selectedRig.address
                GeneralVariables.baudRate = selectedRig.bauRate
                baudRate = selectedRig.bauRate
                mainViewModel.setCivAddress()
                mainViewModel.databaseOpr.writeConfig("model", actualIndex.toString(), null)
                mainViewModel.databaseOpr.writeConfig(
                    "instruction", GeneralVariables.instructionSet.toString(), null,
                )
                mainViewModel.databaseOpr.writeConfig(
                    "baudRate", GeneralVariables.baudRate.toString(), null,
                )
                mainViewModel.databaseOpr.writeConfig(
                    "civ", GeneralVariables.civAddress.toString(), null,
                )
            },
        )
    }

    // -- Control Mode Picker --
    if (showControlModePicker) {
        val controlModeOptions = listOf("VOX", "CAT", "RTS", "DTR")
        val controlModeValues = listOf(ControlMode.VOX, ControlMode.CAT, ControlMode.RTS, ControlMode.DTR)
        val currentControlIndex = controlModeValues.indexOf(controlMode).coerceAtLeast(0)
        ListPickerDialog(
            title = stringResource(R.string.settings_control_mode),
            items = controlModeOptions,
            selectedIndex = currentControlIndex,
            onDismiss = { showControlModePicker = false },
            onSelect = { index ->
                showControlModePicker = false
                val newMode = controlModeValues[index]
                GeneralVariables.controlMode = newMode
                controlMode = newMode
                mainViewModel.setControlMode()
                mainViewModel.databaseOpr.writeConfig("ctrMode", newMode.toString(), null)
                if (newMode == ControlMode.CAT
                    || newMode == ControlMode.RTS
                    || newMode == ControlMode.DTR
                ) {
                    if (!mainViewModel.isRigConnected()) {
                        mainViewModel.getUsbDevice()
                        showSerialPortPicker = true
                    } else {
                        mainViewModel.setOperationBand()
                    }
                }
            },
        )
    }

    // -- Baud Rate Picker --
    if (showBaudRatePicker) {
        val baudRateOptions = listOf(4800, 9600, 14400, 19200, 38400, 43000, 56000, 57600, 115200)
        val baudRateLabels = baudRateOptions.map { it.toString() }
        val currentBaudIndex = baudRateOptions.indexOf(baudRate).coerceAtLeast(0)
        ListPickerDialog(
            title = stringResource(R.string.settings_baud_rate),
            items = baudRateLabels,
            selectedIndex = currentBaudIndex,
            onDismiss = { showBaudRatePicker = false },
            onSelect = { index ->
                showBaudRatePicker = false
                val newBaudRate = baudRateOptions[index]
                GeneralVariables.baudRate = newBaudRate
                baudRate = newBaudRate
                mainViewModel.databaseOpr.writeConfig("baudRate", newBaudRate.toString(), null)
            },
        )
    }

    // -- Audio Input Device Picker --
    if (showAudioInputPicker) {
        AudioDevicePickerDialog(
            title = stringResource(R.string.settings_audio_input),
            adapter = audioInputAdapter,
            currentDeviceId = GeneralVariables.audioInputDeviceId,
            onDismiss = { showAudioInputPicker = false },
            onSelect = { position ->
                showAudioInputPicker = false
                val deviceId = audioInputAdapter.getDeviceId(position)
                GeneralVariables.audioInputDeviceId = deviceId
                mainViewModel.databaseOpr.writeConfig("audioInputDevice", deviceId.toString(), null)

                val usbInfo = audioInputAdapter.getUsbAudioDeviceInfo(position)
                if (usbInfo != null) {
                    GeneralVariables.usbAudioInputVendorId = usbInfo.device.vendorId
                    GeneralVariables.usbAudioInputProductId = usbInfo.device.productId
                    mainViewModel.databaseOpr.writeConfig(
                        "usbAudioInputVid", GeneralVariables.usbAudioInputVendorId.toString(), null,
                    )
                    mainViewModel.databaseOpr.writeConfig(
                        "usbAudioInputPid", GeneralVariables.usbAudioInputProductId.toString(), null,
                    )
                    mainViewModel.requestUsbPermissionIfNeeded(usbInfo.device)
                } else if (deviceId != -1) {
                    GeneralVariables.usbAudioInputVendorId = 0
                    GeneralVariables.usbAudioInputProductId = 0
                    mainViewModel.databaseOpr.writeConfig("usbAudioInputVid", "0", null)
                    mainViewModel.databaseOpr.writeConfig("usbAudioInputPid", "0", null)
                }
                audioInputName = audioInputAdapter.getDeviceDisplayName(position)
            },
        )
    }

    // -- Audio Output Device Picker --
    if (showAudioOutputPicker) {
        AudioDevicePickerDialog(
            title = stringResource(R.string.settings_audio_output),
            adapter = audioOutputAdapter,
            currentDeviceId = GeneralVariables.audioOutputDeviceId,
            onDismiss = { showAudioOutputPicker = false },
            onSelect = { position ->
                showAudioOutputPicker = false
                val deviceId = audioOutputAdapter.getDeviceId(position)
                GeneralVariables.audioOutputDeviceId = deviceId
                mainViewModel.databaseOpr.writeConfig("audioOutputDevice", deviceId.toString(), null)

                val usbInfo = audioOutputAdapter.getUsbAudioDeviceInfo(position)
                if (usbInfo != null) {
                    GeneralVariables.usbAudioOutputVendorId = usbInfo.device.vendorId
                    GeneralVariables.usbAudioOutputProductId = usbInfo.device.productId
                    mainViewModel.databaseOpr.writeConfig(
                        "usbAudioOutputVid", GeneralVariables.usbAudioOutputVendorId.toString(), null,
                    )
                    mainViewModel.databaseOpr.writeConfig(
                        "usbAudioOutputPid", GeneralVariables.usbAudioOutputProductId.toString(), null,
                    )
                    mainViewModel.requestUsbPermissionIfNeeded(usbInfo.device)
                } else if (deviceId != -1) {
                    GeneralVariables.usbAudioOutputVendorId = 0
                    GeneralVariables.usbAudioOutputProductId = 0
                    mainViewModel.databaseOpr.writeConfig("usbAudioOutputVid", "0", null)
                    mainViewModel.databaseOpr.writeConfig("usbAudioOutputPid", "0", null)
                }
                audioOutputName = audioOutputAdapter.getDeviceDisplayName(position)
            },
        )
    }

    // =====================================================================
    // SCREEN CONTENT
    // =====================================================================

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // -- Top bar --
        TopBar(title = stringResource(R.string.settings_title))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // =====================================================================
            // 1. OPERATOR IDENTITY
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_operator_identity)) {
                OperatorCard(
                    callsign = callsign,
                    grid = grid,
                    rigName = rigName,
                    antenna = antennaDisplay,
                    power = powerDisplay,
                    modifier = Modifier.padding(bottom = 4.dp),
                    onClick = { showEditOperator = true },
                )
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsRow(
                        label = stringResource(R.string.settings_auto_update_grid),
                        description = stringResource(R.string.settings_auto_update_grid_desc),
                        toggle = autoUpdateGridFromGPS,
                        onToggleChange = { checked ->
                            if (checked) {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_FINE_LOCATION,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    val activity = context as? Activity
                                    if (activity != null) {
                                        ActivityCompat.requestPermissions(
                                            activity,
                                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                            42,
                                        )
                                    }
                                }
                            }
                            autoUpdateGridFromGPS = checked
                            GeneralVariables.autoUpdateGridFromGPS = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "autoGridFromGPS", if (checked) "1" else "0", null,
                            )
                            GridLocationUpdater.refresh(context, mainViewModel)
                        },
                    )
                }
            }

            // =====================================================================
            // 2. RADIO
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_radio)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_rig_model),
                            value = rigModelStr,
                            showChevron = true,
                            onClick = { showRigModelPicker = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_control_mode),
                            value = controlModeStr,
                            showChevron = true,
                            onClick = { showControlModePicker = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_connection_mode),
                            value = connectModeStr,
                            showChevron = isCatMode,
                            onClick = if (isCatMode) {{ showConnectionMode = true }} else null,
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_baud_rate),
                            value = baudRateStr,
                            showChevron = isCatMode,
                            onClick = if (isCatMode) {{ showBaudRatePicker = true }} else null,
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_band_frequency),
                            value = bandStr,
                            showChevron = true,
                            onClick = { showBandPicker = true },
                        )
                        SectionDivider()
                        run {
                            val total = OperationBand.getAllWaveLengths().size
                            SettingsRow(
                                label = stringResource(R.string.settings_enabled_bands),
                                value = stringResource(
                                    R.string.settings_bands_enabled_format,
                                    total - excludedBands.size, total,
                                ),
                                showChevron = true,
                                onClick = { showEnabledBands = true },
                            )
                        }
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_audio_frequency),
                            value = audioFreqStr,
                            showChevron = !synFrequency,
                            onClick = if (!synFrequency) {{ showAudioFreq = true }} else null,
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_spectrum_width),
                            value = stringResource(R.string.settings_hz_format, spectrumWidth),
                            showChevron = true,
                            onClick = { showSpectrumWidth = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 2b. AUDIO
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_audio)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_audio_input),
                            value = audioInputName,
                            showChevron = true,
                            onClick = {
                                audioInputAdapter.refreshDevices()
                                showAudioInputPicker = true
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_audio_output),
                            value = audioOutputName,
                            showChevron = true,
                            onClick = {
                                audioOutputAdapter.refreshDevices()
                                showAudioOutputPicker = true
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_tx_volume),
                            description = stringResource(R.string.settings_tx_volume_desc),
                            value = stringResource(R.string.settings_percent_format, txVolume),
                            showChevron = true,
                            onClick = { showTxVolume = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 3. TRANSMISSION
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_transmission)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_tx_rx_split),
                            description = stringResource(R.string.settings_tx_rx_split_desc),
                            toggle = synFrequency,
                            onToggleChange = { checked ->
                                synFrequency = checked
                                GeneralVariables.synFrequency = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "synFreq", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_tx_watchdog),
                            description = stringResource(R.string.settings_tx_watchdog_desc),
                            value = watchdogStr,
                            showChevron = true,
                            onClick = { showWatchdog = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_stop_after),
                            description = stringResource(R.string.settings_stop_after_desc),
                            value = if (noReplyLimit == 0) stringResource(R.string.common_off)
                            else stringResource(R.string.settings_tries_format, noReplyLimit),
                            showChevron = true,
                            onClick = { showStopAfter = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 3b. TX PROTECTION
            // =====================================================================
            SettingsSection(title = "TX PROTECTION") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "Auto Volume (ALC)",
                            description = "Automatically adjust TX volume to keep ALC in target range",
                            toggle = autoVolumeEnabled,
                            onToggleChange = { checked ->
                                autoVolumeEnabled = checked
                                GeneralVariables.autoVolumeEnabled = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "autoVolumeEnabled", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        if (autoVolumeEnabled) {
                            SectionDivider()
                            // ALC target range — two values displayed as a label row
                            SettingsRow(
                                label = "ALC Target Range",
                                description = "Low: $alcTargetLow  High: $alcTargetHigh  (0-255 normalized)",
                                value = "$alcTargetLow – $alcTargetHigh",
                            )
                            // Low slider
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Low",
                                    style = TextStyle(fontSize = 12.sp, color = TextMuted),
                                    modifier = Modifier.width(32.dp),
                                )
                                Slider(
                                    value = alcTargetLow.toFloat(),
                                    onValueChange = { v ->
                                        val clamped = v.toInt().coerceIn(10, alcTargetHigh - 10)
                                        alcTargetLow = clamped
                                        GeneralVariables.alcTargetLow = clamped
                                    },
                                    onValueChangeFinished = {
                                        mainViewModel.databaseOpr.writeConfig(
                                            "alcTargetLow", alcTargetLow.toString(), null,
                                        )
                                    },
                                    valueRange = 10f..200f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Accent,
                                        activeTrackColor = Accent,
                                    ),
                                )
                            }
                            // High slider
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "High",
                                    style = TextStyle(fontSize = 12.sp, color = TextMuted),
                                    modifier = Modifier.width(32.dp),
                                )
                                Slider(
                                    value = alcTargetHigh.toFloat(),
                                    onValueChange = { v ->
                                        val clamped = v.toInt().coerceIn(alcTargetLow + 10, 250)
                                        alcTargetHigh = clamped
                                        GeneralVariables.alcTargetHigh = clamped
                                    },
                                    onValueChangeFinished = {
                                        mainViewModel.databaseOpr.writeConfig(
                                            "alcTargetHigh", alcTargetHigh.toString(), null,
                                        )
                                    },
                                    valueRange = 20f..250f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Accent,
                                        activeTrackColor = Accent,
                                    ),
                                )
                            }
                        }
                        SectionDivider()
                        SettingsRow(
                            label = "SWR Protection",
                            description = "Stop transmitting and lock TX if SWR exceeds threshold",
                            toggle = swrHaltEnabled,
                            onToggleChange = { checked ->
                                swrHaltEnabled = checked
                                GeneralVariables.swrHaltEnabled = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "swrHaltEnabled", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        if (swrHaltEnabled) {
                            SectionDivider()
                            val swrRatioStr = MeterProtectionController.normalizedSwrToRatio(swrHaltThreshold)
                            SettingsRow(
                                label = "SWR Threshold",
                                description = "TX halts when SWR exceeds this value",
                                value = swrRatioStr,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "1.5:1",
                                    style = TextStyle(fontSize = 12.sp, color = TextMuted),
                                )
                                Slider(
                                    value = swrHaltThreshold.toFloat(),
                                    onValueChange = { v ->
                                        swrHaltThreshold = v.toInt()
                                        GeneralVariables.swrHaltThreshold = v.toInt()
                                    },
                                    onValueChangeFinished = {
                                        mainViewModel.databaseOpr.writeConfig(
                                            "swrHaltThreshold", swrHaltThreshold.toString(), null,
                                        )
                                    },
                                    valueRange = 30f..200f, // ~1.3:1 to ~7.0:1
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Accent,
                                        activeTrackColor = Accent,
                                    ),
                                )
                                Text(
                                    "7:1",
                                    style = TextStyle(fontSize = 12.sp, color = TextMuted),
                                )
                            }
                        }
                    }
                }
            }

            // =====================================================================
            // 4. AUTO-SEQUENCE
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_auto_sequence)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_hunt),
                            description = stringResource(R.string.settings_hunt_desc),
                            toggle = autoFollowCQ,
                            onToggleChange = { checked ->
                                autoFollowCQ = checked
                                GeneralVariables.autoFollowCQ = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "autoFollowCQ", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_auto_call_followed),
                            description = stringResource(R.string.settings_auto_call_followed_desc),
                            toggle = autoCallFollow,
                            onToggleChange = { checked ->
                                autoCallFollow = checked
                                GeneralVariables.autoCallFollow = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "autoCallFollow", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_fast_turnaround),
                            description = stringResource(R.string.settings_fast_turnaround_desc),
                            toggle = earlyDecode,
                            onToggleChange = { checked ->
                                earlyDecode = checked
                                GeneralVariables.earlyDecode = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "earlyDecode", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_auto_cq_after_qso),
                            description = stringResource(R.string.settings_auto_cq_after_qso_desc),
                            toggle = autoCQAfterQSO,
                            onToggleChange = { checked ->
                                autoCQAfterQSO = checked
                                GeneralVariables.autoCQAfterQSO = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "autoCQAfterQSO", if (checked) "1" else "0", null,
                                )
                            },
                        )
                    }
                }
            }

            // =====================================================================
            // 4b. DECODE HIGHLIGHTS
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_decode_highlights)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_highlight_new_dxcc),
                            description = stringResource(R.string.settings_highlight_new_dxcc_desc),
                            toggle = highlightNewDxcc,
                            onToggleChange = { checked ->
                                highlightNewDxcc = checked
                                GeneralVariables.highlightNewDxcc = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "highlightNewDxcc", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_highlight_new_grid),
                            description = stringResource(R.string.settings_highlight_new_grid_desc),
                            toggle = highlightNewGrid,
                            onToggleChange = { checked ->
                                highlightNewGrid = checked
                                GeneralVariables.highlightNewGrid = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "highlightNewGrid", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_highlight_new_band),
                            description = stringResource(R.string.settings_highlight_new_band_desc),
                            toggle = highlightNewBand,
                            onToggleChange = { checked ->
                                highlightNewBand = checked
                                GeneralVariables.highlightNewBand = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "highlightNewBand", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_highlight_pota),
                            description = stringResource(R.string.settings_highlight_pota_desc),
                            toggle = highlightPota,
                            onToggleChange = { checked ->
                                highlightPota = checked
                                GeneralVariables.highlightPota = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "highlightPota", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_highlight_worked),
                            description = stringResource(R.string.settings_highlight_worked_desc),
                            toggle = highlightWorked,
                            onToggleChange = { checked ->
                                highlightWorked = checked
                                GeneralVariables.highlightWorked = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "highlightWorked", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_distance_unit),
                            description = stringResource(R.string.settings_distance_unit_desc),
                            toggle = distanceInMiles,
                            onToggleChange = { checked ->
                                distanceInMiles = checked
                                GeneralVariables.distanceInMiles = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "distanceInMiles", if (checked) "1" else "0", null,
                                )
                            },
                        )
                    }
                }
            }

            // =====================================================================
            // 4c. CALLSIGN BLOCKLIST
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_callsign_blocklist)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_exact_callsigns),
                            description = stringResource(R.string.settings_exact_callsigns_desc),
                            value = blockedExact.ifBlank { stringResource(R.string.common_none) },
                            showChevron = true,
                            onClick = { showBlockExactDialog = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_prefixes),
                            description = stringResource(R.string.settings_prefixes_desc),
                            value = blockedPrefixes.ifBlank { stringResource(R.string.common_none) },
                            showChevron = true,
                            onClick = { showBlockPrefixDialog = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_keywords),
                            description = stringResource(R.string.settings_keywords_desc),
                            value = blockedKeywords.ifBlank { stringResource(R.string.common_none) },
                            showChevron = true,
                            onClick = { showBlockKeywordDialog = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 4d. DECODE FILTERS
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_decode_filters)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_show_only_cq),
                            description = stringResource(R.string.settings_show_only_cq_desc),
                            toggle = filterShowOnlyCQ,
                            onToggleChange = { checked ->
                                filterShowOnlyCQ = checked
                                GeneralVariables.filterShowOnlyCQ = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "filterShowOnlyCQ", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_dx_only),
                            description = stringResource(R.string.settings_dx_only_desc),
                            toggle = filterDxOnly,
                            onToggleChange = { checked ->
                                filterDxOnly = checked
                                GeneralVariables.filterDxOnly = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "filterDxOnly", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_needed_only),
                            description = stringResource(R.string.settings_needed_only_desc),
                            toggle = filterNeededOnly,
                            onToggleChange = { checked ->
                                filterNeededOnly = checked
                                GeneralVariables.filterNeededOnly = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "filterNeededOnly", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_filter_by_continent),
                            description = stringResource(R.string.settings_filter_by_continent_desc),
                            toggle = filterByContinent,
                            onToggleChange = { checked ->
                                filterByContinent = checked
                                GeneralVariables.filterByContinent = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "filterByContinent", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        if (filterByContinent) {
                            SectionDivider()
                            SettingsRow(
                                label = stringResource(R.string.settings_continent),
                                value = continentNames.getOrElse(
                                    continentCodes.indexOf(filterContinent),
                                ) { filterContinent },
                                showChevron = true,
                                onClick = { showContinentPicker = true },
                            )
                        }
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_skip_directional_cq),
                            description = stringResource(R.string.settings_skip_directional_cq_desc),
                            toggle = respectDirectionalCQ,
                            onToggleChange = { checked ->
                                respectDirectionalCQ = checked
                                GeneralVariables.respectDirectionalCQ = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "respectDirectionalCQ", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_hide_directional_cq),
                            description = stringResource(R.string.settings_hide_directional_cq_desc),
                            toggle = filterDirectionalCQ,
                            onToggleChange = { checked ->
                                filterDirectionalCQ = checked
                                GeneralVariables.filterDirectionalCQ = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "filterDirectionalCQ", if (checked) "1" else "0", null,
                                )
                            },
                        )
                    }
                }
            }

            // =====================================================================
            // 4e. NEEDED-DX ALERTS
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_needed_dx_alerts)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_alert_new_dxcc),
                            description = stringResource(R.string.settings_alert_new_dxcc_desc),
                            toggle = alertNewDxcc,
                            onToggleChange = { checked ->
                                alertNewDxcc = checked
                                GeneralVariables.alertNewDxcc = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "alertNewDxcc", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_alert_new_state),
                            description = stringResource(R.string.settings_alert_new_state_desc),
                            toggle = alertNewState,
                            onToggleChange = { checked ->
                                alertNewState = checked
                                GeneralVariables.alertNewState = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "alertNewState", if (checked) "1" else "0", null,
                                )
                            },
                        )
                    }
                }
            }

            // =====================================================================
            // 5. LOGGING & AWARDS
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_logging_awards)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_save_swl_decodes),
                            description = stringResource(R.string.settings_save_swl_decodes_desc),
                            toggle = saveSWLMessage,
                            onToggleChange = { checked ->
                                saveSWLMessage = checked
                                GeneralVariables.saveSWLMessage = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "saveSWL", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_save_swl_qsos),
                            description = stringResource(R.string.settings_save_swl_qsos_desc),
                            toggle = saveSWL_QSO,
                            onToggleChange = { checked ->
                                saveSWL_QSO = checked
                                GeneralVariables.saveSWL_QSO = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "saveSWLQSO", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_pskreporter),
                            description = stringResource(R.string.settings_pskreporter_desc),
                            toggle = enablePskReporter,
                            onToggleChange = { checked ->
                                enablePskReporter = checked
                                GeneralVariables.enablePskReporter = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "enablePskReporter", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_qrz_com),
                            description = stringResource(R.string.settings_qrz_com_desc),
                            toggle = enableQRZ,
                            onToggleChange = { checked ->
                                enableQRZ = checked
                                GeneralVariables.enableQRZ = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "enableQRZ", if (checked) "1" else "0", null,
                                )
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_qrz_profile_lookup),
                            description = stringResource(R.string.settings_qrz_profile_lookup_desc),
                            value = if (qrzXmlUser.isNotEmpty() && qrzXmlPass.isNotEmpty()) {
                                qrzXmlUser
                            } else {
                                stringResource(R.string.common_not_configured)
                            },
                            showChevron = true,
                            onClick = { showQrzCreds = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_cloudlog),
                            description = stringResource(R.string.settings_cloudlog_desc),
                            value = cloudlogAddress.ifEmpty { stringResource(R.string.common_not_configured) },
                            toggle = enableCloudlog,
                            onToggleChange = { checked ->
                                enableCloudlog = checked
                                GeneralVariables.enableCloudlog = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "enableCloudlog", if (checked) "1" else "0", null,
                                )
                            },
                            showChevron = true,
                            onClick = { showCloudlog = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 6. ADVANCED
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_advanced)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.settings_ptt_delay),
                            description = stringResource(R.string.settings_ptt_delay_desc),
                            value = pttDelayStr,
                            showChevron = true,
                            onClick = { showPttDelay = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_tx_delay),
                            description = stringResource(R.string.settings_tx_delay_desc),
                            value = txDelayStr,
                            showChevron = true,
                            onClick = { showTxDelay = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_late_start_tolerance),
                            description = stringResource(R.string.settings_late_start_tolerance_desc),
                            value = stringResource(R.string.settings_milliseconds_format, lateStartMs),
                            showChevron = true,
                            onClick = { showLateStart = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 7. ABOUT
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "FT8US",
                            description = stringResource(
                                R.string.settings_build_date_format,
                                GeneralVariables.BUILD_DATE,
                            ),
                            value = stringResource(
                                R.string.settings_version_value,
                                GeneralVariables.VERSION,
                            ),
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_faq_support),
                            showChevron = true,
                            onClick = { showAbout = true },
                        )
                        if (debugEnabled) {
                            SectionDivider()
                            SettingsRow(
                                label = stringResource(R.string.settings_debug),
                                description = stringResource(R.string.settings_debug_desc),
                                showChevron = true,
                                onClick = { showDebugScreen = true },
                            )
                        }
                    }
                }
            }

            // =====================================================================
            // 8. LANGUAGE
            // =====================================================================
            SettingsSection(title = stringResource(R.string.settings_section_language)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                    val currentLangIndex = LANGUAGE_TAGS
                        .indexOfFirst { it.isNotEmpty() && currentTags.startsWith(it) }
                    val currentLangRes = when (LANGUAGE_TAGS.getOrElse(currentLangIndex) { "" }) {
                        "en" -> R.string.language_name_en
                        "zh-CN" -> R.string.language_name_zh_cn
                        "zh-TW" -> R.string.language_name_zh_tw
                        "ru" -> R.string.language_name_ru
                        "es" -> R.string.language_name_es
                        "fr" -> R.string.language_name_fr
                        "ja" -> R.string.language_name_ja
                        else -> R.string.settings_language_system
                    }
                    SettingsRow(
                        label = stringResource(R.string.settings_language),
                        description = stringResource(R.string.settings_language_desc),
                        value = stringResource(currentLangRes),
                        showChevron = true,
                        onClick = { showLanguagePicker = true },
                    )
                }
            }

            // Bottom spacer for scroll overscroll / nav bar inset
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * A settings section with an uppercase muted title and its content block.
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = TextFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.08.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
        content()
    }
}

/**
 * Thin divider between rows inside a [GlassCard].
 */
@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = Border,
    )
}

// ---------------------------------------------------------------------------
// Reusable dialog composables
// ---------------------------------------------------------------------------

/**
 * Dialog for editing callsign, grid locator, antenna, and power.
 */
@Composable
private fun EditOperatorDialog(
    initialCallsign: String,
    initialGrid: String,
    initialAntenna: String = "",
    initialPowerWatts: Int = 0,
    onDismiss: () -> Unit,
    onSave: (callsign: String, grid: String, antenna: String, powerWatts: Int) -> Unit,
) {
    var callsignInput by remember { mutableStateOf(TextFieldValue(initialCallsign)) }
    var gridInput by remember { mutableStateOf(TextFieldValue(initialGrid)) }
    var antennaInput by remember { mutableStateOf(TextFieldValue(initialAntenna)) }
    var powerInput by remember {
        mutableStateOf(TextFieldValue(if (initialPowerWatts > 0) initialPowerWatts.toString() else ""))
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_edit_operator_identity),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            OutlinedTextField(
                value = callsignInput,
                onValueChange = { callsignInput = it },
                label = { Text(stringResource(R.string.settings_callsign)) },
                placeholder = { Text(stringResource(R.string.settings_callsign_hint), color = TextFaint) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = gridInput,
                onValueChange = { gridInput = it },
                label = { Text(stringResource(R.string.settings_grid_locator)) },
                placeholder = { Text(stringResource(R.string.settings_grid_locator_hint), color = TextFaint) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 16.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = antennaInput,
                onValueChange = { antennaInput = it },
                label = { Text("Antenna") },
                placeholder = { Text("e.g. EFHW 40-10m", color = TextFaint) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = powerInput,
                onValueChange = { new ->
                    if (new.text.all { it.isDigit() }) powerInput = new
                },
                label = { Text("Power (watts)") },
                placeholder = { Text("e.g. 100", color = TextFaint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        val watts = powerInput.text.toIntOrNull() ?: 0
                        onSave(callsignInput.text, gridInput.text, antennaInput.text, watts)
                    },
                ) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Dialog for editing a comma/space-separated list of tokens (blocklist entries).
 * One multi-line text field; the caller parses + persists the saved string.
 */
@Composable
private fun TextListDialog(
    title: String,
    description: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue(initialValue)) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = description,
                color = TextMuted,
                fontSize = 13.sp,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.settings_blocklist_hint), color = TextFaint) },
                singleLine = false,
                minLines = 2,
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 15.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(onClick = { onSave(input.text) }) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Dialog for configuring Cloudlog server address, API key, and station ID.
 * Includes a Test Connection button that calls [ThirdPartyService.CheckCloudlogConnection].
 */
@Composable
private fun CloudlogSettingsDialog(
    initialAddress: String,
    initialApiKey: String,
    initialStationId: String,
    onDismiss: () -> Unit,
    onSave: (address: String, apiKey: String, stationId: String) -> Unit,
) {
    var addressInput by remember { mutableStateOf(TextFieldValue(initialAddress)) }
    var apiKeyInput by remember { mutableStateOf(TextFieldValue(initialApiKey)) }
    var stationIdInput by remember { mutableStateOf(TextFieldValue(initialStationId)) }

    // Test connection state: null = idle, true = pass, false = fail
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Station picker state
    var stationList by remember { mutableStateOf<List<ThirdPartyService.StationProfile>>(emptyList()) }
    var isFetchingStations by remember { mutableStateOf(false) }
    var manualStationEntry by remember { mutableStateOf(false) }
    var showStationPicker by remember { mutableStateOf(false) }

    // Auto-fetch stations on dialog open if credentials are present
    LaunchedEffect(Unit) {
        val addr = initialAddress.trim()
        val key = initialApiKey.trim()
        if (addr.isNotBlank() && key.isNotBlank()) {
            isFetchingStations = true
            val result = withContext(Dispatchers.IO) {
                ThirdPartyService.FetchCloudlogStations(addr, key)
            }
            stationList = result
            isFetchingStations = false
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_logging_server),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = stringResource(R.string.settings_logging_server_desc),
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            OutlinedTextField(
                value = addressInput,
                onValueChange = {
                    addressInput = it
                    testResult = null
                    stationList = emptyList()
                },
                label = { Text(stringResource(R.string.settings_server_address)) },
                placeholder = { Text("https://log.example.com/", color = TextFaint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it
                    testResult = null
                    stationList = emptyList()
                },
                label = { Text(stringResource(R.string.settings_api_key)) },
                placeholder = { Text(stringResource(R.string.settings_api_key_hint), color = TextFaint) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Station ID: picker when stations are loaded, manual entry otherwise
            if (isFetchingStations) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp,
                        color = Accent,
                    )
                    Text(
                        text = stringResource(R.string.settings_loading_stations),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
            } else if (stationList.isNotEmpty() && !manualStationEntry) {
                // Picker mode: show selected station as a clickable read-only field
                val selectedLabel = stationList
                    .firstOrNull { it.stationId == stationIdInput.text }
                    ?.displayLabel()
                    ?: stationIdInput.text.ifBlank { stringResource(R.string.settings_select_a_station) }

                Column {
                    Text(
                        text = stringResource(R.string.settings_station_id),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedLabel,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(BgSurface3)
                            .clickable { showStationPicker = true }
                            .padding(12.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_enter_manually),
                        color = Accent,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { manualStationEntry = true },
                    )
                }
            } else {
                // Manual entry mode
                OutlinedTextField(
                    value = stationIdInput,
                    onValueChange = { stationIdInput = it },
                    label = { Text(stringResource(R.string.settings_station_id)) },
                    placeholder = { Text(stringResource(R.string.settings_station_id_hint), color = TextFaint) },
                    singleLine = true,
                    colors = fieldColors,
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (stationList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_choose_from_server),
                        color = Accent,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { manualStationEntry = false },
                    )
                }
            }

            // Test Connection button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        // Write current input values to GeneralVariables so the test uses them
                        GeneralVariables.cloudlogServerAddress = addressInput.text
                        GeneralVariables.cloudlogApiKey = apiKeyInput.text
                        GeneralVariables.cloudlogStationID = stationIdInput.text
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ThirdPartyService.CheckCloudlogConnection()
                            }
                            testResult = result
                            isTesting = false
                            // On success, also fetch station profiles
                            if (result) {
                                isFetchingStations = true
                                val stations = withContext(Dispatchers.IO) {
                                    ThirdPartyService.FetchCloudlogStations(
                                        addressInput.text.trim(),
                                        apiKeyInput.text.trim(),
                                    )
                                }
                                stationList = stations
                                isFetchingStations = false
                            }
                        }
                    },
                    enabled = !isTesting,
                ) {
                    Text(
                        text = stringResource(R.string.common_test_connection),
                        color = if (isTesting) TextFaint else Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(16.dp)
                            .height(16.dp),
                        strokeWidth = 2.dp,
                        color = Accent,
                    )
                }
                if (testResult != null) {
                    Text(
                        text = if (testResult == true) stringResource(R.string.common_pass)
                        else stringResource(R.string.common_fail),
                        color = if (testResult == true) StatusConfirmed else StatusBad,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        onSave(
                            addressInput.text.trim(),
                            apiKeyInput.text.trim(),
                            stationIdInput.text.trim(),
                        )
                    },
                ) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // Station picker dialog
    if (showStationPicker && stationList.isNotEmpty()) {
        val items = stationList.map { it.displayLabel() }
        val selectedIdx = stationList.indexOfFirst { it.stationId == stationIdInput.text }
        ListPickerDialog(
            title = stringResource(R.string.settings_station_profile),
            items = items,
            selectedIndex = selectedIdx.coerceAtLeast(0),
            onDismiss = { showStationPicker = false },
            onSelect = { index ->
                stationIdInput = TextFieldValue(stationList[index].stationId)
                showStationPicker = false
            },
        )
    }
}

@Composable
private fun QrzCredsDialog(
    initialUsername: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSave: (username: String, password: String) -> Unit,
) {
    var userInput by remember { mutableStateOf(TextFieldValue(initialUsername)) }
    var passInput by remember { mutableStateOf(TextFieldValue(initialPassword)) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_qrz_profile_lookup),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Text(
                text = stringResource(R.string.settings_qrz_creds_desc),
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it; testResult = null },
                label = { Text(stringResource(R.string.settings_username)) },
                placeholder = { Text(stringResource(R.string.settings_username_hint), color = TextFaint) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = passInput,
                onValueChange = { passInput = it; testResult = null },
                label = { Text(stringResource(R.string.settings_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Test Connection
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        GeneralVariables.qrzXmlUsername = userInput.text.trim()
                        GeneralVariables.qrzXmlPassword = passInput.text
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                radio.ks3ckc.ft8us.qrz.QrzXmlClient.testConnection()
                            }
                            testResult = result
                            isTesting = false
                        }
                    },
                    enabled = !isTesting,
                ) {
                    Text(
                        text = stringResource(R.string.common_test_connection),
                        color = if (isTesting) TextFaint else Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(16.dp)
                            .height(16.dp),
                        strokeWidth = 2.dp,
                        color = Accent,
                    )
                }
                if (testResult != null) {
                    val ok = testResult == "OK"
                    Text(
                        text = if (ok) stringResource(R.string.common_pass) else testResult!!,
                        color = if (ok) StatusConfirmed else StatusBad,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        onSave(userInput.text.trim(), passInput.text)
                    },
                ) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Per-band visibility toggles. Lists every distinct band name from bands.txt;
 * a band that's switched off is hidden from both band pickers. Lets operators
 * in regions with restricted band plans (e.g. Russia: no 6m/60m) hide bands
 * they may not use.
 */
@Composable
private fun BandToggleDialog(
    excludedBands: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (waveLength: String, enabled: Boolean) -> Unit,
) {
    val waveLengths = remember { OperationBand.getAllWaveLengths() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(vertical = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_enabled_bands),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.settings_enabled_bands_dialog_desc),
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                itemsIndexed(waveLengths) { _, wave ->
                    val enabled = !excludedBands.contains(wave)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(wave, !enabled) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = wave,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Toggle(
                            checked = enabled,
                            onCheckedChange = { onToggle(wave, it) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done), color = Accent)
                }
            }
        }
    }
}

/**
 * Scrollable list picker dialog with highlighted current selection.
 */
@Composable
private fun ListPickerDialog(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (index: Int) -> Unit,
) {
    val listState = rememberLazyListState()

    // Scroll to the selected item when the dialog opens
    LaunchedEffect(selectedIndex) {
        if (selectedIndex > 0) {
            listState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(vertical = 24.dp),
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 0.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    val isSelected = index == selectedIndex
                    val bg = if (isSelected) AccentSoft else BgSurface2
                    val textColor = if (isSelected) Accent else TextPrimary

                    Text(
                        text = item,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .background(bg)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
            }
        }
    }
}

/**
 * Numeric text input dialog with min/max validation.
 */
@Composable
private fun NumberInputDialog(
    title: String,
    suffix: String,
    initialValue: Int,
    min: Int,
    max: Int,
    onDismiss: () -> Unit,
    onSave: (value: Int) -> Unit,
) {
    var textInput by remember { mutableStateOf(TextFieldValue(initialValue.toString())) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            OutlinedTextField(
                value = textInput,
                onValueChange = { newValue ->
                    // Only allow digits
                    if (newValue.text.all { it.isDigit() }) {
                        textInput = newValue
                    }
                },
                label = { Text(stringResource(R.string.settings_min_max_suffix, min, max, suffix)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        val parsed = textInput.text.toIntOrNull() ?: initialValue
                        onSave(parsed.coerceIn(min, max))
                    },
                ) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Dialog for selecting a USB serial port to connect to a rig.
 */
@Composable
private fun SerialPortPickerDialog(
    ports: List<CableSerialPort.SerialPort>,
    onDismiss: () -> Unit,
    onSelect: (CableSerialPort.SerialPort) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(vertical = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_select_serial_port),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                itemsIndexed(ports) { _, port ->
                    Text(
                        text = port.information(),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(port) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
            }
        }
    }
}

/**
 * Slider-based TX volume editor with live update. Dragging commits the new
 * level immediately (no Save/Cancel friction — the next TX uses what you
 * dialed); we persist to the config DB only on dismiss.
 */
@Composable
private fun TxVolumeSliderDialog(
    initialValue: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableIntStateOf(initialValue) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_tx_volume),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            // Big live readout so you can dial it in at a glance — useful in
            // the car where the slider thumb is small relative to the radio's
            // ALC meter you're watching at the same time.
            Text(
                text = stringResource(R.string.settings_percent_format, current),
                color = Accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 48.sp,
            )

            Slider(
                value = current.toFloat(),
                onValueChange = { v ->
                    val clamped = v.toInt().coerceIn(0, 100)
                    if (clamped != current) {
                        current = clamped
                        onChange(clamped)
                    }
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                ),
            )

            Text(
                text = stringResource(R.string.settings_tx_volume_advice),
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Generic informational dialog: title + body text + dismiss.
 */
@Composable
private fun InfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = body,
                color = TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_ok), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * About dialog with version info, credits, and tappable QRZ links for the authors.
 */
@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    onToggleDebug: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    // Hidden debug-mode unlock: 7 consecutive taps on the version block flips
    // GeneralVariables.debugModeEnabled (and persists it). Counter resets when
    // the dialog re-opens, matching Android's developer-options UX.
    var versionTaps by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "FT8AF",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = stringResource(
                    R.string.settings_about_body,
                    GeneralVariables.VERSION,
                    GeneralVariables.VERSION_CODE,
                    GeneralVariables.BUILD_DATE,
                ),
                color = TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.clickable {
                    versionTaps += 1
                    if (versionTaps >= 7) {
                        versionTaps = 0
                        onToggleDebug()
                    }
                },
            )

            Text(
                text = stringResource(R.string.settings_website),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = "ft8af.app",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://ft8af.app") },
            )

            Text(
                text = stringResource(R.string.settings_community),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = "github.com/patrickrb/FT8AF",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://github.com/patrickrb/FT8AF") },
            )
            Text(
                text = "discord.gg/UeE3ZpwRG",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://discord.gg/UeE3ZpwRG") },
            )

            Text(
                text = stringResource(R.string.settings_built_by),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = stringResource(R.string.settings_author_k1af),
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://www.qrz.com/db/K1AF") },
            )
            Text(
                text = stringResource(R.string.settings_author_n0rc),
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://www.qrz.com/db/N0RC") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_ok), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Dialog for selecting an audio input or output device from [AudioDeviceSpinnerAdapter].
 */
@Composable
private fun AudioDevicePickerDialog(
    title: String,
    adapter: AudioDeviceSpinnerAdapter,
    currentDeviceId: Int,
    onDismiss: () -> Unit,
    onSelect: (position: Int) -> Unit,
) {
    val count = adapter.count
    val items = (0 until count).map { adapter.getDeviceDisplayName(it) }
    val selectedIndex = adapter.getPositionByDeviceId(currentDeviceId).coerceIn(0, count - 1)

    ListPickerDialog(
        title = title,
        items = items,
        selectedIndex = selectedIndex,
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}
