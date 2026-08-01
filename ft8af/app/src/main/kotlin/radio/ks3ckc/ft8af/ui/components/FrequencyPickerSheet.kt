package radio.ks3ckc.ft8af.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.rigs.BaseRigOperation
import radio.ks3ckc.ft8af.theme.*

/**
 * Applies a band selection (by index into [OperationBand.bandList]) to the app: updates
 * GeneralVariables, persists the new bandFreq in config, refreshes QSL callsigns, and
 * pushes the new frequency to the rig when CAT/RTS/DTR control is active.
 *
 * Shared between the Settings band picker and the TxStrip frequency picker.
 */
fun selectBandIndex(mainViewModel: MainViewModel, context: Context, index: Int) {
    // Compare the meter (wavelength) band, not the index — see shouldClearOnBandChange.
    val oldWaveLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band)
    GeneralVariables.bandListIndex = index
    GeneralVariables.band = OperationBand.getBandFreq(index)
    // An explicit operator choice: this is the dial the app asserts from now on, and the
    // one the reassert heartbeat re-sends. See RigDialTarget.
    GeneralVariables.commandedBandHz = GeneralVariables.band
    val newWaveLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band)
    mainViewModel.databaseOpr.writeConfig(
        "bandFreq", GeneralVariables.band.toString(), null,
    )
    mainViewModel.databaseOpr.getAllQSLCallsigns()
    // Notify observers (TxStrip pill, Settings band picker) so the UI updates
    // without waiting for a rig onFreqChanged round-trip.
    GeneralVariables.mutableBandChange.postValue(index)
    // A real band hop invalidates the clear-CQ-slot occupancy history (issue #418).
    if (newWaveLength != oldWaveLength) {
        mainViewModel.ft8TransmitSignal.clearBandActivity()
    }
    // The operator picked a new band — optionally clear the stale decodes + reset
    // the TX target so the decode screen reflects the new band (tester request).
    if (MainViewModel.shouldClearOnBandChange(
            GeneralVariables.clearOnBandModeChange, oldWaveLength, newWaveLength,
        )
    ) {
        mainViewModel.clearDecodesAndTarget()
    }

    // Per-band output level (issue #355): when enabled and this band has a
    // saved level that differs from the current one, restore it and tell the
    // operator where the change came from. Bands with no saved value keep the
    // current (global) level.
    val restoredLevel = radio.ks3ckc.ft8af.restoredOutputLevelForBand(newWaveLength)
    if (restoredLevel != null) {
        GeneralVariables.volumePercent = restoredLevel / 100f
        GeneralVariables.mutableVolumePercent.postValue(restoredLevel / 100f)
        mainViewModel.databaseOpr.writeConfig("volumeValue", restoredLevel.toString(), null)
        mainViewModel.baseRig?.connector?.setRFVolume(restoredLevel)
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.per_band_volume_restored, restoredLevel, newWaveLength),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    val cm = GeneralVariables.controlMode
    val connected = mainViewModel.isRigConnected()
    android.util.Log.d(
        "FrequencyPicker",
        "bandSelect: index=$index, band=${GeneralVariables.band}, " +
            "controlMode=$cm, rigConnected=$connected",
    )
    try {
        val dir = context.getExternalFilesDir(null)
        if (dir != null) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            java.io.File(dir, "debug.log").appendText(
                "$ts bandSelect: index=$index, band=${GeneralVariables.band}, " +
                    "controlMode=$cm, rigConnected=$connected\n",
            )
        }
    } catch (_: Exception) {
    }

    if (cm == ControlMode.CAT || cm == ControlMode.RTS || cm == ControlMode.DTR) {
        mainViewModel.setOperationBand()
    }
}

private data class BandGroup(
    val waveLength: String,
    val primaryIndex: Int,
    val primaryFreqHz: Long,
    val alternates: List<Pair<Int, Long>>,
)

/**
 * Build the band model from OperationBand.bandList:
 *   - Group entries by waveLength (file order preserved).
 *   - Primary = first entry with marked == true; fall back to first entry in group.
 *   - Alternates = every other entry in the group, in file order.
 */
private fun buildBandGroups(): List<BandGroup> {
    val order = LinkedHashMap<String, MutableList<Pair<Int, OperationBand.Band>>>()
    for (i in 0 until OperationBand.bandList.size) {
        val b = OperationBand.bandList[i]
        // Only the current operating mode's dials (FT8 vs FT4 use different frequencies).
        if (b.mode != GeneralVariables.operatingMode) continue
        if (GeneralVariables.isBandExcluded(b.waveLength)) continue
        order.getOrPut(b.waveLength) { mutableListOf() }.add(i to b)
    }
    return order.map { (wave, entries) ->
        val primary = entries.firstOrNull { it.second.marked } ?: entries.first()
        val alternates = entries.filter { it.first != primary.first }
            .map { it.first to it.second.band }
        BandGroup(
            waveLength = wave,
            primaryIndex = primary.first,
            primaryFreqHz = primary.second.band,
            alternates = alternates,
        )
    }
}

internal fun formatMhz(freqHz: Long): String {
    val mhz = freqHz / 1_000_000.0
    return String.format(java.util.Locale.US, "%.3f", mhz)
}

@Composable
fun FrequencyPickerSheet(
    visible: Boolean,
    currentBandIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    FT8AFBottomSheet(visible = visible, onDismiss = onDismiss) {
        val groups = remember(GeneralVariables.excludedBands.toSet(), GeneralVariables.operatingMode) { buildBandGroups() }
        var showAlternates by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.freq_select_title),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.06.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            BandTileGrid(
                groups = groups,
                currentBandIndex = currentBandIndex,
                onTileClick = onSelect,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Show / hide alternates toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showAlternates = !showAlternates }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showAlternates) stringResource(R.string.freq_hide_alternates) else stringResource(R.string.freq_show_alternates),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.08.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (showAlternates) "△" else "▽",
                    color = TextFaint,
                    fontSize = 11.sp,
                )
            }

            if (showAlternates) {
                Spacer(modifier = Modifier.height(4.dp))
                AlternatesList(
                    groups = groups,
                    currentBandIndex = currentBandIndex,
                    onChipClick = onSelect,
                )
            }
        }
    }
}

@Composable
private fun BandTileGrid(
    groups: List<BandGroup>,
    currentBandIndex: Int,
    onTileClick: (Int) -> Unit,
) {
    val columns = 3
    val rows = groups.chunked(columns)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in row) {
                    // If an alternate within this group is the active selection,
                    // surface that on the tile so the user doesn't have to open the
                    // alternates list to see what's tuned. Tapping the tile still
                    // tunes to the band's primary — that's also the "reset to
                    // default" gesture for getting off an alternate.
                    val selectedAlt = g.alternates.firstOrNull { it.first == currentBandIndex }
                    val isSelected = g.primaryIndex == currentBandIndex || selectedAlt != null
                    val displayFreqHz = selectedAlt?.second ?: g.primaryFreqHz
                    BandTile(
                        waveLength = g.waveLength,
                        freqHz = displayFreqHz,
                        isSelected = isSelected,
                        isAlternate = selectedAlt != null,
                        modifier = Modifier.weight(1f),
                        onClick = { onTileClick(g.primaryIndex) },
                    )
                }
                // Pad incomplete row with empty weights so tiles stay sized consistently.
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BandTile(
    waveLength: String,
    freqHz: Long,
    isSelected: Boolean,
    isAlternate: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) AccentSoft else BgSurface3
    val bandColor = if (isSelected) Accent else TextPrimary
    val freqColor = if (isSelected) Accent else TextMuted

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = waveLength,
                color = bandColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistMonoFamily,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAlternate) {
                    Text(
                        text = stringResource(R.string.freq_alt_prefix),
                        color = freqColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistMonoFamily,
                        letterSpacing = 0.08.sp,
                    )
                }
                Text(
                    text = formatMhz(freqHz),
                    color = freqColor,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                )
            }
        }
    }
}

@Composable
private fun AlternatesList(
    groups: List<BandGroup>,
    currentBandIndex: Int,
    onChipClick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (g in groups) {
            if (g.alternates.isEmpty()) continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = g.waveLength,
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    modifier = Modifier.width(44.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (alt in g.alternates) {
                        AlternateChip(
                            label = formatMhz(alt.second),
                            isSelected = alt.first == currentBandIndex,
                            onClick = { onChipClick(alt.first) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlternateChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) AccentSoft else BgSurface3
    val fg = if (isSelected) Accent else TextMuted
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
        )
    }
}
