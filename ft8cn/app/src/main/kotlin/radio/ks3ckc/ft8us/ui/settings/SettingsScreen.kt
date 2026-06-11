package radio.ks3ckc.ft8us.ui.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bg7yoz.ft8cn.Ft8Message
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.ft8signal.FT8Package
import com.bg7yoz.ft8cn.location.GridLocationUpdater
import radio.ks3ckc.ft8us.theme.*
import radio.ks3ckc.ft8us.ui.components.GlassCard
import radio.ks3ckc.ft8us.ui.components.SettingsRow
import radio.ks3ckc.ft8us.ui.components.TopBar

/**
 * Top-level settings categories shown on the [SettingsLanding] page. Each opens
 * a focused detail screen via drill-down navigation.
 */
private enum class SettingsCategory {
    RADIO_AUDIO,
    TRANSMISSION,
    TIME_SYNC,
    DECODE_FILTERS,
    LOGGING,
    ADVANCED,
    ABOUT,
}

/**
 * Settings screen host. Shows a short category list (landing) and drills down
 * into a focused detail screen per category. Drill-down is driven by internal
 * state (no NavHost) since Settings is hosted as a plain tab swap in FT8USApp.
 *
 * [currentCategory] uses plain `remember` (not `rememberSaveable`), so switching
 * away to another tab and back resets to the category list — conventional
 * settings behavior.
 */
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    var currentCategory by remember { mutableStateOf<SettingsCategory?>(null) }

    // Hardware back pops a detail screen back to the landing. On the landing the
    // handler is disabled so the event propagates up (app exit) as before.
    BackHandler(enabled = currentCategory != null) { currentCategory = null }

    AnimatedContent(
        targetState = currentCategory,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState != null) {
                // Entering a detail: slide in from the right, fade out the landing.
                slideInHorizontally(initialOffsetX = { it }) + fadeIn() togetherWith fadeOut()
            } else {
                // Back to the landing: fade it in, slide the detail out to the right.
                fadeIn() togetherWith slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            }
        },
        label = "settingsDrill",
    ) { category ->
        when (category) {
            null -> SettingsLanding(
                mainViewModel = mainViewModel,
                onOpenCategory = { currentCategory = it },
            )
            SettingsCategory.RADIO_AUDIO ->
                RadioAudioSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.TRANSMISSION ->
                TransmissionSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.TIME_SYNC ->
                TimeSyncSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.DECODE_FILTERS ->
                DecodeFilterSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.LOGGING ->
                LoggingSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.ADVANCED ->
                AdvancedSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.ABOUT ->
                AboutSettings(mainViewModel, onBack = { currentCategory = null })
        }
    }
}

/**
 * Settings landing page: the operator identity card (pinned), the GPS
 * auto-grid toggle, and the list of categories to drill into.
 */
@Composable
private fun SettingsLanding(
    mainViewModel: MainViewModel,
    onOpenCategory: (SettingsCategory) -> Unit,
) {
    val context = LocalContext.current

    // Grid is observed so the operator card live-updates after a GPS grid change.
    val gridLive by GeneralVariables.mutableMyMaidenheadGrid.observeAsState(
        GeneralVariables.getMyMaidenheadGrid(),
    )

    var callsignState by remember { mutableStateOf(GeneralVariables.myCallsign.orEmpty()) }
    var antennaState by remember { mutableStateOf(GeneralVariables.myAntenna.orEmpty()) }
    var powerWattsState by remember { mutableIntStateOf(GeneralVariables.myPowerWatts) }
    var autoUpdateGridFromGPS by remember { mutableStateOf(GeneralVariables.autoUpdateGridFromGPS) }
    var showEditOperator by remember { mutableStateOf(false) }

    val callsign = callsignState
    val grid = gridLive.orEmpty()
    val rigConnected = mainViewModel.isRigConnected()
    val rigName = if (rigConnected) {
        mainViewModel.baseRig?.javaClass?.simpleName ?: "--"
    } else {
        stringResource(R.string.common_not_connected)
    }
    val antennaDisplay = antennaState.ifEmpty { "--" }
    val powerDisplay = if (powerWattsState > 0) "${powerWattsState}W" else "--"

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = stringResource(R.string.settings_title))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // =====================================================================
            // OPERATOR IDENTITY
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
            // CATEGORIES
            // =====================================================================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_cat_radio_audio),
                        showChevron = true,
                        onClick = { onOpenCategory(SettingsCategory.RADIO_AUDIO) },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_cat_transmission),
                        showChevron = true,
                        onClick = { onOpenCategory(SettingsCategory.TRANSMISSION) },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_cat_time_sync),
                        description = stringResource(R.string.settings_cat_time_sync_desc),
                        showChevron = true,
                        onClick = { onOpenCategory(SettingsCategory.TIME_SYNC) },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_cat_decode_filters),
                        showChevron = true,
                        onClick = { onOpenCategory(SettingsCategory.DECODE_FILTERS) },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_cat_logging),
                        showChevron = true,
                        onClick = { onOpenCategory(SettingsCategory.LOGGING) },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_cat_advanced),
                        showChevron = true,
                        onClick = { onOpenCategory(SettingsCategory.ADVANCED) },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_cat_about),
                        showChevron = true,
                        onClick = { onOpenCategory(SettingsCategory.ABOUT) },
                    )
                }
            }

            // Bottom spacer for scroll overscroll / nav bar inset
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

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
