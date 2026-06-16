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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.timer.UtcTimer
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard
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
    // UtcTimer.delay is the live source of truth; seed local state from it.
    var correctionMs by remember { mutableIntStateOf(UtcTimer.delay) }

    // Latest cycle's average decode DT (seconds), posted in MainViewModel.afterDecode.
    // Null until the first decode this session.
    val avgDtSec by mainViewModel.mutableTimerOffset.observeAsState()

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StepButton("-0.5", Modifier.weight(1f)) { apply(stepCorrectionMs(correctionMs, -500)) }
                        StepButton("-0.1", Modifier.weight(1f)) { apply(stepCorrectionMs(correctionMs, -100)) }
                        StepButton("+0.1", Modifier.weight(1f)) { apply(stepCorrectionMs(correctionMs, 100)) }
                        StepButton("+0.5", Modifier.weight(1f)) { apply(stepCorrectionMs(correctionMs, 500)) }
                    }
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
            Text(
                text = stringResource(R.string.settings_time_correction_desc),
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
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
                        Text(
                            text = stringResource(R.string.settings_time_suggest_apply),
                            color = Accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { apply(suggestedCorrectionMs(correctionMs, dt)) }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A bordered, tappable stepper button (e.g. "-0.1"). Kept local to this screen.
 */
@Composable
private fun StepButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .border(BorderStroke(1.dp, BorderStrong), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = GeistMonoFamily,
        )
    }
}
