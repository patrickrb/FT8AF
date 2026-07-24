package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.ft8transmit.MeterProtectionController
import com.k1af.ft8af.ft8transmit.TuneController
import com.k1af.ft8af.ft8transmit.TuneMethod
import radio.ks3ckc.ft8af.TUNE_LEVEL_INDEPENDENT_KEY
import radio.ks3ckc.ft8af.TUNE_LEVEL_KEY
import radio.ks3ckc.ft8af.TUNE_MAX_ON_SECONDS_KEY
import radio.ks3ckc.ft8af.TUNE_METHOD_KEY
import radio.ks3ckc.ft8af.saveTuneLevelForCurrentBand
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.FT8AFIconButton
import radio.ks3ckc.ft8af.ui.components.FT8AFIcons
import radio.ks3ckc.ft8af.ui.components.IntSlider
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow

// ALC target window bounds (0-255 normalized ALC scale). The low/high sliders
// keep a [ALC_GAP]-unit gap between the two values; clampAlcLow/clampAlcHigh use
// these both to bound each slider and to keep the coerceIn range non-empty when a
// restored/corrupted config persists an out-of-order pair.
// Upper bound of the "Max 73 Sends" picker (1..MAX; 0 = Auto, cap disabled).
private const val MAX_73_SENDS_MAX = 10

private const val ALC_LOW_MIN = 10
private const val ALC_LOW_MAX = 200
private const val ALC_HIGH_MAX = 250
private const val ALC_GAP = 10

/**
 * Clamp a desired ALC-target *low* value into [ALC_LOW_MIN]..[ALC_LOW_MAX]. The
 * [ALC_GAP]-unit gap below the current [high] is enforced only when [high] leaves
 * room for it (i.e. `high >= ALC_LOW_MIN + ALC_GAP`); otherwise the value is simply
 * clamped to [ALC_LOW_MIN]. This is because the upper bound is floored at
 * [ALC_LOW_MIN] so the range never inverts: a corrupted/restored config with `high`
 * below `ALC_LOW_MIN + ALC_GAP` would otherwise make `high - ALC_GAP` fall under
 * [ALC_LOW_MIN] and crash `coerceIn` with an empty range (in that degenerate case
 * the only non-crashing result is [ALC_LOW_MIN] itself). Byte-identical to the
 * previous inline `coerceIn(ALC_LOW_MIN, minOf(ALC_LOW_MAX, high - ALC_GAP))`
 * whenever that range is already valid (i.e. `high >= ALC_LOW_MIN + ALC_GAP`).
 */
internal fun clampAlcLow(desired: Int, high: Int): Int {
    val upper = minOf(ALC_LOW_MAX, high - ALC_GAP).coerceAtLeast(ALC_LOW_MIN)
    return desired.coerceIn(ALC_LOW_MIN, upper)
}

/**
 * Clamp a desired ALC-target *high* value into (`low + ALC_GAP`)..[ALC_HIGH_MAX].
 * The [ALC_GAP]-unit gap above the current [low] is enforced only when [low] leaves
 * room for it (i.e. `low <= ALC_HIGH_MAX - ALC_GAP`); otherwise the lower bound is
 * capped at [ALC_HIGH_MAX] and the value clamps to [ALC_HIGH_MAX]. This keeps the
 * range from inverting when a corrupted/restored config persists a `low` above
 * `ALC_HIGH_MAX - ALC_GAP` (in that degenerate case the only non-crashing result is
 * [ALC_HIGH_MAX] itself). Byte-identical to the previous inline
 * `coerceIn(low + ALC_GAP, ALC_HIGH_MAX)` whenever that range is already valid
 * (i.e. `low <= ALC_HIGH_MAX - ALC_GAP`).
 */
internal fun clampAlcHigh(desired: Int, low: Int): Int {
    val lower = (low + ALC_GAP).coerceAtMost(ALC_HIGH_MAX)
    return desired.coerceIn(lower, ALC_HIGH_MAX)
}

/**
 * Transmission settings: TX/RX split, watchdog, stop-after, TX protection
 * (auto-volume ALC + SWR halt), and auto-sequencing.
 */
@Composable
fun TransmissionSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var synFrequency by remember { mutableStateOf(GeneralVariables.synFrequency) }
    var holdTxFreq by remember { mutableStateOf(GeneralVariables.holdTxFreq) }
    var clearOnBandModeChange by remember { mutableStateOf(GeneralVariables.clearOnBandModeChange) }
    var watchdogMs by remember { mutableIntStateOf(GeneralVariables.launchSupervision) }
    var noReplyLimit by remember { mutableIntStateOf(GeneralVariables.noReplyLimit) }
    var max73Sends by remember { mutableIntStateOf(GeneralVariables.max73Sends) }

    // TX Protection state
    var autoVolumeEnabled by remember { mutableStateOf(GeneralVariables.autoVolumeEnabled) }
    var swrHaltEnabled by remember { mutableStateOf(GeneralVariables.swrHaltEnabled) }
    var swrHaltThreshold by remember { mutableIntStateOf(GeneralVariables.swrHaltThreshold) }
    var alcTargetLow by remember { mutableIntStateOf(GeneralVariables.alcTargetLow) }
    var alcTargetHigh by remember { mutableIntStateOf(GeneralVariables.alcTargetHigh) }

    // Tune state (issue #408)
    var tuneMaxOnSeconds by remember { mutableIntStateOf(GeneralVariables.tuneMaxOnSeconds) }
    var tuneLevelIndependent by remember { mutableStateOf(GeneralVariables.tuneLevelIndependent) }
    var tuneLevel by remember { mutableIntStateOf(GeneralVariables.tuneLevel) }
    var tuneMethod by remember { mutableIntStateOf(GeneralVariables.tuneMethod) }

    // Auto-sequence state
    var autoClearTxFreq by remember { mutableStateOf(GeneralVariables.autoClearTxFreq) }
    var autoFollowCQ by remember { mutableStateOf(GeneralVariables.autoFollowCQ) }
    var huntCallsCQ by remember { mutableStateOf(GeneralVariables.huntCallsCQ) }
    var autoCallFollow by remember { mutableStateOf(GeneralVariables.autoCallFollow) }
    var earlyDecode by remember { mutableStateOf(GeneralVariables.earlyDecode) }
    var autoCQAfterQSO by remember { mutableStateOf(GeneralVariables.autoCQAfterQSO) }

    var showWatchdog by remember { mutableStateOf(false) }
    var showStopAfter by remember { mutableStateOf(false) }
    var showMax73 by remember { mutableStateOf(false) }
    var showTuneMethod by remember { mutableStateOf(false) }

    // Index == TuneMethod.AUTOMATIC/INTERNAL/TONE
    val tuneMethodOptions = listOf(
        stringResource(R.string.tune_method_automatic),
        stringResource(R.string.tune_method_internal),
        stringResource(R.string.tune_method_tone),
    )

    // -- Tune Method Picker (issue #425) --
    if (showTuneMethod) {
        ListPickerDialog(
            title = stringResource(R.string.settings_tune_method),
            items = tuneMethodOptions,
            selectedIndex = TuneMethod.clamp(tuneMethod),
            onDismiss = { showTuneMethod = false },
            onSelect = { index ->
                showTuneMethod = false
                val method = TuneMethod.clamp(index)
                tuneMethod = method
                GeneralVariables.tuneMethod = method
                mainViewModel.databaseOpr.writeConfig(
                    TUNE_METHOD_KEY, method.toString(), null,
                )
            },
        )
    }

    val watchdogMinutes = watchdogMs / 60000
    val watchdogStr = if (watchdogMinutes == 0) stringResource(R.string.common_off)
        else stringResource(R.string.settings_minutes_format, watchdogMinutes)

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

    // -- Max 73 Sends Picker --
    if (showMax73) {
        val max73Options = mutableListOf(stringResource(R.string.settings_max_73_auto))
        for (i in 1..MAX_73_SENDS_MAX) {
            max73Options.add(i.toString())
        }
        ListPickerDialog(
            title = stringResource(R.string.settings_max_73),
            items = max73Options,
            selectedIndex = max73Sends.coerceIn(0, MAX_73_SENDS_MAX),
            onDismiss = { showMax73 = false },
            onSelect = { index ->
                showMax73 = false
                GeneralVariables.max73Sends = index
                max73Sends = index
                mainViewModel.databaseOpr.writeConfig(
                    "max73Sends", index.toString(), null,
                )
            },
        )
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_transmission),
        onBack = onBack,
    ) {
        // =====================================================================
        // TRANSMISSION
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
                        label = stringResource(R.string.settings_hold_tx_freq),
                        description = stringResource(R.string.settings_hold_tx_freq_desc),
                        toggle = holdTxFreq,
                        onToggleChange = { checked ->
                            holdTxFreq = checked
                            GeneralVariables.holdTxFreq = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "holdTxFreq", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_clear_on_change),
                        description = stringResource(R.string.settings_clear_on_change_desc),
                        toggle = clearOnBandModeChange,
                        onToggleChange = { checked ->
                            clearOnBandModeChange = checked
                            GeneralVariables.clearOnBandModeChange = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "clearOnBandModeChange", if (checked) "1" else "0", null,
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
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_max_73),
                        description = stringResource(R.string.settings_max_73_desc),
                        value = if (max73Sends == 0) stringResource(R.string.settings_max_73_auto)
                        else max73Sends.toString(),
                        showChevron = true,
                        onClick = { showMax73 = true },
                    )
                }
            }
        }

        // =====================================================================
        // TX PROTECTION
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
                            FT8AFIconButton(
                                onClick = {
                                    val clamped = clampAlcLow(alcTargetLow - 5, alcTargetHigh)
                                    alcTargetLow = clamped
                                    GeneralVariables.alcTargetLow = clamped
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetLow", clamped.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                FT8AFIcons.Minus(color = Accent, size = 16.dp)
                            }
                            IntSlider(
                                value = alcTargetLow,
                                onValueChange = { v ->
                                    val clamped = clampAlcLow(v, alcTargetHigh)
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
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            )
                            FT8AFIconButton(
                                onClick = {
                                    val clamped = clampAlcLow(alcTargetLow + 5, alcTargetHigh)
                                    alcTargetLow = clamped
                                    GeneralVariables.alcTargetLow = clamped
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetLow", clamped.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                FT8AFIcons.Plus(color = Accent, size = 16.dp)
                            }
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
                            FT8AFIconButton(
                                onClick = {
                                    val clamped = clampAlcHigh(alcTargetHigh - 5, alcTargetLow)
                                    alcTargetHigh = clamped
                                    GeneralVariables.alcTargetHigh = clamped
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetHigh", clamped.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                FT8AFIcons.Minus(color = Accent, size = 16.dp)
                            }
                            IntSlider(
                                value = alcTargetHigh,
                                onValueChange = { v ->
                                    val clamped = clampAlcHigh(v, alcTargetLow)
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
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            )
                            FT8AFIconButton(
                                onClick = {
                                    val clamped = clampAlcHigh(alcTargetHigh + 5, alcTargetLow)
                                    alcTargetHigh = clamped
                                    GeneralVariables.alcTargetHigh = clamped
                                    mainViewModel.databaseOpr.writeConfig(
                                        "alcTargetHigh", clamped.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                FT8AFIcons.Plus(color = Accent, size = 16.dp)
                            }
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
                            FT8AFIconButton(
                                onClick = {
                                    val newVal = (swrHaltThreshold - 5).coerceIn(30, 200)
                                    swrHaltThreshold = newVal
                                    GeneralVariables.swrHaltThreshold = newVal
                                    mainViewModel.databaseOpr.writeConfig(
                                        "swrHaltThreshold", newVal.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                FT8AFIcons.Minus(color = Accent, size = 16.dp)
                            }
                            IntSlider(
                                value = swrHaltThreshold,
                                onValueChange = { v ->
                                    swrHaltThreshold = v
                                    GeneralVariables.swrHaltThreshold = v
                                },
                                onValueChangeFinished = {
                                    mainViewModel.databaseOpr.writeConfig(
                                        "swrHaltThreshold", swrHaltThreshold.toString(), null,
                                    )
                                },
                                valueRange = 30f..200f, // ~1.3:1 to ~7.0:1
                                modifier = Modifier.weight(1f),
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            )
                            FT8AFIconButton(
                                onClick = {
                                    val newVal = (swrHaltThreshold + 5).coerceIn(30, 200)
                                    swrHaltThreshold = newVal
                                    GeneralVariables.swrHaltThreshold = newVal
                                    mainViewModel.databaseOpr.writeConfig(
                                        "swrHaltThreshold", newVal.toString(), null,
                                    )
                                },
                                size = 36.dp,
                            ) {
                                FT8AFIcons.Plus(color = Accent, size = 16.dp)
                            }
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
        // TUNE (issue #408)
        // =====================================================================
        SettingsSection(title = "TUNE") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_tune_method),
                        description = stringResource(R.string.settings_tune_method_desc),
                        value = tuneMethodOptions[TuneMethod.clamp(tuneMethod)],
                        showChevron = true,
                        onClick = { showTuneMethod = true },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = "Tune timeout",
                        description = "Hard cap on the tune carrier — it always stops by itself",
                        value = "${tuneMaxOnSeconds}s",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IntSlider(
                            value = tuneMaxOnSeconds,
                            onValueChange = { v ->
                                val clamped = TuneController.clampMaxOnSeconds(v)
                                tuneMaxOnSeconds = clamped
                                GeneralVariables.tuneMaxOnSeconds = clamped
                            },
                            onValueChangeFinished = {
                                mainViewModel.databaseOpr.writeConfig(
                                    TUNE_MAX_ON_SECONDS_KEY, tuneMaxOnSeconds.toString(), null,
                                )
                            },
                            valueRange = TuneController.MIN_MAX_ON_SECONDS.toFloat()..
                                TuneController.MAX_MAX_ON_SECONDS.toFloat(),
                            modifier = Modifier.weight(1f),
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                        )
                    }
                    SectionDivider()
                    SettingsRow(
                        label = "Independent tune level",
                        description = "Tune at its own drive level (e.g. reduced power) without touching the TX drive",
                        toggle = tuneLevelIndependent,
                        onToggleChange = { checked ->
                            tuneLevelIndependent = checked
                            GeneralVariables.tuneLevelIndependent = checked
                            mainViewModel.databaseOpr.writeConfig(
                                TUNE_LEVEL_INDEPENDENT_KEY, if (checked) "1" else "0", null,
                            )
                        },
                    )
                    if (tuneLevelIndependent) {
                        SectionDivider()
                        SettingsRow(
                            label = "Tune audio level",
                            description = if (GeneralVariables.savePerBandOutputLevel)
                                "Remembered per band (Save output level per band is on)"
                            else "Single level for all bands",
                            value = "$tuneLevel%",
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IntSlider(
                                value = tuneLevel,
                                onValueChange = { v ->
                                    val clamped = v.coerceIn(0, 100)
                                    tuneLevel = clamped
                                    GeneralVariables.tuneLevel = clamped
                                },
                                onValueChangeFinished = {
                                    mainViewModel.databaseOpr.writeConfig(
                                        TUNE_LEVEL_KEY, tuneLevel.toString(), null,
                                    )
                                    // When per-band levels are on, the independent tune
                                    // level is remembered for the current band too — in
                                    // its own map, never the FT8 one (issue #408).
                                    saveTuneLevelForCurrentBand(mainViewModel.databaseOpr, tuneLevel)
                                },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f),
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // AUTO-SEQUENCE
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_auto_sequence)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_auto_clear_tx),
                        description = stringResource(R.string.settings_auto_clear_tx_desc),
                        toggle = autoClearTxFreq,
                        onToggleChange = { checked ->
                            autoClearTxFreq = checked
                            GeneralVariables.autoClearTxFreq = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "autoClearTxFreq", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
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
                        label = stringResource(R.string.settings_hunt_cq),
                        description = stringResource(R.string.settings_hunt_cq_desc),
                        toggle = huntCallsCQ,
                        onToggleChange = { checked ->
                            huntCallsCQ = checked
                            GeneralVariables.huntCallsCQ = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "huntCallsCQ", if (checked) "1" else "0", null,
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
    }
}
