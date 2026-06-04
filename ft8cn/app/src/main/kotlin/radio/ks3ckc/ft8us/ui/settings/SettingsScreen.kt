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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bg7yoz.ft8cn.Ft8Message
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.connector.CableSerialPort
import com.bg7yoz.ft8cn.location.GridLocationUpdater
import com.bg7yoz.ft8cn.connector.ConnectMode
import com.bg7yoz.ft8cn.database.ControlMode
import com.bg7yoz.ft8cn.database.OperationBand
import com.bg7yoz.ft8cn.database.RigNameList
import com.bg7yoz.ft8cn.ft8signal.FT8Package
import com.bg7yoz.ft8cn.rigs.BaseRigOperation
import com.bg7yoz.ft8cn.rigs.InstructionSet
import com.bg7yoz.ft8cn.ui.AudioDeviceSpinnerAdapter
import com.bg7yoz.ft8cn.ui.LoginIcomRadioDialog
import com.bg7yoz.ft8cn.ui.SelectBluetoothDialog
import com.bg7yoz.ft8cn.ui.SelectFlexRadioDialog
import com.bg7yoz.ft8cn.ui.SelectXieguRadioDialog
import radio.ks3ckc.ft8us.theme.*
import radio.ks3ckc.ft8us.ui.components.GlassCard
import radio.ks3ckc.ft8us.ui.components.SettingsRow
import radio.ks3ckc.ft8us.ui.components.TopBar
import radio.ks3ckc.ft8us.ui.components.selectBandIndex

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
    var autoUpdateGridFromGPS by remember { mutableStateOf(GeneralVariables.autoUpdateGridFromGPS) }
    var enableCloudlog by remember { mutableStateOf(GeneralVariables.enableCloudlog) }
    var enableQRZ by remember { mutableStateOf(GeneralVariables.enableQRZ) }
    var enablePskReporter by remember { mutableStateOf(GeneralVariables.enablePskReporter) }
    var saveSWLMessage by remember { mutableStateOf(GeneralVariables.saveSWLMessage) }
    var saveSWL_QSO by remember { mutableStateOf(GeneralVariables.saveSWL_QSO) }

    // Observe serial ports for USB Cable picker
    val serialPorts by mainViewModel.mutableSerialPorts.observeAsState()
    var showSerialPortPicker by remember { mutableStateOf(false) }

    // Dialog visibility state
    var showEditOperator by remember { mutableStateOf(false) }
    var showConnectionMode by remember { mutableStateOf(false) }
    var showBandPicker by remember { mutableStateOf(false) }
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

    // Operator identity edit state
    var callsignState by remember { mutableStateOf(GeneralVariables.myCallsign.orEmpty()) }
    var gridState by remember { mutableStateOf(GeneralVariables.getMyMaidenheadGrid().orEmpty()) }

    // Mutable state for settings that need to trigger recomposition on change
    var watchdogMs by remember { mutableIntStateOf(GeneralVariables.launchSupervision) }
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
    val audioFreqStr = "${GeneralVariables.getBaseFrequencyStr()} Hz"
    val txDelayStr = "$txDelay ms"
    val pttDelayStr = "$pttDelay ms"
    val watchdogMinutes = watchdogMs / 60000
    val watchdogStr = if (watchdogMinutes == 0) "Off" else "$watchdogMinutes min"
    val rigConnected = mainViewModel.isRigConnected()
    val rigName = if (rigConnected) {
        mainViewModel.baseRig?.javaClass?.simpleName ?: "--"
    } else {
        "Not connected"
    }
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
            onDismiss = { showEditOperator = false },
            onSave = { newCallsign, newGrid ->
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

                showEditOperator = false
            },
        )
    }

    // -- Connection Mode Picker --
    if (showConnectionMode) {
        val connectionOptions = listOf("USB Cable", "Bluetooth", "Network")
        val currentIndex = GeneralVariables.connectMode.coerceIn(0, 2)
        ListPickerDialog(
            title = "Connection Mode",
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
                        SelectBluetoothDialog(context, mainViewModel).show()
                    }
                    ConnectMode.NETWORK -> {
                        when (GeneralVariables.instructionSet) {
                            InstructionSet.FLEX_NETWORK ->
                                SelectFlexRadioDialog(context, mainViewModel).show()
                            InstructionSet.XIEGU_6100_FT8CNS ->
                                SelectXieguRadioDialog(context, mainViewModel).show()
                            else ->
                                LoginIcomRadioDialog(context, mainViewModel).show()
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
                title = "USB Cable",
                body = "No USB serial devices detected. Please connect a USB cable to your radio and try again.",
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

    // -- Band & Frequency Picker --
    if (showBandPicker) {
        val bandItems = (0 until OperationBand.bandList.size).map { i ->
            OperationBand.getBandInfo(i)
        }
        val currentBandIndex = GeneralVariables.bandListIndex.coerceAtLeast(0)
        ListPickerDialog(
            title = "Band & Frequency",
            items = bandItems,
            selectedIndex = currentBandIndex,
            onDismiss = { showBandPicker = false },
            onSelect = { index ->
                showBandPicker = false
                selectBandIndex(mainViewModel, context, index)
            },
        )
    }

    // -- Audio Frequency Editor --
    if (showAudioFreq) {
        val audioFreqMax = spectrumWidth - 100
        NumberInputDialog(
            title = "Audio Frequency",
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
            title = "Spectrum Width",
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
        val watchdogOptions = mutableListOf("Off")
        for (i in 1..10) {
            watchdogOptions.add("${i * 10 - 5} min")
        }
        // Find current selection index from stored ms value
        val currentWatchdogIndex = if (watchdogMs == 0) {
            0
        } else {
            ((watchdogMs - 5 * 60 * 1000) / 60 / 1000 / 10).coerceIn(0, 10)
        }
        ListPickerDialog(
            title = "TX Watchdog",
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
        val stopAfterOptions = mutableListOf("Off")
        for (i in 1..30) {
            stopAfterOptions.add("$i tries")
        }
        ListPickerDialog(
            title = "Stop After",
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
        val pttDelayOptions = (0 until 20).map { "${it * 10} ms" }
        val currentPttIndex = (pttDelay / 10).coerceIn(0, 19)
        ListPickerDialog(
            title = "PTT Delay",
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
            title = "TX Delay",
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
            title = "Late-start Tolerance",
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
                    if (next) "Debug mode enabled" else "Debug mode disabled",
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
            title = "Rig Model",
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
            title = "Control Mode",
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
            title = "Baud Rate",
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
            title = "Audio Input",
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
            title = "Audio Output",
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
        TopBar(title = "Settings")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // =====================================================================
            // 1. OPERATOR IDENTITY
            // =====================================================================
            SettingsSection(title = "OPERATOR IDENTITY") {
                OperatorCard(
                    callsign = callsign,
                    grid = grid,
                    rigName = rigName,
                    modifier = Modifier.padding(bottom = 4.dp),
                    onClick = { showEditOperator = true },
                )
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsRow(
                        label = "Auto-update Grid (GPS)",
                        description = "Use device GPS to keep your Maidenhead grid current",
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
            SettingsSection(title = "RADIO") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "Rig Model",
                            value = rigModelStr,
                            showChevron = true,
                            onClick = { showRigModelPicker = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Control Mode",
                            value = controlModeStr,
                            showChevron = true,
                            onClick = { showControlModePicker = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Connection Mode",
                            value = connectModeStr,
                            showChevron = isCatMode,
                            onClick = if (isCatMode) {{ showConnectionMode = true }} else null,
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Baud Rate",
                            value = baudRateStr,
                            showChevron = isCatMode,
                            onClick = if (isCatMode) {{ showBaudRatePicker = true }} else null,
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Band & Frequency",
                            value = bandStr,
                            showChevron = true,
                            onClick = { showBandPicker = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Audio Frequency",
                            value = audioFreqStr,
                            showChevron = !synFrequency,
                            onClick = if (!synFrequency) {{ showAudioFreq = true }} else null,
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Spectrum Width",
                            value = "$spectrumWidth Hz",
                            showChevron = true,
                            onClick = { showSpectrumWidth = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 2b. AUDIO
            // =====================================================================
            SettingsSection(title = "AUDIO") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "Audio Input",
                            value = audioInputName,
                            showChevron = true,
                            onClick = {
                                audioInputAdapter.refreshDevices()
                                showAudioInputPicker = true
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Audio Output",
                            value = audioOutputName,
                            showChevron = true,
                            onClick = {
                                audioOutputAdapter.refreshDevices()
                                showAudioOutputPicker = true
                            },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "TX Volume",
                            description = "Transmit audio level (hardware buttons ±5%)",
                            value = "$txVolume%",
                            showChevron = true,
                            onClick = { showTxVolume = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 3. TRANSMISSION
            // =====================================================================
            SettingsSection(title = "TRANSMISSION") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "TX/RX Split",
                            description = "Transmit on a different frequency than receive",
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
                            label = "TX Watchdog",
                            description = "Auto-stop transmit after timeout",
                            value = watchdogStr,
                            showChevron = true,
                            onClick = { showWatchdog = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Stop After",
                            description = "Stop calling after N unanswered attempts",
                            value = if (noReplyLimit == 0) "Off"
                            else "$noReplyLimit tries",
                            showChevron = true,
                            onClick = { showStopAfter = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 4. AUTO-SEQUENCE
            // =====================================================================
            SettingsSection(title = "AUTO-SEQUENCE") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "Hunt (auto-answer CQ)",
                            description = "Proactively call stations calling CQ. Same as the HUNT button on the TX bar. Off = run CQ and work only stations that answer you.",
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
                            label = "Auto-call Followed",
                            description = "Continue calling followed callsigns automatically",
                            toggle = autoCallFollow,
                            onToggleChange = { checked ->
                                autoCallFollow = checked
                                GeneralVariables.autoCallFollow = checked
                                mainViewModel.databaseOpr.writeConfig(
                                    "autoCallFollow", if (checked) "1" else "0", null,
                                )
                            },
                        )
                    }
                }
            }

            // =====================================================================
            // 5. LOGGING & AWARDS
            // =====================================================================
            SettingsSection(title = "LOGGING & AWARDS") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "Save SWL Decodes",
                            description = "Log all decoded messages to the database",
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
                            label = "Save SWL QSOs",
                            description = "Log QSOs detected between other stations",
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
                            label = "PSKReporter",
                            description = "Upload decoded spots to pskreporter.info",
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
                            label = "QRZ.com",
                            description = "Auto-upload QSOs to QRZ Logbook",
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
                            label = "QRZ Profile Lookup",
                            description = "Username + password for QRZ XML API (avatars)",
                            value = if (qrzXmlUser.isNotEmpty() && qrzXmlPass.isNotEmpty()) {
                                qrzXmlUser
                            } else {
                                "Not configured"
                            },
                            showChevron = true,
                            onClick = { showQrzCreds = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Cloudlog / Wavelog / Nextlog",
                            description = "Auto-upload QSOs to a Cloudlog, Wavelog, or Nextlog instance",
                            value = cloudlogAddress.ifEmpty { "Not configured" },
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
            SettingsSection(title = "ADVANCED") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "PTT Delay",
                            description = "Delay after PTT before transmit audio begins",
                            value = pttDelayStr,
                            showChevron = true,
                            onClick = { showPttDelay = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "TX Delay",
                            description = "Delay before transmit to allow prior-cycle decode",
                            value = txDelayStr,
                            showChevron = true,
                            onClick = { showTxDelay = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "Late-start Tolerance",
                            description = "Allow starting TX up to N ms into a cycle; leading audio is clipped so TX ends on the cycle boundary",
                            value = "$lateStartMs ms",
                            showChevron = true,
                            onClick = { showLateStart = true },
                        )
                    }
                }
            }

            // =====================================================================
            // 7. ABOUT
            // =====================================================================
            SettingsSection(title = "ABOUT") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = "FT8US",
                            description = "Build ${GeneralVariables.BUILD_DATE}",
                            value = "v${GeneralVariables.VERSION}",
                        )
                        SectionDivider()
                        SettingsRow(
                            label = "FAQ & Support",
                            showChevron = true,
                            onClick = { showAbout = true },
                        )
                        if (debugEnabled) {
                            SectionDivider()
                            SettingsRow(
                                label = "Debug",
                                description = "View / share debug.log",
                                showChevron = true,
                                onClick = { showDebugScreen = true },
                            )
                        }
                    }
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
 * Dialog for editing callsign and grid locator.
 */
@Composable
private fun EditOperatorDialog(
    initialCallsign: String,
    initialGrid: String,
    onDismiss: () -> Unit,
    onSave: (callsign: String, grid: String) -> Unit,
) {
    var callsignInput by remember { mutableStateOf(TextFieldValue(initialCallsign)) }
    var gridInput by remember { mutableStateOf(TextFieldValue(initialGrid)) }

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
                text = "Edit Operator Identity",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            OutlinedTextField(
                value = callsignInput,
                onValueChange = { callsignInput = it },
                label = { Text("Callsign") },
                placeholder = { Text("e.g. W1AW", color = TextFaint) },
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
                label = { Text("Grid Locator") },
                placeholder = { Text("e.g. FN31pr", color = TextFaint) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 16.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextMuted)
                }
                TextButton(
                    onClick = { onSave(callsignInput.text, gridInput.text) },
                ) {
                    Text("Save", color = Accent, fontWeight = FontWeight.SemiBold)
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
                text = "Logging Server",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = "Cloudlog, Wavelog, and Nextlog all accept the same uploads.",
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
                label = { Text("Server Address") },
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
                label = { Text("API Key") },
                placeholder = { Text("Your Cloudlog API key", color = TextFaint) },
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
                        text = "Loading stations...",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
            } else if (stationList.isNotEmpty() && !manualStationEntry) {
                // Picker mode: show selected station as a clickable read-only field
                val selectedLabel = stationList
                    .firstOrNull { it.stationId == stationIdInput.text }
                    ?.displayLabel()
                    ?: stationIdInput.text.ifBlank { "Select a station" }

                Column {
                    Text(
                        text = "Station ID",
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
                        text = "Enter manually",
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
                    label = { Text("Station ID") },
                    placeholder = { Text("e.g. 1", color = TextFaint) },
                    singleLine = true,
                    colors = fieldColors,
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (stationList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose from server",
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
                        text = "Test Connection",
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
                        text = if (testResult == true) "Pass" else "Fail",
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
                    Text("Cancel", color = TextMuted)
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
                    Text("Save", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // Station picker dialog
    if (showStationPicker && stationList.isNotEmpty()) {
        val items = stationList.map { it.displayLabel() }
        val selectedIdx = stationList.indexOfFirst { it.stationId == stationIdInput.text }
        ListPickerDialog(
            title = "Station Profile",
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
                text = "QRZ Profile Lookup",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Text(
                text = "Used to fetch profile photos for decoded callsigns via the QRZ XML API. Requires a QRZ XML subscription.",
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it; testResult = null },
                label = { Text("Username") },
                placeholder = { Text("Your QRZ.com username", color = TextFaint) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = passInput,
                onValueChange = { passInput = it; testResult = null },
                label = { Text("Password") },
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
                        text = "Test Connection",
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
                        text = if (ok) "Pass" else testResult!!,
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
                    Text("Cancel", color = TextMuted)
                }
                TextButton(
                    onClick = {
                        onSave(userInput.text.trim(), passInput.text)
                    },
                ) {
                    Text("Save", color = Accent, fontWeight = FontWeight.SemiBold)
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
                    Text("Cancel", color = TextMuted)
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
                label = { Text("$min\u2013$max $suffix") },
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
                    Text("Cancel", color = TextMuted)
                }
                TextButton(
                    onClick = {
                        val parsed = textInput.text.toIntOrNull() ?: initialValue
                        onSave(parsed.coerceIn(min, max))
                    },
                ) {
                    Text("Save", color = Accent, fontWeight = FontWeight.SemiBold)
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
                text = "Select Serial Port",
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
                    Text("Cancel", color = TextMuted)
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
                text = "TX Volume",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            // Big live readout so you can dial it in at a glance — useful in
            // the car where the slider thumb is small relative to the radio's
            // ALC meter you're watching at the same time.
            Text(
                text = "$current%",
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
                text = "Adjust live — your next TX uses this level. " +
                    "Watch your radio's ALC and dial down until it's just kissing the line.",
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Accent, fontWeight = FontWeight.SemiBold)
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
                    Text("OK", color = Accent, fontWeight = FontWeight.SemiBold)
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
                text = "Version ${GeneralVariables.VERSION} (build ${GeneralVariables.VERSION_CODE})\n" +
                    "Build date ${GeneralVariables.BUILD_DATE}\n\n" +
                    "FT8, made easy.\n\n" +
                    "A standalone FT8 transceiver app for Android. " +
                    "USB, Bluetooth, and network rig control with " +
                    "automatic sequencing and logging.",
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
                text = "Website",
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
                text = "Built by",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = "K1AF — Patrick Burns (QRZ)",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://www.qrz.com/db/K1AF") },
            )
            Text(
                text = "N0RC — Reid (QRZ)",
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
                    Text("OK", color = Accent, fontWeight = FontWeight.SemiBold)
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
