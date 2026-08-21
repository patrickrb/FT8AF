package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.location.GpsClockUpdater
import com.k1af.ft8af.timer.UtcTimer
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow
import kotlin.math.roundToInt

/**
 * Time Sync settings: a manual clock-correction control for operating offline,
 * where the NTP auto-sync ("Sync now", needs internet) can't reach a time server.
 *
 * The correction drives [UtcTimer.delay] (ms) — the single offset every RX window,
 * TX start, and the slot-timer bar reads through — and is persisted so it survives
 * a relaunch. A suggestion is derived from the average decode DT of stations we're
 * hearing (our only time reference when offline); see [suggestedCorrectionMs].
 */
@Composable
fun TimeSyncSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // UtcTimer.delay is the live source of truth; seed local state from it.
    var correctionMs by remember { mutableIntStateOf(UtcTimer.delay) }

    // GPS clock discipline (issue #373).
    var disciplineFromGps by remember { mutableStateOf(GeneralVariables.disciplineClockFromGPS) }

    // Self-syncing clock: auto-trim the correction from decode DT medians.
    var autoSyncFromDecodes by remember { mutableStateOf(GeneralVariables.autoSyncClockFromDecodes) }
    var gpsIntervalMin by remember { mutableIntStateOf(GeneralVariables.gpsClockIntervalMinutes) }
    // Re-read the status readout whenever a GPS fix disciplines the clock. The LiveData
    // retains its last posted timestamp, so seeding from .value shows a prior sync when the
    // screen is reopened in the same session.
    val lastGpsSync by GeneralVariables.mutableGpsClockSync.observeAsState(
        GeneralVariables.mutableGpsClockSync.value
    )

    // Latest cycle's average decode DT (seconds), posted in MainViewModel.afterDecode.
    // Null until the first decode this session.
    val avgDtSec by mainViewModel.mutableTimerOffset.observeAsState()

    // Last self-sync correction (timestamp), so the Suggestion card can show that
    // auto-sync is doing the work. Seeded from .value like the GPS readout so a
    // correction applied before this screen opened still shows.
    val lastSelfSync by GeneralVariables.mutableSelfSyncApplied.observeAsState(
        GeneralVariables.mutableSelfSyncApplied.value
    )

    // While GPS discipline owns the clock, each fix rewrites UtcTimer.delay behind this
    // screen's back — and disabling it restores the pre-GPS offset. Re-read the live value
    // on every posted sync and on toggle changes so the "Current" readout can't go stale.
    LaunchedEffect(lastGpsSync, disciplineFromGps) {
        correctionMs = UtcTimer.delay
    }

    // Apply a new correction everywhere: live timer, in-memory config mirror, and DB.
    fun apply(newMs: Int) {
        val clamped = clampCorrectionMs(newMs)
        correctionMs = clamped
        UtcTimer.delay = clamped
        GeneralVariables.manualTimeCorrectionMs = clamped
        mainViewModel.databaseOpr.writeConfig("timeCorrectionMs", clamped.toString(), null)
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_time_sync),
        onBack = onBack,
    ) {
        // =====================================================================
        // MANUAL CORRECTION
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_time_correction_section)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_time_current_label),
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatOffsetMs(correctionMs),
                        color = if (correctionMs == 0) TextPrimary else Accent,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // While GPS discipline owns the clock the manual controls are inert —
                    // editing them would fight the next GPS fix and overwrite the persisted
                    // manual value. Disable them and say why.
                    val manualEnabled = !disciplineFromGps
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StepButton("-0.5", Modifier.weight(1f), manualEnabled) { apply(stepCorrectionMs(correctionMs, -500)) }
                        StepButton("-0.1", Modifier.weight(1f), manualEnabled) { apply(stepCorrectionMs(correctionMs, -100)) }
                        StepButton("+0.1", Modifier.weight(1f), manualEnabled) { apply(stepCorrectionMs(correctionMs, 100)) }
                        StepButton("+0.5", Modifier.weight(1f), manualEnabled) { apply(stepCorrectionMs(correctionMs, 500)) }
                    }
                    if (disciplineFromGps) {
                        Text(
                            text = stringResource(R.string.settings_time_correction_gps_locked),
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_time_correction_reset),
                            color = if (correctionMs == 0) TextFaint else Accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = correctionMs != 0) { apply(0) }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_time_correction_desc),
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }

        // =====================================================================
        // SELF-SYNCING CLOCK (auto-correct from decode DT medians)
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_selfsync_section)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    label = stringResource(R.string.settings_selfsync_toggle),
                    description = stringResource(R.string.settings_selfsync_toggle_desc),
                    toggle = autoSyncFromDecodes,
                    // Same lock-out as the manual controls: while GPS discipline owns
                    // the clock this estimator must not fight it, so the row is inert.
                    enabled = !disciplineFromGps,
                    onToggleChange = { checked ->
                        autoSyncFromDecodes = checked
                        GeneralVariables.autoSyncClockFromDecodes = checked
                        mainViewModel.databaseOpr.writeConfig(
                            "autoSyncClockFromDecodes",
                            if (checked) "1" else "0",
                            null,
                        )
                        if (!checked) {
                            // Drop any half-built confirmation streak so re-enabling
                            // later starts from a clean slate.
                            mainViewModel.clockSelfSync.reset()
                        }
                    },
                )
            }
        }

        // =====================================================================
        // SUGGESTION (from decode DT)
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_time_suggest_section)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val dt = avgDtSec
                    if (dt == null) {
                        Text(
                            text = stringResource(R.string.settings_time_suggest_none),
                            color = TextMuted,
                            fontSize = 14.sp,
                        )
                    } else {
                        val dtMs = (dt * 1000f).roundToInt()
                        Text(
                            text = stringResource(
                                R.string.settings_time_suggest_label,
                                formatOffsetMs(dtMs),
                            ),
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontFamily = GeistMonoFamily,
                        )
                        if (showSuggestionApply(autoSyncFromDecodes, disciplineFromGps)) {
                            Text(
                                text = stringResource(R.string.settings_time_suggest_apply),
                                color = Accent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { apply(suggestedCorrectionMs(correctionMs, dt)) }
                                    .padding(vertical = 6.dp),
                            )
                        } else if (disciplineFromGps) {
                            Text(
                                text = stringResource(R.string.settings_time_correction_gps_locked),
                                color = TextMuted,
                                fontSize = 13.sp,
                            )
                        } else {
                            // Auto-sync owns this: say so instead of asking for a tap.
                            Text(
                                text = stringResource(R.string.settings_time_suggest_auto),
                                color = TextMuted,
                                fontSize = 13.sp,
                            )
                            val syncMs = lastSelfSync
                            Text(
                                text = if (syncMs == null) {
                                    stringResource(R.string.settings_time_suggest_auto_waiting)
                                } else {
                                    stringResource(
                                        R.string.settings_time_suggest_auto_last,
                                        formatOffsetMs(GeneralVariables.selfSyncLastStepMs),
                                        UtcTimer.getDatetimeStr(syncMs),
                                    )
                                },
                                color = if (syncMs == null) TextMuted else TextPrimary,
                                fontSize = 14.sp,
                                fontFamily = GeistMonoFamily,
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // GPS CLOCK DISCIPLINE (issue #373)
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_gps_clock_section)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    label = stringResource(R.string.settings_gps_clock_toggle),
                    description = stringResource(R.string.settings_gps_clock_toggle_desc),
                    toggle = disciplineFromGps,
                    onToggleChange = { checked ->
                        // On enable, make sure we have location permission — the
                        // updater silently no-ops without it (same pattern as the
                        // GPS grid toggle).
                        if (checked) {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                (context as? Activity)?.let { activity ->
                                    ActivityCompat.requestPermissions(
                                        activity,
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                        42,
                                    )
                                }
                            }
                        }
                        disciplineFromGps = checked
                        GeneralVariables.disciplineClockFromGPS = checked
                        mainViewModel.databaseOpr.writeConfig(
                            "disciplineClockFromGPS", if (checked) "1" else "0", null,
                        )
                        if (checked) {
                            // GPS discipline takes over the clock; the self-sync
                            // estimator stands down — clear its streak so stale
                            // pre-GPS evidence can't act if GPS is later disabled.
                            mainViewModel.clockSelfSync.reset()
                        }
                        GpsClockUpdater.refresh(context)
                    },
                )
            }

            if (disciplineFromGps) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_gps_clock_interval),
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (min in GPS_INTERVAL_OPTIONS) {
                                IntervalChip(
                                    label = stringResource(R.string.settings_gps_clock_interval_value, min),
                                    selected = gpsIntervalMin == min,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    gpsIntervalMin = min
                                    GeneralVariables.gpsClockIntervalMinutes = min
                                    mainViewModel.databaseOpr.writeConfig(
                                        "gpsClockIntervalMin", min.toString(), null,
                                    )
                                    // Re-subscribe at the new cadence.
                                    GpsClockUpdater.refresh(context)
                                }
                            }
                        }

                        // Status readout: last sync + applied offset, or "waiting".
                        val syncMs = lastGpsSync
                        if (syncMs == null) {
                            Text(
                                text = stringResource(R.string.settings_gps_clock_waiting),
                                color = TextMuted,
                                fontSize = 14.sp,
                            )
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.settings_gps_clock_last_sync,
                                    UtcTimer.getDatetimeStr(syncMs),
                                ),
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontFamily = GeistMonoFamily,
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_gps_clock_offset,
                                    formatOffsetMs(GeneralVariables.gpsClockOffsetMs),
                                ),
                                color = if (GeneralVariables.gpsClockOffsetMs == 0) TextPrimary else Accent,
                                fontSize = 14.sp,
                                fontFamily = GeistMonoFamily,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Update-interval presets (minutes) offered for GPS clock discipline. */
private val GPS_INTERVAL_OPTIONS = intArrayOf(1, 5, 10, 15, 30)

/**
 * A bordered, tappable interval chip that highlights when selected. Local to this screen.
 */
@Composable
private fun IntervalChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .border(
                BorderStroke(1.dp, if (selected) Accent else BorderStrong),
                RoundedCornerShape(10.dp),
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Accent else TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
        )
    }
}

/**
 * A bordered, tappable stepper button (e.g. "-0.1"). Kept local to this screen.
 */
@Composable
private fun StepButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .border(BorderStroke(1.dp, BorderStrong), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Accent else TextFaint,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = GeistMonoFamily,
        )
    }
}
