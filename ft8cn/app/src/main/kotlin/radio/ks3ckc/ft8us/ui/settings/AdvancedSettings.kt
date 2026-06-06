package radio.ks3ckc.ft8us.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.R
import radio.ks3ckc.ft8us.ui.components.GlassCard
import radio.ks3ckc.ft8us.ui.components.SettingsRow

/**
 * BCP-47 language tags for the in-app Language picker, parallel to the label list
 * built in the picker dialog. Index 0 ("") means "System default" (empty locale
 * list). Keep in sync with res/xml/locales_config.xml and the values-<locale>/ dirs.
 */
private val LANGUAGE_TAGS = listOf("", "en", "zh-CN", "zh-TW", "ru", "es", "fr", "ja")

/**
 * Advanced settings: PTT/TX timing delays, late-start tolerance, and the
 * in-app language picker.
 */
@Composable
fun AdvancedSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var pttDelay by remember { mutableIntStateOf(GeneralVariables.pttDelay) }
    var txDelay by remember { mutableIntStateOf(GeneralVariables.transmitDelay) }
    var lateStartMs by remember { mutableIntStateOf(GeneralVariables.lateStartTolerance) }

    var showPttDelay by remember { mutableStateOf(false) }
    var showTxDelay by remember { mutableStateOf(false) }
    var showLateStart by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

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
        // LANGUAGE
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
    }
}
