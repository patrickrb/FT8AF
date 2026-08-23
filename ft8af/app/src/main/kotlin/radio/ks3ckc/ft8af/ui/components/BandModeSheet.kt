package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.ModeProfile
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface3
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.InterFamily
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary

// ---------------------------------------------------------------------------
// Pure helpers (unit-tested without Compose)
// ---------------------------------------------------------------------------

/**
 * Format a slot length (in ms) as the plain seconds label the mode toggle shows next to
 * each mode name: 15000 -> "15s", 7500 -> "7.5s", 3750 -> "3.75s". Trailing zeros are
 * dropped so whole seconds don't render as "15.0s".
 */
internal fun formatSlotSeconds(slotMillis: Int): String {
    val secs = slotMillis / 1000.0
    val label = if (secs % 1.0 == 0.0) {
        secs.toInt().toString()
    } else {
        secs.toString().trimEnd('0').trimEnd('.')
    }
    return "${label}s"
}

/**
 * The plain-English hint under each band in the sheet, as a string resource id. 20m alone is
 * time-of-day aware ("Best now · …" during daytime); the rest are static copy. Returns 0 for a
 * band with no hint so the caller can omit the line.
 */
internal fun bandHintRes(waveLength: String, isDaytime: Boolean): Int = when (waveLength) {
    "20m" -> if (isDaytime) R.string.band_hint_20m_best else R.string.band_hint_20m
    "40m" -> R.string.band_hint_40m
    "17m" -> R.string.band_hint_17m
    "15m" -> R.string.band_hint_15m
    "10m" -> R.string.band_hint_10m
    "80m" -> R.string.band_hint_80m
    else -> 0
}

/** One band row surfaced in the sheet: name, dial frequency, and its index into bandList. */
internal data class BandRow(
    val waveLength: String,
    val freqHz: Long,
    val bandIndex: Int,
)

/**
 * The curated "most used" band names shown in the sheet, in display order — the subset the
 * design calls out. The full band plan stays reachable via the "All bands & custom frequency"
 * footer (the existing frequency picker).
 */
internal val MOST_USED_BANDS = listOf("20m", "40m", "17m", "15m", "10m")

/**
 * Build the sheet's band rows for [modeId] from [bands] (normally [OperationBand.bandList]):
 * for each name in [MOST_USED_BANDS], pick that band's dial for the mode — preferring the
 * marked (*) entry, else the first match — and skip names with no entry in that mode. Pure so
 * it can be unit-tested with hand-built [OperationBand.Band] lists.
 */
internal fun mostUsedBands(bands: List<OperationBand.Band>, modeId: Int): List<BandRow> {
    val out = ArrayList<BandRow>()
    for (wave in MOST_USED_BANDS) {
        var chosenIdx = -1
        var chosenFreq = 0L
        for (i in bands.indices) {
            val b = bands[i]
            if (b.mode != modeId || b.waveLength != wave) continue
            if (b.marked) {
                chosenIdx = i
                chosenFreq = b.band
                break
            }
            if (chosenIdx == -1) {
                chosenIdx = i
                chosenFreq = b.band
            }
        }
        if (chosenIdx != -1) {
            out.add(BandRow(wave, chosenFreq, chosenIdx))
        }
    }
    return out
}

// ---------------------------------------------------------------------------
// Band & Mode bottom sheet (option 3b)
// ---------------------------------------------------------------------------

/**
 * The Band & Mode bottom sheet (design option 3b): a mode toggle (FT8/FT4/FT2 with cycle
 * times) over a short list of the most-used bands for that mode, plus a footer that opens the
 * full band / custom-frequency picker. Picking a band retunes via the caller's [onSelectBand]
 * (CAT if connected) and dismisses.
 */
@Composable
fun BandModeSheet(
    visible: Boolean,
    selectedModeId: Int,
    currentBandIndex: Int,
    catConnected: Boolean,
    isDaytime: Boolean,
    onDismiss: () -> Unit,
    onSelectMode: (Int) -> Unit,
    onSelectBand: (Int) -> Unit,
    onOpenAllBands: () -> Unit,
) {
    FT8AFBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            // ---- Header ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.band_sheet_title),
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = InterFamily,
                    )
                    if (catConnected) {
                        Text(
                            text = stringResource(R.string.band_sheet_subtitle),
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = InterFamily,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
                val closeLabel = stringResource(R.string.band_sheet_close)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgSurface3)
                        .clickable(onClickLabel = closeLabel) { onDismiss() }
                        .semantics { role = Role.Button; contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    FT8AFIcons.Close(size = 16.dp, color = TextMuted, strokeWidth = 2f)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Mode toggle (FT8 / FT4 / FT2) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgSurface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (mode in ModeProfile.values()) {
                    ModeSegment(
                        name = mode.displayName,
                        cycle = formatSlotSeconds(mode.slotMillis),
                        selected = mode.id == selectedModeId,
                        modifier = Modifier.weight(1f),
                        onClick = { if (mode.id != selectedModeId) onSelectMode(mode.id) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Band list for the selected mode ----
            val bands = remember(selectedModeId, GeneralVariables.excludedBands.toSet()) {
                mostUsedBands(OperationBand.bandList, selectedModeId)
                    .filterNot { GeneralVariables.isBandExcluded(it.waveLength) }
            }
            // Compare the exact band index, not just the wavelength: one band can have
            // several dial entries (bands.txt lists 14.074 and 14.090 for 20m), so matching
            // by wavelength would light up the curated 14.074 row while the rig is actually on
            // 14.090. When the rig is on an alternate dial no curated row is marked (the full
            // picker behind the footer owns alternates).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in bands) {
                    BandSheetRow(
                        row = row,
                        selected = row.bandIndex == currentBandIndex,
                        isDaytime = isDaytime,
                        onClick = { onSelectBand(row.bandIndex) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Footer: full picker ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgSurface3)
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
                    .clickable { onOpenAllBands() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(R.string.band_sheet_all_bands),
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = InterFamily,
                )
                FT8AFIcons.Chevron(size = 13.dp, color = TextMuted, strokeWidth = 2f)
            }
        }
    }
}

@Composable
private fun ModeSegment(
    name: String,
    cycle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = name,
            color = if (selected) Accent else TextMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = InterFamily,
        )
        Text(
            text = cycle,
            color = if (selected) Accent.copy(alpha = 0.8f) else TextFaint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = InterFamily,
        )
    }
}

@Composable
private fun BandSheetRow(
    row: BandRow,
    selected: Boolean,
    isDaytime: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.10f) else BgSurface3)
            .border(
                1.dp,
                if (selected) Accent.copy(alpha = 0.28f) else Border,
                RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, role = Role.RadioButton) { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = row.waveLength,
            color = if (selected) Accent else TextMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = GeistMonoFamily,
            modifier = Modifier.width(42.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${formatMhz(row.freqHz)} MHz",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
            )
            val hintRes = bandHintRes(row.waveLength, isDaytime)
            if (hintRes != 0) {
                Text(
                    text = stringResource(hintRes),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = InterFamily,
                )
            }
        }
        if (selected) {
            FT8AFIcons.Check(size = 18.dp, color = Accent, strokeWidth = 2.2f)
        }
    }
}
