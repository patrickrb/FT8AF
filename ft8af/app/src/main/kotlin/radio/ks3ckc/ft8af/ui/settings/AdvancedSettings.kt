package radio.ks3ckc.ft8af.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.os.LocaleListCompat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OnAfterQueryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.ScreenWake
import radio.ks3ckc.ft8af.crash.CrashReporting
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.theme.ThemeOption
import radio.ks3ckc.ft8af.theme.applyTheme
import radio.ks3ckc.ft8af.theme.currentThemeNameRes
import radio.ks3ckc.ft8af.theme.loadTheme
import radio.ks3ckc.ft8af.theme.saveTheme
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One selectable app language: its BCP-47 tag (as handed to
 * [AppCompatDelegate.setApplicationLocales]) and the display-name resource shown
 * in its own script.
 */
internal data class AppLanguage(val tag: String, @StringRes val nameRes: Int)

/**
 * Single source of truth for the in-app Language picker, in display order. Adding
 * a language is one entry here — the tag list, picker labels, and current-language
 * label all derive from it. Keep in sync with res/xml/locales_config.xml and the
 * values-<locale>/ translation dirs (English lives in the default values/ dir, so
 * "en" has no dedicated folder; Indonesian "id" compiles to the legacy values-in).
 */
internal val SUPPORTED_LANGUAGES: List<AppLanguage> = listOf(
    AppLanguage("en", R.string.language_name_en),
    AppLanguage("zh-CN", R.string.language_name_zh_cn),
    AppLanguage("zh-TW", R.string.language_name_zh_tw),
    AppLanguage("ru", R.string.language_name_ru),
    AppLanguage("es", R.string.language_name_es),
    AppLanguage("fr", R.string.language_name_fr),
    AppLanguage("ja", R.string.language_name_ja),
    AppLanguage("it", R.string.language_name_it),
    AppLanguage("pl", R.string.language_name_pl),
    AppLanguage("ko", R.string.language_name_ko),
    AppLanguage("nl", R.string.language_name_nl),
    AppLanguage("cs", R.string.language_name_cs),
    AppLanguage("tr", R.string.language_name_tr),
    AppLanguage("id", R.string.language_name_id),
    AppLanguage("uk", R.string.language_name_uk),
    AppLanguage("ar", R.string.language_name_ar),
)

/**
 * BCP-47 language tags for the picker, parallel to the label list built in the
 * dialog. Index 0 ("") means "System default" (empty locale list).
 */
internal val LANGUAGE_TAGS: List<String> = listOf("") + SUPPORTED_LANGUAGES.map { it.tag }

/**
 * The display-name resource for the currently-applied app locale tags (as returned
 * by `getApplicationLocales().toLanguageTags()`), or the "system default" label
 * when nothing matches. The first supported language whose tag prefixes the current
 * tags wins (e.g. "en-US" → English). Pure logic, unit-tested.
 */
@StringRes
internal fun currentLanguageNameRes(currentTags: String): Int =
    SUPPORTED_LANGUAGES.firstOrNull { currentTags.startsWith(it.tag) }?.nameRes
        ?: R.string.settings_language_system

/**
 * FFT display developer knobs (issue #428): label resources indexed by the
 * wire value stored in GeneralVariables / the config DB. Out-of-range values
 * fall back to each knob's default, mirroring the clamping setters. Pure
 * logic, unit-tested.
 */
internal val FFT_WINDOW_NAME_RES: List<Int> = listOf(
    R.string.settings_fft_window_rect,           // 0
    R.string.settings_fft_window_hann,           // 1 (default)
    R.string.settings_fft_window_hamming,        // 2
    R.string.settings_fft_window_blackman,       // 3
    R.string.settings_fft_window_blackman_harris, // 4
)

@StringRes
internal fun fftWindowLabelRes(mode: Int): Int =
    FFT_WINDOW_NAME_RES.getOrElse(mode) { FFT_WINDOW_NAME_RES[1] }

internal val FFT_AVERAGING_NAME_RES: List<Int> = listOf(
    R.string.settings_fft_avg_off,   // 0 (default)
    R.string.settings_fft_avg_light, // 1: EMA a=0.5
    R.string.settings_fft_avg_heavy, // 2: EMA a=0.25
)

@StringRes
internal fun fftAveragingLabelRes(mode: Int): Int =
    FFT_AVERAGING_NAME_RES.getOrElse(mode) { FFT_AVERAGING_NAME_RES[0] }

internal val BIN_AGGREGATION_NAME_RES: List<Int> = listOf(
    R.string.settings_bin_agg_max, // 0 (default, legacy behavior)
    R.string.settings_bin_agg_avg, // 1
    R.string.settings_bin_agg_rms, // 2
)

@StringRes
internal fun binAggregationLabelRes(mode: Int): Int =
    BIN_AGGREGATION_NAME_RES.getOrElse(mode) { BIN_AGGREGATION_NAME_RES[0] }

/**
 * Advanced settings: PTT/TX timing delays, late-start tolerance, and the
 * in-app language picker.
 */
@Composable
fun AdvancedSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var pttDelay by remember { mutableIntStateOf(GeneralVariables.pttDelay) }
    var txDelay by remember { mutableIntStateOf(GeneralVariables.transmitDelay) }
    var lateStartMs by remember { mutableIntStateOf(GeneralVariables.lateStartTolerance) }
    var currentTheme by remember { mutableStateOf(loadTheme(context)) }

    // Power & heat knobs. Seeded from GeneralVariables, which config hydration
    // has already populated by the time settings can be opened.
    var keepScreenOn by remember { mutableStateOf(GeneralVariables.keepScreenOn) }
    var deepDecode by remember { mutableStateOf(GeneralVariables.deepDecodeMode) }

    // Opt-in crash reporting (Sentry). Only surfaced when a DSN was compiled in.
    var crashReportingEnabled by remember { mutableStateOf(CrashReporting.isOptedIn(context)) }

    var showPttDelay by remember { mutableStateOf(false) }
    var showTxDelay by remember { mutableStateOf(false) }
    var showLateStart by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }

    // FFT display developer knobs (issue #428)
    var fftWindow by remember { mutableIntStateOf(GeneralVariables.getFftWindowType()) }
    var fftAveraging by remember { mutableIntStateOf(GeneralVariables.getFftAveragingMode()) }
    var binAggregation by remember { mutableIntStateOf(GeneralVariables.getSpectrumBinAggregation()) }
    var showFftWindowPicker by remember { mutableStateOf(false) }
    var showFftAveragingPicker by remember { mutableStateOf(false) }
    var showBinAggregationPicker by remember { mutableStateOf(false) }

    // -- Backup & restore (issue #357) --
    val scope = rememberCoroutineScope()
    val databaseOpr = mainViewModel.databaseOpr
    var showExportDialog by remember { mutableStateOf(false) }
    // Captured at launch time so the SAF result callback knows whether to include
    // sensitive keys in the file it writes.
    var exportIncludeSensitive by remember { mutableStateOf(false) }
    // Non-null while the import confirmation dialog is showing the parsed backup.
    var pendingImport by remember { mutableStateOf<SettingsBackup.ParsedBackup?>(null) }

    // Storage Access Framework: user picks where to write the export. On result we
    // read the whole config table off the main thread, serialize it, and stream it
    // to the chosen document.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val config = databaseOpr.getAllConfigSync()
                    val json = SettingsBackup.buildBackupJson(
                        config = config,
                        includeSensitive = exportIncludeSensitive,
                        appVersion = GeneralVariables.VERSION,
                        createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                    )
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the selected file for writing")
                }
            }
            result.fold(
                onSuccess = {
                    Toast.makeText(context, R.string.settings_export_success, Toast.LENGTH_LONG).show()
                },
                onFailure = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_export_failed, it.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    // SAF: user picks a backup file. On result we read + validate it off the main
    // thread; a valid parse opens the confirmation dialog, a bad file just toasts.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("Could not open the selected file")
                    SettingsBackup.parseBackupJson(text)
                }
            }
            result.fold(
                onSuccess = { pendingImport = it },
                onFailure = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_import_failed, it.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    // -- Export options dialog (choose whether to include secrets) --
    if (showExportDialog) {
        ExportSettingsDialog(
            includeSensitive = exportIncludeSensitive,
            onToggleSensitive = { exportIncludeSensitive = it },
            onDismiss = { showExportDialog = false },
            onExport = {
                showExportDialog = false
                val fileName = SettingsBackup.defaultFileName(
                    date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                    appVersion = GeneralVariables.VERSION,
                )
                exportLauncher.launch(fileName)
            },
        )
    }

    // -- Import confirmation dialog (preview count, confirm overwrite) --
    pendingImport?.let { backup ->
        ConfirmImportDialog(
            keyCount = backup.config.size,
            createdAt = backup.createdAt.ifBlank { "?" },
            onDismiss = { pendingImport = null },
            onConfirm = {
                pendingImport = null
                val toApply = backup.config
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { databaseOpr.writeConfigSync(toApply) }
                    }
                    result.fold(
                        onSuccess = {
                            // Re-hydrate GeneralVariables from the freshly written config so
                            // most settings take effect without waiting for an app restart.
                            databaseOpr.getAllConfigParameter(object : OnAfterQueryConfig {
                                override fun doOnBeforeQueryConfig(keyName: String?) {}
                                override fun doOnAfterQueryConfig(keyName: String?, value: String?) {}
                            })
                            Toast.makeText(
                                context, R.string.settings_import_success, Toast.LENGTH_LONG,
                            ).show()
                        },
                        onFailure = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_import_failed, it.message ?: ""),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            },
        )
    }

    val txDelayStr = stringResource(R.string.settings_milliseconds_format, txDelay)
    val pttDelayStr = stringResource(R.string.settings_milliseconds_format, pttDelay)

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

    // -- FFT Window Picker (issue #428) --
    if (showFftWindowPicker) {
        val labels = ArrayList<String>(FFT_WINDOW_NAME_RES.size)
        for (res in FFT_WINDOW_NAME_RES) {
            labels.add(stringResource(res))
        }
        ListPickerDialog(
            title = stringResource(R.string.settings_fft_window),
            items = labels,
            selectedIndex = fftWindow.coerceIn(0, FFT_WINDOW_NAME_RES.size - 1),
            onDismiss = { showFftWindowPicker = false },
            onSelect = { index ->
                showFftWindowPicker = false
                GeneralVariables.setFftWindowType(index)
                fftWindow = GeneralVariables.getFftWindowType()
                mainViewModel.databaseOpr.writeConfig("fftWindowType", index.toString(), null)
            },
        )
    }

    // -- FFT Frame Averaging Picker (issue #428) --
    if (showFftAveragingPicker) {
        val labels = ArrayList<String>(FFT_AVERAGING_NAME_RES.size)
        for (res in FFT_AVERAGING_NAME_RES) {
            labels.add(stringResource(res))
        }
        ListPickerDialog(
            title = stringResource(R.string.settings_fft_averaging),
            items = labels,
            selectedIndex = fftAveraging.coerceIn(0, FFT_AVERAGING_NAME_RES.size - 1),
            onDismiss = { showFftAveragingPicker = false },
            onSelect = { index ->
                showFftAveragingPicker = false
                GeneralVariables.setFftAveragingMode(index)
                fftAveraging = GeneralVariables.getFftAveragingMode()
                mainViewModel.databaseOpr.writeConfig("fftAveragingMode", index.toString(), null)
            },
        )
    }

    // -- Spectrum Bin Aggregation Picker (issue #428) --
    if (showBinAggregationPicker) {
        val labels = ArrayList<String>(BIN_AGGREGATION_NAME_RES.size)
        for (res in BIN_AGGREGATION_NAME_RES) {
            labels.add(stringResource(res))
        }
        ListPickerDialog(
            title = stringResource(R.string.settings_bin_agg),
            items = labels,
            selectedIndex = binAggregation.coerceIn(0, BIN_AGGREGATION_NAME_RES.size - 1),
            onDismiss = { showBinAggregationPicker = false },
            onSelect = { index ->
                showBinAggregationPicker = false
                GeneralVariables.setSpectrumBinAggregation(index)
                binAggregation = GeneralVariables.getSpectrumBinAggregation()
                mainViewModel.databaseOpr.writeConfig("spectrumBinAggregation", index.toString(), null)
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
        // Built with a for-loop (not map/forEach) so the @Composable stringResource
        // calls run in a permitted context; mirrors LANGUAGE_TAGS index-for-index.
        val languageLabels = ArrayList<String>(LANGUAGE_TAGS.size)
        languageLabels.add(stringResource(R.string.settings_language_system))
        for (lang in SUPPORTED_LANGUAGES) {
            languageLabels.add(stringResource(lang.nameRes))
        }
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

    // -- Theme Picker --
    // Selecting a theme applies it live (swaps the Compose palette + night mode,
    // no activity recreate) and persists the choice. Built like the language
    // picker so adding a future theme is one ThemeOption entry.
    if (showThemePicker) {
        val themes = ThemeOption.entries
        val themeLabels = ArrayList<String>(themes.size)
        for (theme in themes) {
            themeLabels.add(stringResource(theme.nameRes))
        }
        ListPickerDialog(
            title = stringResource(R.string.settings_theme),
            items = themeLabels,
            selectedIndex = themes.indexOf(currentTheme).coerceAtLeast(0),
            onDismiss = { showThemePicker = false },
            onSelect = { index ->
                showThemePicker = false
                val theme = themes[index]
                currentTheme = theme
                applyTheme(theme)
                saveTheme(context, theme)
            },
        )
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_advanced),
        onBack = onBack,
    ) {
        // =====================================================================
        // ADVANCED
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
        // FFT / WATERFALL (developer knobs, issue #428)
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_fft_waterfall)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_fft_window),
                        description = stringResource(R.string.settings_fft_window_desc),
                        value = stringResource(fftWindowLabelRes(fftWindow)),
                        showChevron = true,
                        onClick = { showFftWindowPicker = true },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_fft_averaging),
                        description = stringResource(R.string.settings_fft_averaging_desc),
                        value = stringResource(fftAveragingLabelRes(fftAveraging)),
                        showChevron = true,
                        onClick = { showFftAveragingPicker = true },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_bin_agg),
                        description = stringResource(R.string.settings_bin_agg_desc),
                        value = stringResource(binAggregationLabelRes(binAggregation)),
                        showChevron = true,
                        onClick = { showBinAggregationPicker = true },
                    )
                }
            }
        }

        // =====================================================================
        // POWER & HEAT
        // =====================================================================
        // Both knobs exist because a long portable session cooks the phone until
        // it browns out its own OTG accessory rail and drops the CAT link (field
        // log 2026-07-23: 48.6C battery, twelve USB re-enumerations). The screen
        // flag used to be hard-coded on; deep decode was only reachable from the
        // retired legacy settings fragment, so neither was adjustable in the
        // shipping UI.
        SettingsSection(title = stringResource(R.string.settings_section_power)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_keep_screen_on),
                        description = stringResource(R.string.settings_keep_screen_on_desc),
                        toggle = keepScreenOn,
                        onToggleChange = { on ->
                            keepScreenOn = on
                            GeneralVariables.keepScreenOn = on
                            mainViewModel.databaseOpr.writeConfig(
                                "keepScreenOn", if (on) "1" else "0", null,
                            )
                            // Apply live rather than waiting for the next onResume,
                            // so the effect is visible from the settings screen.
                            (context as? android.app.Activity)?.let {
                                ScreenWake.apply(it.window, on)
                            }
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_deep_decode),
                        description = stringResource(R.string.settings_deep_decode_desc),
                        toggle = deepDecode,
                        onToggleChange = { on ->
                            deepDecode = on
                            GeneralVariables.deepDecodeMode = on
                            // Same "deepMode" key the retired legacy fragment wrote,
                            // so an existing preference carries over unchanged.
                            mainViewModel.databaseOpr.writeConfig(
                                "deepMode", if (on) "1" else "0", null,
                            )
                        },
                    )
                }
            }
        }

        // =====================================================================
        // APPEARANCE
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    label = stringResource(R.string.settings_theme),
                    description = stringResource(R.string.settings_theme_desc),
                    value = stringResource(currentThemeNameRes(currentTheme)),
                    showChevron = true,
                    onClick = { showThemePicker = true },
                )
            }
        }

        // =====================================================================
        // LANGUAGE
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_language)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                val currentLangRes = currentLanguageNameRes(currentTags)
                SettingsRow(
                    label = stringResource(R.string.settings_language),
                    description = stringResource(R.string.settings_language_desc),
                    value = stringResource(currentLangRes),
                    showChevron = true,
                    onClick = { showLanguagePicker = true },
                )
            }
        }

        // =====================================================================
        // BACKUP & RESTORE (issue #357)
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_backup)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_export),
                        description = stringResource(R.string.settings_export_desc),
                        showChevron = true,
                        onClick = { showExportDialog = true },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_import),
                        description = stringResource(R.string.settings_import_desc),
                        showChevron = true,
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    )
                }
            }
        }

        // =====================================================================
        // DIAGNOSTICS (opt-in crash reporting)
        // =====================================================================
        // Only shown when a Sentry DSN was compiled in — a contributor build with
        // no DSN has nowhere to report, so the toggle would be dead weight.
        if (CrashReporting.isAvailable()) {
            SettingsSection(title = stringResource(R.string.settings_section_diagnostics)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsRow(
                        label = stringResource(R.string.settings_crash_reporting),
                        description = stringResource(R.string.settings_crash_reporting_desc),
                        toggle = crashReportingEnabled,
                        onToggleChange = { enabled ->
                            crashReportingEnabled = enabled
                            CrashReporting.setEnabled(context, enabled)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Export options dialog: a single toggle for whether to include sensitive
 * credentials in the backup, then Export/Cancel. Styled like [EditOperatorDialog]
 * to match the app's dialog look.
 */
@Composable
private fun ExportSettingsDialog(
    includeSensitive: Boolean,
    onToggleSensitive: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
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
                text = stringResource(R.string.settings_export_dialog_title),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_backup_include_sensitive),
                        color = TextPrimary,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = stringResource(R.string.settings_backup_include_sensitive_desc),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = includeSensitive,
                    onCheckedChange = onToggleSensitive,
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(onClick = onExport) {
                    Text(
                        stringResource(R.string.action_export),
                        color = Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Import confirmation dialog: previews how many keys will be overwritten and when
 * the backup was created, then requires an explicit confirm before applying.
 */
@Composable
private fun ConfirmImportDialog(
    keyCount: Int,
    createdAt: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
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
                text = stringResource(R.string.settings_import_confirm_title),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Text(
                text = stringResource(R.string.settings_import_confirm_msg, keyCount, createdAt),
                color = TextMuted,
                fontSize = 14.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(onClick = onConfirm) {
                    Text(
                        stringResource(R.string.action_import),
                        color = Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
