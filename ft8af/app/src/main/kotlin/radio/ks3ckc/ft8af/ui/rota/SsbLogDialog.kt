package radio.ks3ckc.ft8af.ui.rota

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import com.k1af.ft8af.database.DatabaseOpr
import com.k1af.ft8af.log.QSLRecord
import com.k1af.ft8af.rigs.BaseRigOperation
import radio.ks3ckc.ft8af.rota.RotaSettings
import radio.ks3ckc.ft8af.rota.normalizeCallsign
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Quick manual entry for a voice contact made while a ROTA trip is running.
 *
 * FT8 contacts log themselves; a rover who picks up the mic has nothing that
 * does. This dialog builds the same [QSLRecord] the FT8 path builds and hands
 * it to [DatabaseOpr.addQSL_Callsign], so everything downstream comes for
 * free: the GPS position stamp, the ADIF mirror, and the ROTA live upload
 * that puts the contact on the trip map.
 *
 * All decision logic lives in the top-level `internal` helpers below so it is
 * unit-testable without Compose (see CLAUDE.md).
 */
@Composable
internal fun LogSsbQsoDialog(
    onDismiss: () -> Unit,
    onLogged: (String) -> Unit,
) {
    var callsign by remember { mutableStateOf("") }
    var freqMhz by remember { mutableStateOf(formatFreqMhz(RotaSettings.lastSsbFreqHz)) }
    var rstSent by remember { mutableStateOf(DEFAULT_SSB_REPORT) }
    var rstRcvd by remember { mutableStateOf(DEFAULT_SSB_REPORT) }
    var grid by remember { mutableStateOf("") }

    val freqHz = parseSsbFrequencyMhzToHz(freqMhz)
    val bandLabel = freqHz?.let { BaseRigOperation.getMeterFromFreq(it) }.orEmpty()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgSurface2)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.rota_log_ssb),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = callsign,
                onValueChange = { callsign = it.uppercase(Locale.US) },
                label = { Text(stringResource(R.string.rota_ssb_callsign)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                colors = rotaFieldColors(),
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = freqMhz,
                onValueChange = { freqMhz = it },
                label = { Text(stringResource(R.string.rota_ssb_freq)) },
                // The derived band ("20m") confirms the dial reading was typed
                // right — a slipped decimal point reads back as the wrong band.
                supportingText =
                    if (bandLabel.isNotEmpty()) {
                        { Text(bandLabel, color = TextFaint, fontSize = 12.sp) }
                    } else {
                        null
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = rotaFieldColors(),
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = rstSent,
                    onValueChange = { rstSent = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text(stringResource(R.string.rota_ssb_rst_sent)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = rotaFieldColors(),
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = rstRcvd,
                    onValueChange = { rstRcvd = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text(stringResource(R.string.rota_ssb_rst_rcvd)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = rotaFieldColors(),
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = grid,
                onValueChange = { grid = it.uppercase(Locale.US) },
                label = { Text(stringResource(R.string.rota_ssb_grid)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                colors = rotaFieldColors(),
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        if (!ssbEntryValid(callsign, freqMhz, rstSent, rstRcvd, grid)) {
                            return@TextButton
                        }
                        val record =
                            buildSsbQslRecord(
                                nowMs = System.currentTimeMillis(),
                                myCallsign = GeneralVariables.myCallsign.orEmpty(),
                                myGrid = GeneralVariables.getMyMaidenheadGrid().orEmpty(),
                                toCallsign = callsign,
                                toGrid = grid,
                                rstSent = parseSsbReport(rstSent) ?: DEFAULT_SSB_REPORT_INT,
                                rstRcvd = parseSsbReport(rstRcvd) ?: DEFAULT_SSB_REPORT_INT,
                                freqHz = freqHz ?: 0L,
                            )
                        freqHz?.let { RotaSettings.lastSsbFreqHz = it }
                        DatabaseOpr.getInstance(GeneralVariables.getMainContext(), null)
                            .addQSL_Callsign(record)
                        onLogged(record.toCallsign)
                    },
                ) {
                    Text(stringResource(R.string.rota_ssb_log_action), color = Accent)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pure helpers (unit-tested in SsbLogDialogTest)
// ---------------------------------------------------------------------------

internal const val DEFAULT_SSB_REPORT = "59"
internal const val DEFAULT_SSB_REPORT_INT = 59

/**
 * Dial frequency typed in MHz → Hz, or null when it isn't a number or falls
 * outside the range the server's schema accepts (100 kHz – 10 GHz). Rejecting
 * here beats sending a value that would invalidate the whole upload batch.
 */
internal fun parseSsbFrequencyMhzToHz(input: String): Long? {
    val mhz = input.trim().toDoubleOrNull() ?: return null
    if (!mhz.isFinite() || mhz <= 0.0) return null
    val hz = (mhz * 1_000_000.0).roundToLong()
    return if (hz in 100_000L..10_000_000_000L) hz else null
}

/** Hz → the MHz text shown in the frequency field ("14.250"); empty for no value. */
internal fun formatFreqMhz(freqHz: Long): String =
    if (freqHz <= 0L) "" else String.format(Locale.US, "%.3f", freqHz / 1_000_000.0)

/**
 * A voice RS report: readability 1–5 then strength 1–9, e.g. "59". Returns the
 * value as the int the QSO record stores, or null when the text isn't one.
 */
internal fun parseSsbReport(input: String): Int? {
    val s = input.trim()
    if (s.length != 2 || !s.all { it.isDigit() }) return null
    val readability = s[0] - '0'
    val strength = s[1] - '0'
    if (readability !in 1..5 || strength !in 1..9) return null
    return readability * 10 + strength
}

/** Blank (grid unknown) or a plausible 4/6-char Maidenhead locator. */
internal fun isValidGridOrBlank(grid: String): Boolean {
    val g = grid.trim()
    return g.isEmpty() || Regex("^[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?$").matches(g)
}

/**
 * Everything the Log button needs to allow the tap. Frequency may be blank —
 * a contact without a dial reading still counts — but a non-blank one must
 * parse, or a typo would silently log a QSO with no frequency at all.
 */
internal fun ssbEntryValid(
    callsign: String,
    freqMhz: String,
    rstSent: String,
    rstRcvd: String,
    grid: String,
): Boolean =
    callsign.isNotBlank() &&
        (freqMhz.isBlank() || parseSsbFrequencyMhzToHz(freqMhz) != null) &&
        parseSsbReport(rstSent) != null &&
        parseSsbReport(rstRcvd) != null &&
        isValidGridOrBlank(grid)

/**
 * The [QSLRecord] for a manual SSB contact logged right now. Uses the same
 * constructor as the FT8 auto-log path so the record is indistinguishable
 * downstream — DB row, ADIF mirror, position stamp, and ROTA upload included.
 */
internal fun buildSsbQslRecord(
    nowMs: Long,
    myCallsign: String,
    myGrid: String,
    toCallsign: String,
    toGrid: String,
    rstSent: Int,
    rstRcvd: Int,
    freqHz: Long,
): QSLRecord =
    QSLRecord(
        nowMs,
        nowMs,
        myCallsign,
        myGrid,
        normalizeCallsign(toCallsign),
        toGrid.trim().uppercase(Locale.US),
        rstSent,
        rstRcvd,
        "SSB",
        freqHz,
        0,
    )
