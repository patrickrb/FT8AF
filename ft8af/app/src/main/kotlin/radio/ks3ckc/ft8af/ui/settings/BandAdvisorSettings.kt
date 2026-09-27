package radio.ks3ckc.ft8af.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.bandadvisor.BandAdvisor
import radio.ks3ckc.ft8af.bandadvisor.alerts.AlertPreferencesStore
import radio.ks3ckc.ft8af.bandadvisor.alerts.AlertPrefs
import radio.ks3ckc.ft8af.bandadvisor.alerts.AlertType
import radio.ks3ckc.ft8af.bandadvisor.alerts.PropagationAlertScheduler
import radio.ks3ckc.ft8af.flags.FeatureFlag
import radio.ks3ckc.ft8af.flags.FeatureFlags
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.InterFamily
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.ui.bandadvisor.BandAdvisorTelemetry
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow

/** Bands offered in the watch list (superset of the curated band sheet). */
internal val ALERT_BAND_CHOICES =
    listOf("160m", "80m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m")

/** Target regions matching the band-advisor service's region enum. */
internal val ALERT_REGION_CHOICES = listOf(
    "EUROPE", "NORTH_AMERICA_EAST", "NORTH_AMERICA_WEST", "SOUTH_AMERICA",
    "AFRICA", "ASIA", "OCEANIA",
)

internal fun alertTypeLabelRes(type: AlertType): Int = when (type) {
    AlertType.BAND_OPENING -> R.string.prop_alerts_band_opening
    AlertType.BAND_ACTIVE -> R.string.prop_alerts_band_active
    AlertType.REGION_REACHABLE -> R.string.prop_alerts_region_reachable
    AlertType.PERSONAL_IMPROVEMENT -> R.string.prop_alerts_personal
    AlertType.UNUSUAL_OPENING -> R.string.prop_alerts_unusual
}

/** Toggle one element in a set. Pure. */
internal fun <T> toggled(set: Set<T>, element: T): Set<T> =
    if (element in set) set - element else set + element

/**
 * Band Advisor settings: propagation-alert opt-ins (only while the
 * PROPAGATION_ALERTS flag is on), quiet hours, watched bands/regions, the
 * privacy note, and the local-data clear control. Every change persists and
 * re-syncs the WorkManager schedule, so switching the master toggle off
 * cancels pending background work immediately.
 */
@Composable
fun BandAdvisorSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(AlertPreferencesStore.load(context)) }
    val alertsFlagOn = FeatureFlags.isEnabled(FeatureFlag.PROPAGATION_ALERTS)

    fun update(transform: (AlertPrefs) -> AlertPrefs) {
        prefs = transform(prefs)
        AlertPreferencesStore.save(context, prefs)
        PropagationAlertScheduler.sync(context)
    }

    // Android 13+ runtime permission for notifications, requested when the
    // master toggle turns on. A denial leaves the preference on — checks run,
    // but the worker skips posting until permission exists.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.prop_alerts_permission_needed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    var showQuietStart by remember { mutableStateOf(false) }
    var showQuietEnd by remember { mutableStateOf(false) }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_band_advisor),
        onBack = onBack,
    ) {
        if (alertsFlagOn) {
            SettingsSection(title = stringResource(R.string.prop_alerts_settings_title)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.prop_alerts_master_toggle),
                            description = stringResource(R.string.prop_alerts_master_desc),
                            toggle = prefs.enabled,
                            onToggleChange = { enabled ->
                                update { it.copy(enabled = enabled) }
                                BandAdvisorTelemetry.event(
                                    if (enabled) "alerts_enabled" else "alerts_disabled", null,
                                )
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                        if (prefs.enabled) {
                            for (type in AlertType.entries) {
                                SectionDivider()
                                SettingsRow(
                                    label = stringResource(alertTypeLabelRes(type)),
                                    toggle = type in prefs.enabledTypes,
                                    onToggleChange = {
                                        update { p -> p.copy(enabledTypes = toggled(p.enabledTypes, type)) }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (prefs.enabled) {
                SettingsSection(title = stringResource(R.string.prop_alerts_quiet_hours)) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingsRow(
                                label = stringResource(R.string.prop_alerts_quiet_from, prefs.quietStartHour),
                                description = stringResource(R.string.prop_alerts_quiet_hours_desc),
                                showChevron = true,
                                onClick = { showQuietStart = true },
                            )
                            SectionDivider()
                            SettingsRow(
                                label = stringResource(R.string.prop_alerts_quiet_to, prefs.quietEndHour),
                                showChevron = true,
                                onClick = { showQuietEnd = true },
                            )
                        }
                    }
                }

                SettingsSection(title = stringResource(R.string.prop_alerts_bands)) {
                    ChipFlow(
                        choices = ALERT_BAND_CHOICES,
                        selected = prefs.watchedBands,
                        onToggle = { band ->
                            update { p -> p.copy(watchedBands = toggled(p.watchedBands, band)) }
                        },
                    )
                }

                SettingsSection(title = stringResource(R.string.prop_alerts_regions)) {
                    ChipFlow(
                        choices = ALERT_REGION_CHOICES,
                        selected = prefs.watchedRegions,
                        onToggle = { region ->
                            update { p -> p.copy(watchedRegions = toggled(p.watchedRegions, region)) }
                        },
                    )
                }
            }
        }

        // Privacy: what leaves the device + the local-data clear control.
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    text = stringResource(R.string.band_advisor_privacy_note),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = InterFamily,
                    modifier = Modifier.padding(16.dp),
                )
                SectionDivider()
                SettingsRow(
                    label = stringResource(R.string.band_advisor_clear_local),
                    description = stringResource(R.string.band_advisor_clear_local_desc),
                    onClick = {
                        BandAdvisor.clearLocalData(context)
                        Toast.makeText(
                            context,
                            context.getString(R.string.band_advisor_cleared),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }

    if (showQuietStart) {
        HourPickerDialog(
            title = stringResource(R.string.prop_alerts_quiet_hours),
            selectedHour = prefs.quietStartHour,
            onDismiss = { showQuietStart = false },
            onSelect = { hour ->
                showQuietStart = false
                update { it.copy(quietStartHour = hour) }
            },
        )
    }
    if (showQuietEnd) {
        HourPickerDialog(
            title = stringResource(R.string.prop_alerts_quiet_hours),
            selectedHour = prefs.quietEndHour,
            onDismiss = { showQuietEnd = false },
            onSelect = { hour ->
                showQuietEnd = false
                update { it.copy(quietEndHour = hour) }
            },
        )
    }
}

@Composable
private fun HourPickerDialog(
    title: String,
    selectedHour: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    ListPickerDialog(
        title = title,
        items = (0..23).map { String.format(java.util.Locale.US, "%02d:00", it) },
        selectedIndex = selectedHour.coerceIn(0, 23),
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}

/** Simple wrapping row of selectable chips (bands / regions). */
@Composable
private fun ChipFlow(
    choices: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in choices.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in row) {
                        val isSelected = choice in selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Accent.copy(alpha = 0.18f) else BgSurface)
                                .clickable { onToggle(choice) }
                                .padding(vertical = 8.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Text(
                                text = choice.replace('_', ' '),
                                color = if (isSelected) Accent else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                fontFamily = InterFamily,
                            )
                        }
                    }
                    repeat(3 - row.size) {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
