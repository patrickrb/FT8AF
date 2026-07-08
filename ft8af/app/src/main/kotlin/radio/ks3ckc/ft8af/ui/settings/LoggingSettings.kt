package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.log.ThirdPartyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.CredentialFieldRole
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow
import radio.ks3ckc.ft8af.ui.components.autofill

/**
 * Logging & awards settings: SWL logging, PSKReporter, QRZ.com logging + profile
 * lookup credentials, and Cloudlog integration.
 */
@Composable
fun LoggingSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var saveSWLMessage by remember { mutableStateOf(GeneralVariables.saveSWLMessage) }
    var saveSWL_QSO by remember { mutableStateOf(GeneralVariables.saveSWL_QSO) }
    var enablePskReporter by remember { mutableStateOf(GeneralVariables.enablePskReporter) }
    var enableAdifExport by remember { mutableStateOf(GeneralVariables.enableAdifExport) }
    var enableQRZ by remember { mutableStateOf(GeneralVariables.enableQRZ) }
    var enableCloudlog by remember { mutableStateOf(GeneralVariables.enableCloudlog) }
    var qrzXmlUser by remember { mutableStateOf(GeneralVariables.qrzXmlUsername.orEmpty()) }
    var qrzXmlPass by remember { mutableStateOf(GeneralVariables.qrzXmlPassword.orEmpty()) }
    var qrzApiKey by remember { mutableStateOf(GeneralVariables.qrzApiKey.orEmpty()) }
    var cloudlogAddress by remember { mutableStateOf(GeneralVariables.cloudlogServerAddress.orEmpty()) }

    var showQrzLogbook by remember { mutableStateOf(false) }
    var showQrzCreds by remember { mutableStateOf(false) }
    var showCloudlog by remember { mutableStateOf(false) }

    // -- QRZ Logbook API Key Dialog (upload credential) --
    if (showQrzLogbook) {
        QrzLogbookDialog(
            initialApiKey = qrzApiKey,
            onDismiss = { showQrzLogbook = false },
            onSave = { key ->
                qrzApiKey = key
                GeneralVariables.qrzApiKey = key
                mainViewModel.databaseOpr.writeConfig("qrzApiKey", key, null)
                showQrzLogbook = false
            },
        )
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
                radio.ks3ckc.ft8af.qrz.QrzXmlClient.clearCache()
                radio.ks3ckc.ft8af.qrz.QrzWebClient.clearCache()
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

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_logging),
        onBack = onBack,
    ) {
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
                        label = stringResource(R.string.settings_adif_export),
                        description = stringResource(R.string.settings_adif_export_desc),
                        toggle = enableAdifExport,
                        onToggleChange = { checked ->
                            enableAdifExport = checked
                            GeneralVariables.enableAdifExport = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "enableAdifExport", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_qrz_com),
                        description = stringResource(R.string.settings_qrz_com_desc),
                        value = if (qrzApiKey.isNotEmpty()) {
                            stringResource(R.string.common_configured)
                        } else {
                            stringResource(R.string.common_not_configured)
                        },
                        toggle = enableQRZ,
                        onToggleChange = { checked ->
                            enableQRZ = checked
                            GeneralVariables.enableQRZ = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "enableQRZ", if (checked) "1" else "0", null,
                            )
                        },
                        showChevron = true,
                        onClick = { showQrzLogbook = true },
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

/**
 * Dialog for configuring the QRZ Logbook API key — the credential QRZ QSO uploads
 * require (distinct from the XML username/password used for avatar lookups). Includes
 * a Test Connection button that calls [ThirdPartyService.CheckQRZConnection].
 */
@Composable
private fun QrzLogbookDialog(
    initialApiKey: String,
    onDismiss: () -> Unit,
    onSave: (apiKey: String) -> Unit,
) {
    var apiKeyInput by remember { mutableStateOf(TextFieldValue(initialApiKey)) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Test Connection writes the typed key into GeneralVariables.qrzApiKey so the
    // STATUS call uses it. If the user dismisses without saving, that in-memory key
    // would otherwise persist and could drive real uploads even though it was never
    // committed. Restore the persisted value on every dismiss path (back/outside/Cancel).
    val handleDismiss = {
        GeneralVariables.qrzApiKey = initialApiKey
        onDismiss()
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

    Dialog(onDismissRequest = handleDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_qrz_logbook),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Text(
                text = stringResource(R.string.settings_qrz_logbook_desc),
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it; testResult = null },
                label = { Text(stringResource(R.string.settings_api_key)) },
                placeholder = { Text(stringResource(R.string.settings_qrz_api_key_hint), color = TextFaint) },
                singleLine = true,
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
                        // Write the typed key so the STATUS test uses the current input.
                        GeneralVariables.qrzApiKey = apiKeyInput.text.trim()
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ThirdPartyService.CheckQRZConnection()
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
                TextButton(onClick = handleDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = { onSave(apiKeyInput.text.trim()) },
                ) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
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
                // QRZ usernames are callsigns, so advertise Username (not EmailAddress).
                modifier = Modifier
                    .fillMaxWidth()
                    .autofill(CredentialFieldRole.USERNAME) {
                        userInput = TextFieldValue(it, selection = TextRange(it.length)); testResult = null
                    },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .autofill(CredentialFieldRole.PASSWORD) {
                        passInput = TextFieldValue(it, selection = TextRange(it.length)); testResult = null
                    },
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
                                radio.ks3ckc.ft8af.qrz.QrzXmlClient.testConnection()
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
