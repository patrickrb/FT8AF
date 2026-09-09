package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow
import radio.ks3ckc.ft8af.ui.decode.WorkedStationScope

/**
 * Decode-list settings: highlight rules, callsign blocklist, display filters,
 * and needed-DX alerts.
 */
@Composable
fun DecodeFilterSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    // Decode-list highlight toggles
    var highlightNewDxcc by remember { mutableStateOf(GeneralVariables.highlightNewDxcc) }
    var highlightNewZone by remember { mutableStateOf(GeneralVariables.highlightNewZone) }
    var highlightNewState by remember { mutableStateOf(GeneralVariables.highlightNewState) }
    var highlightNewGrid by remember { mutableStateOf(GeneralVariables.highlightNewGrid) }
    var highlightNewPrefix by remember { mutableStateOf(GeneralVariables.highlightNewPrefix) }
    var highlightNewBand by remember { mutableStateOf(GeneralVariables.highlightNewBand) }
    var highlightWorked by remember { mutableStateOf(GeneralVariables.highlightWorked) }
    var workedStationMode by remember { mutableStateOf(GeneralVariables.workedStationMode) }
    var workedStationScope by remember { mutableStateOf(GeneralVariables.workedStationScope) }
    var workedSameMode by remember { mutableStateOf(GeneralVariables.workedSameMode) }
    var workedStationList by remember { mutableStateOf(GeneralVariables.getWorkedStationList()) }
    var highlightPota by remember { mutableStateOf(GeneralVariables.highlightPota) }
    var distanceInMiles by remember { mutableStateOf(GeneralVariables.distanceInMiles) }
    var showBeamHeading by remember { mutableStateOf(GeneralVariables.showBeamHeading) }

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
    var alertOnCqReply by remember { mutableStateOf(GeneralVariables.alertOnCqReply) }
    var alertOnQsoComplete by remember { mutableStateOf(GeneralVariables.alertOnQsoComplete) }
    var watchCallsigns by remember { mutableStateOf(GeneralVariables.getWatchCallsigns()) }

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

    // Worked-station handling labels, indexed by the persisted mode/scope ordinals.
    val workedModeLabels = listOf(
        stringResource(R.string.settings_worked_mode_highlight),
        stringResource(R.string.settings_worked_mode_ignore),
        stringResource(R.string.settings_worked_mode_hide),
    )
    val workedScopeLabels = listOf(
        stringResource(R.string.settings_worked_scope_on_band),
        stringResource(R.string.settings_worked_scope_before),
        stringResource(R.string.settings_worked_scope_today),
        stringResource(R.string.settings_worked_scope_from_list),
    )
    // FROM_LIST scope ordinal — the list editor row only appears for this scope.
    // Derived from the enum so it can't drift if the scope entries are reordered.
    val workedScopeFromList = WorkedStationScope.FROM_LIST.ordinal

    var showBlockExactDialog by remember { mutableStateOf(false) }
    var showBlockPrefixDialog by remember { mutableStateOf(false) }
    var showBlockKeywordDialog by remember { mutableStateOf(false) }
    var showContinentPicker by remember { mutableStateOf(false) }
    var showWorkedModePicker by remember { mutableStateOf(false) }
    var showWorkedScopePicker by remember { mutableStateOf(false) }
    var showWorkedListDialog by remember { mutableStateOf(false) }
    var showWatchDialog by remember { mutableStateOf(false) }

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

    // -- Worked stations: behavior (highlight / ignore / hide) picker --
    if (showWorkedModePicker) {
        ListPickerDialog(
            title = stringResource(R.string.settings_worked_mode),
            items = workedModeLabels,
            selectedIndex = workedStationMode.coerceIn(0, workedModeLabels.size - 1),
            onDismiss = { showWorkedModePicker = false },
            onSelect = { index ->
                showWorkedModePicker = false
                workedStationMode = index
                GeneralVariables.workedStationMode = index
                mainViewModel.databaseOpr.writeConfig("workedStationMode", index.toString(), null)
            },
        )
    }

    // -- Worked stations: scope (on band / before / today / from list) picker --
    if (showWorkedScopePicker) {
        ListPickerDialog(
            title = stringResource(R.string.settings_worked_scope),
            items = workedScopeLabels,
            selectedIndex = workedStationScope.coerceIn(0, workedScopeLabels.size - 1),
            onDismiss = { showWorkedScopePicker = false },
            onSelect = { index ->
                showWorkedScopePicker = false
                workedStationScope = index
                GeneralVariables.workedStationScope = index
                mainViewModel.databaseOpr.writeConfig("workedStationScope", index.toString(), null)
            },
        )
    }

    // -- Worked stations: user-maintained "from list" callsigns --
    if (showWorkedListDialog) {
        TextListDialog(
            title = stringResource(R.string.settings_worked_list_title),
            description = stringResource(R.string.settings_worked_list_desc),
            initialValue = workedStationList,
            onDismiss = { showWorkedListDialog = false },
            onSave = { text ->
                GeneralVariables.addWorkedStationList(text)
                workedStationList = GeneralVariables.getWorkedStationList()
                mainViewModel.databaseOpr.writeConfig("workedStationList", workedStationList, null)
                showWorkedListDialog = false
            },
        )
    }

    // -- Needed-DX alerts: callsign watchlist editor --
    if (showWatchDialog) {
        TextListDialog(
            title = stringResource(R.string.settings_watch_dialog_title),
            description = stringResource(R.string.settings_watch_dialog_desc),
            initialValue = watchCallsigns,
            onDismiss = { showWatchDialog = false },
            onSave = { text ->
                GeneralVariables.addWatchCallsigns(text)
                watchCallsigns = GeneralVariables.getWatchCallsigns()
                mainViewModel.databaseOpr.writeConfig("watchCallsigns", watchCallsigns, null)
                showWatchDialog = false
            },
        )
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_decode_filters),
        onBack = onBack,
    ) {
        // =====================================================================
        // DECODE HIGHLIGHTS
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
                        label = stringResource(R.string.settings_highlight_new_zone),
                        description = stringResource(R.string.settings_highlight_new_zone_desc),
                        toggle = highlightNewZone,
                        onToggleChange = { checked ->
                            highlightNewZone = checked
                            GeneralVariables.highlightNewZone = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "highlightNewZone", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_highlight_new_state),
                        description = stringResource(R.string.settings_highlight_new_state_desc),
                        toggle = highlightNewState,
                        onToggleChange = { checked ->
                            highlightNewState = checked
                            GeneralVariables.highlightNewState = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "highlightNewState", if (checked) "1" else "0", null,
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
                        label = stringResource(R.string.settings_highlight_new_prefix),
                        description = stringResource(R.string.settings_highlight_new_prefix_desc),
                        toggle = highlightNewPrefix,
                        onToggleChange = { checked ->
                            highlightNewPrefix = checked
                            GeneralVariables.highlightNewPrefix = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "highlightNewPrefix", if (checked) "1" else "0", null,
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
                    if (highlightWorked) {
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_worked_mode),
                            description = stringResource(R.string.settings_worked_mode_desc),
                            value = workedModeLabels.getOrElse(workedStationMode) {
                                workedModeLabels.first()
                            },
                            showChevron = true,
                            onClick = { showWorkedModePicker = true },
                        )
                        SectionDivider()
                        SettingsRow(
                            label = stringResource(R.string.settings_worked_scope),
                            description = stringResource(R.string.settings_worked_scope_desc),
                            value = workedScopeLabels.getOrElse(workedStationScope) {
                                workedScopeLabels.first()
                            },
                            showChevron = true,
                            onClick = { showWorkedScopePicker = true },
                        )
                        // "and mode" refinement — meaningless for the user list, so
                        // only offer it for the band/before/today scopes.
                        if (workedStationScope != workedScopeFromList) {
                            SectionDivider()
                            SettingsRow(
                                label = stringResource(R.string.settings_worked_same_mode),
                                description = stringResource(R.string.settings_worked_same_mode_desc),
                                toggle = workedSameMode,
                                onToggleChange = { checked ->
                                    workedSameMode = checked
                                    GeneralVariables.workedSameMode = checked
                                    mainViewModel.databaseOpr.writeConfig(
                                        "workedSameMode", if (checked) "1" else "0", null,
                                    )
                                    // Reload the worked lists so the new filter applies now.
                                    mainViewModel.databaseOpr.getAllQSLCallsigns()
                                },
                            )
                        }
                        if (workedStationScope == workedScopeFromList) {
                            SectionDivider()
                            SettingsRow(
                                label = stringResource(R.string.settings_worked_list_title),
                                description = stringResource(R.string.settings_worked_list_desc),
                                value = workedStationList.ifBlank {
                                    stringResource(R.string.common_none)
                                },
                                showChevron = true,
                                onClick = { showWorkedListDialog = true },
                            )
                        }
                    }
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
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_show_beam_heading),
                        description = stringResource(R.string.settings_show_beam_heading_desc),
                        toggle = showBeamHeading,
                        onToggleChange = { checked ->
                            showBeamHeading = checked
                            GeneralVariables.showBeamHeading = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "showBeamHeading", if (checked) "1" else "0", null,
                            )
                        },
                    )
                }
            }
        }

        // =====================================================================
        // CALLSIGN BLOCKLIST
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
        // DECODE FILTERS
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
        // NEEDED-DX ALERTS
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_needed_dx_alerts)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_alert_watchlist),
                        description = stringResource(R.string.settings_alert_watchlist_desc),
                        value = watchCallsigns.ifBlank { stringResource(R.string.common_none) },
                        showChevron = true,
                        onClick = { showWatchDialog = true },
                    )
                    SectionDivider()
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
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_alert_cq_reply),
                        description = stringResource(R.string.settings_alert_cq_reply_desc),
                        toggle = alertOnCqReply,
                        onToggleChange = { checked ->
                            alertOnCqReply = checked
                            GeneralVariables.alertOnCqReply = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "alertOnCqReply", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_alert_qso_complete),
                        description = stringResource(R.string.settings_alert_qso_complete_desc),
                        toggle = alertOnQsoComplete,
                        onToggleChange = { checked ->
                            alertOnQsoComplete = checked
                            GeneralVariables.alertOnQsoComplete = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "alertOnQsoComplete", if (checked) "1" else "0", null,
                            )
                        },
                    )
                }
            }
        }
    }
}
