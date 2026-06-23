package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow

/**
 * Meters HUD settings — toggles which meters the pull-down HUD shows. SWR and
 * ALC are universal across CAT rigs and on by default; power / S-meter / voltage
 * / temperature are only reported by some rigs (e.g. FlexRadio/Xiegu network) and
 * are off by default. The HUD additionally hides any enabled meter the connected
 * rig doesn't actually report ("adapt per rig").
 */
@Composable
fun MetersSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_meters),
        onBack = onBack,
    ) {
        SettingsSection(title = stringResource(R.string.settings_meters_universal_section)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                MeterToggleRow(
                    mainViewModel = mainViewModel,
                    label = stringResource(R.string.meters_swr),
                    description = stringResource(R.string.settings_meter_swr_desc),
                    configKey = "meterShowSwr",
                    initial = GeneralVariables.meterShowSwr,
                    apply = { GeneralVariables.meterShowSwr = it },
                )
                SectionDivider()
                MeterToggleRow(
                    mainViewModel = mainViewModel,
                    label = stringResource(R.string.meters_alc),
                    description = stringResource(R.string.settings_meter_alc_desc),
                    configKey = "meterShowAlc",
                    initial = GeneralVariables.meterShowAlc,
                    apply = { GeneralVariables.meterShowAlc = it },
                )
            }
        }

        SettingsSection(title = stringResource(R.string.settings_meters_rig_section)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                MeterToggleRow(
                    mainViewModel = mainViewModel,
                    label = stringResource(R.string.meters_power),
                    description = stringResource(R.string.settings_meter_power_desc),
                    configKey = "meterShowPower",
                    initial = GeneralVariables.meterShowPower,
                    apply = { GeneralVariables.meterShowPower = it },
                )
                SectionDivider()
                MeterToggleRow(
                    mainViewModel = mainViewModel,
                    label = stringResource(R.string.meters_smeter),
                    description = stringResource(R.string.settings_meter_smeter_desc),
                    configKey = "meterShowSMeter",
                    initial = GeneralVariables.meterShowSMeter,
                    apply = { GeneralVariables.meterShowSMeter = it },
                )
                SectionDivider()
                MeterToggleRow(
                    mainViewModel = mainViewModel,
                    label = stringResource(R.string.meters_voltage),
                    description = stringResource(R.string.settings_meter_voltage_desc),
                    configKey = "meterShowVoltage",
                    initial = GeneralVariables.meterShowVoltage,
                    apply = { GeneralVariables.meterShowVoltage = it },
                )
                SectionDivider()
                MeterToggleRow(
                    mainViewModel = mainViewModel,
                    label = stringResource(R.string.meters_temp),
                    description = stringResource(R.string.settings_meter_temp_desc),
                    configKey = "meterShowTemp",
                    initial = GeneralVariables.meterShowTemp,
                    apply = { GeneralVariables.meterShowTemp = it },
                )
            }
        }

        Text(
            text = stringResource(R.string.settings_meters_footnote),
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

/**
 * A single meter on/off row. Mirrors the change into [GeneralVariables] (via
 * [apply], so the live HUD picks it up) and persists it under [configKey].
 */
@Composable
private fun MeterToggleRow(
    mainViewModel: MainViewModel,
    label: String,
    description: String,
    configKey: String,
    initial: Boolean,
    apply: (Boolean) -> Unit,
) {
    var enabled by remember { mutableStateOf(initial) }
    SettingsRow(
        label = label,
        description = description,
        toggle = enabled,
        onToggleChange = { checked ->
            enabled = checked
            apply(checked)
            mainViewModel.databaseOpr.writeConfig(configKey, if (checked) "1" else "0", null)
        },
    )
}
