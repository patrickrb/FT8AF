package radio.ks3ckc.ft8af.ui.settings

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.connector.CableSerialPort
import com.k1af.ft8af.connector.ConnectMode
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.database.RigNameList
import com.k1af.ft8af.rigs.BaseRigOperation
import com.k1af.ft8af.rigs.InstructionSet
import com.k1af.ft8af.ui.AudioDeviceSpinnerAdapter
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.FT8AFIconButton
import radio.ks3ckc.ft8af.ui.components.FT8AFIcons
import radio.ks3ckc.ft8af.ui.components.IntSlider
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow
import radio.ks3ckc.ft8af.ui.components.Toggle
import radio.ks3ckc.ft8af.ui.components.selectBandIndex

/**
 * Radio & audio settings: rig model, control/connection mode, baud rate, band &
 * frequency, enabled bands, audio frequency, spectrum width, audio input/output
 * devices, and TX volume.
 */
@Composable
fun RadioAudioSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Observe reactive fields so band/frequency rows recompose on change.
    val bandIndexLive by GeneralVariables.mutableBandChange.observeAsState(
        GeneralVariables.bandListIndex,
    )
    val baseFreqLive by GeneralVariables.mutableBaseFrequency.observeAsState(
        GeneralVariables.getBaseFrequency(),
    )

    var synFrequency by remember { mutableStateOf(GeneralVariables.synFrequency) }
    var spectrumWidth by remember { mutableIntStateOf(GeneralVariables.getSpectrumWidth()) }
    var connectMode by remember { mutableIntStateOf(GeneralVariables.connectMode) }
    var controlMode by remember { mutableIntStateOf(GeneralVariables.controlMode) }
    var modelNo by remember { mutableIntStateOf(GeneralVariables.modelNo) }
    var baudRate by remember { mutableIntStateOf(GeneralVariables.baudRate) }

    // Mirror of GeneralVariables.excludedBands so the dialog + the "N of M enabled"
    // label recompose as the user toggles bands.
    var excludedBands by remember { mutableStateOf(GeneralVariables.excludedBands.toSet()) }

    // TX Volume state – observe LiveData so hardware button changes update the UI
    val volumeLive by GeneralVariables.mutableVolumePercent.observeAsState(
        GeneralVariables.volumePercent,
    )
    var txVolume by remember { mutableIntStateOf((GeneralVariables.volumePercent * 100).toInt()) }
    // Keep txVolume in sync when hardware buttons (or other sources) update the LiveData
    LaunchedEffect(volumeLive) {
        txVolume = ((volumeLive ?: GeneralVariables.volumePercent) * 100).toInt()
    }

    // Observe serial ports for USB Cable picker
    val serialPorts by mainViewModel.mutableSerialPorts.observeAsState()

    // Audio device adapters + display names
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

    // Dialog visibility state
    var showRigModelPicker by remember { mutableStateOf(false) }
    var showControlModePicker by remember { mutableStateOf(false) }
    var showConnectionMode by remember { mutableStateOf(false) }
    var showBaudRatePicker by remember { mutableStateOf(false) }
    var showBandPicker by remember { mutableStateOf(false) }
    var showEnabledBands by remember { mutableStateOf(false) }
    var showAudioFreq by remember { mutableStateOf(false) }
    var showSpectrumWidth by remember { mutableStateOf(false) }
    var showAudioInputPicker by remember { mutableStateOf(false) }
    var showAudioOutputPicker by remember { mutableStateOf(false) }
    var showTxVolume by remember { mutableStateOf(false) }
    var showSerialPortPicker by remember { mutableStateOf(false) }
    var showBluetoothPicker by remember { mutableStateOf(false) }
    var showFlexRadioPicker by remember { mutableStateOf(false) }
    var showXieguRadioPicker by remember { mutableStateOf(false) }
    var showIcomLogin by remember { mutableStateOf(false) }

    // Derived display strings
    val connectModeStr = ConnectMode.getModeStr(connectMode)
    val bandStr = remember(bandIndexLive) {
        BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)
    }
    val baseFreqStr = remember(baseFreqLive) { GeneralVariables.getBaseFrequencyStr() }
    val audioFreqStr = stringResource(R.string.settings_hz_str_format, baseFreqStr)
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

    // =====================================================================
    // DIALOGS
    // =====================================================================

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
                GeneralVariables.excludedBands = java.util.HashSet(updated)
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

    // -- TX Volume Editor --
    // Slider-based dialog with live update: dragging the thumb updates the
    // in-memory volumePercent immediately so the next TX uses the new level,
    // and we persist to the config DB on dismiss.
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
                // Track this band's saved level when per-band saving is on (#355).
                mainViewModel.savePerBandOutputLevel(txVolume)
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

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_radio_audio),
        onBack = onBack,
    ) {
        // =====================================================================
        // RADIO
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
        // AUDIO
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
                    SectionDivider()
                    run {
                        var showSlider by remember { mutableStateOf(GeneralVariables.showTxVolumeSlider) }
                        SettingsRow(
                            label = stringResource(R.string.settings_show_volume_slider),
                            description = stringResource(R.string.settings_show_volume_slider_desc),
                            toggle = showSlider,
                            onToggleChange = { enabled ->
                                showSlider = enabled
                                GeneralVariables.showTxVolumeSlider = enabled
                                GeneralVariables.mutableShowTxVolumeSlider.postValue(enabled)
                                mainViewModel.databaseOpr.writeConfig(
                                    "showTxVolumeSlider", if (enabled) "1" else "0", null,
                                )
                            },
                        )
                    }
                    SectionDivider()
                    run {
                        var perBand by remember {
                            mutableStateOf(GeneralVariables.saveOutputLevelPerBand)
                        }
                        SettingsRow(
                            label = stringResource(R.string.settings_per_band_volume),
                            description = stringResource(R.string.settings_per_band_volume_desc),
                            toggle = perBand,
                            onToggleChange = { enabled ->
                                perBand = enabled
                                GeneralVariables.saveOutputLevelPerBand = enabled
                                GeneralVariables.mutableSaveOutputLevelPerBand.postValue(enabled)
                                mainViewModel.databaseOpr.writeConfig(
                                    "saveOutputLevelPerBand", if (enabled) "1" else "0", null,
                                )
                                // Seed the current band with the active level so it has a
                                // saved value straight away instead of only after the next
                                // slider move (#355).
                                if (enabled) {
                                    mainViewModel.savePerBandOutputLevel(txVolume)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Per-band visibility toggles. Lists every distinct band name from bands.txt;
 * a band that's switched off is hidden from both band pickers.
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

            // Big live readout so you can dial it in at a glance.
            Text(
                text = stringResource(R.string.settings_percent_format, current),
                color = Accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 48.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FT8AFIconButton(
                    onClick = {
                        val clamped = (current - 5).coerceIn(0, 100)
                        if (clamped != current) {
                            current = clamped
                            onChange(clamped)
                        }
                    },
                    size = 36.dp,
                ) {
                    FT8AFIcons.Minus(color = Accent, size = 16.dp)
                }
                IntSlider(
                    value = current,
                    onValueChange = { v ->
                        val clamped = v.coerceIn(0, 100)
                        if (clamped != current) {
                            current = clamped
                            onChange(clamped)
                        }
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                )
                FT8AFIconButton(
                    onClick = {
                        val clamped = (current + 5).coerceIn(0, 100)
                        if (clamped != current) {
                            current = clamped
                            onChange(clamped)
                        }
                    },
                    size = 36.dp,
                ) {
                    FT8AFIcons.Plus(color = Accent, size = 16.dp)
                }
            }

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
