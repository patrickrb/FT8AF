package radio.ks3ckc.ft8us.ui.pota

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.R
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8us.pota.PotaClient
import radio.ks3ckc.ft8us.pota.PotaSessionManager
import radio.ks3ckc.ft8us.pota.PotaSpotsRepository
import radio.ks3ckc.ft8us.pota.model.PotaActivation
import radio.ks3ckc.ft8us.pota.model.PotaQso
import radio.ks3ckc.ft8us.pota.model.PotaSpot
import radio.ks3ckc.ft8us.ui.decode.QrzAvatar
import radio.ks3ckc.ft8us.theme.Accent
import radio.ks3ckc.ft8us.theme.AccentSoft
import radio.ks3ckc.ft8us.theme.BgApp
import radio.ks3ckc.ft8us.theme.BgSurface
import radio.ks3ckc.ft8us.theme.BgSurface2
import radio.ks3ckc.ft8us.theme.Border
import radio.ks3ckc.ft8us.theme.BorderStrong
import radio.ks3ckc.ft8us.theme.StatusConfirmed
import radio.ks3ckc.ft8us.theme.TextMuted
import radio.ks3ckc.ft8us.theme.TextPrimary

private enum class PotaSubTab(@StringRes val labelRes: Int) {
    ACTIVATE(R.string.pota_tab_activate),
    HUNT(R.string.pota_tab_hunt),
    HISTORY(R.string.pota_tab_history),
}

@Composable
fun PotaScreen(mainViewModel: MainViewModel) {
    var subTab by rememberSaveable { mutableStateOf(PotaSubTab.ACTIVATE) }

    // Hunter tab and an active activation both rely on fresh spots — start
    // polling whenever the POTA screen is mounted.
    DisposableEffect(Unit) {
        PotaSpotsRepository.start()
        onDispose { PotaSpotsRepository.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        PotaTabHeader(subTab = subTab, onTabSelected = { subTab = it })
        Spacer(Modifier.height(10.dp))
        when (subTab) {
            PotaSubTab.ACTIVATE -> ActivateTab()
            PotaSubTab.HUNT -> HuntTab(mainViewModel)
            PotaSubTab.HISTORY -> HistoryTab(mainViewModel)
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-tab chip header
// ---------------------------------------------------------------------------

@Composable
private fun PotaTabHeader(subTab: PotaSubTab, onTabSelected: (PotaSubTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (tab in PotaSubTab.entries) {
            val selected = tab == subTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) AccentSoft else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    color = if (selected) Accent else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Activate tab
// ---------------------------------------------------------------------------

@Composable
private fun ActivateTab() {
    val activation by PotaSessionManager.currentActivation.collectAsStateWithLifecycle()
    val contacts by PotaSessionManager.activationQsos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parkRefs = remember { mutableStateListOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Refresh the activation's QSO counter periodically while one is running so
    // the on-screen tally reflects QSOs added by the TX/RX path.
    LaunchedEffect(activation?.id, refreshKey) {
        if (activation != null) {
            kotlinx.coroutines.delay(3000)
            PotaSessionManager.refreshCounter()
            refreshKey++
        }
    }

    if (activation == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card {
                SectionTitle(stringResource(R.string.pota_start_activation))
                Spacer(Modifier.height(8.dp))
                parkRefs.forEachIndexed { index, ref ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = ref,
                            onValueChange = { parkRefs[index] = it.uppercase().take(10) },
                            label = { Text(stringResource(R.string.pota_park_reference_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            colors = textFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        if (parkRefs.size > 1) {
                            IconButton(onClick = { parkRefs.removeAt(index) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.pota_remove_park),
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    if (index < parkRefs.lastIndex) Spacer(Modifier.height(4.dp))
                }
                if (parkRefs.size < 10) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { parkRefs.add("") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.pota_add_park), color = TextPrimary, fontSize = 13.sp)
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.pota_max_parks_reached),
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(120) },
                    label = { Text(stringResource(R.string.pota_notes_optional_label)) },
                    singleLine = true,
                    colors = textFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val started = PotaSessionManager.start(parkRefs, notes.ifBlank { null })
                        if (started == null) {
                            Toast.makeText(context, context.getString(R.string.pota_enter_park_reference_first), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.pota_activating, started.parkRefsDisplay), Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgApp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.pota_start_activation), fontWeight = FontWeight.SemiBold) }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "activation-card") {
                ActiveActivationCard(
                    activation = activation!!,
                    onStop = {
                        PotaSessionManager.end()
                        Toast.makeText(context, context.getString(R.string.pota_activation_ended), Toast.LENGTH_SHORT).show()
                    },
                    onSelfSpot = {
                        val myCall = GeneralVariables.myCallsign ?: ""
                        if (myCall.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.pota_set_callsign_first), Toast.LENGTH_SHORT).show()
                        } else {
                            val refs = activation!!.parkRefs
                            scope.launch {
                                var successCount = 0
                                for (ref in refs) {
                                    val ok = PotaClient.selfSpot(
                                        activator = myCall,
                                        spotter = myCall,
                                        frequencyKhz = GeneralVariables.getBaseFrequency() / 1000.0,
                                        mode = "FT8",
                                        reference = ref,
                                        comments = "CQ POTA via FT8AF",
                                    )
                                    if (ok) successCount++
                                }
                                val msg = if (successCount == refs.size) {
                                    if (refs.size == 1) context.getString(R.string.pota_self_spot_posted)
                                    else context.getString(R.string.pota_spotted_n_parks, successCount)
                                } else {
                                    context.getString(R.string.pota_self_spot_failed)
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
            if (contacts.isNotEmpty()) {
                item(key = "contacts-header") {
                    Text(
                        stringResource(R.string.pota_qsos),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(contacts, key = { it.id }) { qso ->
                    PotaContactRow(qso)
                }
            }
        }
    }
}

@Composable
private fun ActiveActivationCard(
    activation: PotaActivation,
    onStop: () -> Unit,
    onSelfSpot: () -> Unit,
) {
    Card {
        val parks = activation.parkRefs
        if (parks.size <= 3) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.pota_status_active).uppercase(),
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                for (ref in parks) ParkPill(ref)
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.pota_status_active).uppercase(),
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                for (ref in parks.take(3)) ParkPill(ref)
                Text(
                    stringResource(R.string.pota_parks_more, parks.size - 3),
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatBlock(
                label = stringResource(R.string.pota_qsos),
                value = activation.qsoCount.toString(),
                hint = if (activation.qsoCount >= 10) stringResource(R.string.pota_valid_activation) else stringResource(R.string.pota_to_valid, 10 - activation.qsoCount),
                valueColor = if (activation.qsoCount >= 10) StatusConfirmed else Accent,
            )
            StatBlock(
                label = stringResource(R.string.pota_elapsed),
                value = formatElapsed(System.currentTimeMillis() - activation.startedAtMs),
                hint = stringResource(R.string.pota_since_start),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSelfSpot,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgApp),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.pota_self_spot), fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.pota_end_activation), color = TextPrimary) }
        }
        if (!activation.notes.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pota_notes_quote, activation.notes),
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.pota_tx_cq_preview, GeneralVariables.myCallsign ?: "", GeneralVariables.getMyMaidenhead4Grid()),
            color = TextMuted,
            fontSize = 11.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Contact row
// ---------------------------------------------------------------------------

@Composable
private fun PotaContactRow(qso: PotaQso) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrzAvatar(callsign = qso.callsign, size = 32.dp, fallbackText = qso.callsign.take(2))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    qso.callsign,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (qso.sigInfo != null) {
                    Spacer(Modifier.width(6.dp))
                    ParkPill(qso.sigInfo)
                }
            }
            Row {
                Text(
                    listOfNotNull(
                        qso.grid.ifBlank { null },
                        qso.band.ifBlank { null },
                    ).joinToString(" · "),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatQsoTime(qso.timeOn),
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${qso.rstRcvd} dB",
                color = Accent,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Hunt tab
// ---------------------------------------------------------------------------

@Composable
private fun HuntTab(mainViewModel: MainViewModel) {
    val spotsMap by PotaSpotsRepository.spotsByCall.collectAsStateWithLifecycle()
    val spots = remember(spotsMap) { spotsMap.values.sortedBy { it.frequencyKhz } }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (spots.isEmpty()) stringResource(R.string.pota_no_spots) else stringResource(R.string.pota_active_spots, spots.size),
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(spots, key = { "${it.activator}-${it.reference}-${it.frequencyKhz}" }) { spot ->
                SpotRow(spot = spot, onClick = {
                    // Open the QSO sheet pre-filled with this activator's callsign. Tuning the
                    // radio is the operator's job — most rigs aren't CAT-controlled by FT8AF
                    // for frequency changes during a session.
                    mainViewModel.qsoSheetCallsign.postValue(spot.activator)
                    Toast.makeText(
                        context,
                        context.getString(R.string.pota_targeting, spot.activator, "%.1f".format(spot.frequencyKhz), spot.reference),
                        Toast.LENGTH_LONG,
                    ).show()
                })
            }
        }
    }
}

@Composable
private fun SpotRow(spot: PotaSpot, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spot.activator,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                ParkPill(spot.reference)
            }
            if (spot.parkName.isNotBlank()) {
                Text(
                    spot.parkName + if (spot.locationDesc.isNotBlank()) " · ${spot.locationDesc}" else "",
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
            if (spot.comments.isNotBlank()) {
                Text(stringResource(R.string.pota_notes_quote, spot.comments), color = TextMuted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "%.1f kHz".format(spot.frequencyKhz),
            color = Accent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ---------------------------------------------------------------------------
// History tab
// ---------------------------------------------------------------------------

@Composable
private fun HistoryTab(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    var history by remember { mutableStateOf<List<PotaActivation>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val activation by PotaSessionManager.currentActivation.collectAsStateWithLifecycle()

    // Reload whenever activation state changes (start/end will appear here).
    LaunchedEffect(refreshKey, activation?.id, activation?.endedAtMs) {
        history = PotaSessionManager.history()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (history.isEmpty()) {
            Text(
                stringResource(R.string.pota_no_past_activations),
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(history, key = { it.id }) { row ->
                HistoryRow(
                    row = row,
                    onOpenUploadPage = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://pota.app/#/user/upload"))
                            context.startActivity(intent)
                        }.onFailure {
                            Toast.makeText(context, context.getString(R.string.pota_no_browser_available), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShareAdif = {
                        PotaAdifExporter.shareActivationAdif(context, mainViewModel, row) { ok ->
                            if (!ok) Toast.makeText(context, context.getString(R.string.pota_export_failed), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    row: PotaActivation,
    onOpenUploadPage: () -> Unit,
    onShareAdif: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<PotaQso>?>(null) }

    // Load contacts lazily on first expansion.
    LaunchedEffect(expanded) {
        if (expanded && contacts == null) {
            contacts = PotaSessionManager.getQsosForActivation(row)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    for (ref in row.parkRefs) ParkPill(ref)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (row.isActive) stringResource(R.string.pota_status_active) else stringResource(R.string.pota_qso_count, row.qsoCount),
                    color = if (row.isActive) Accent else TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDateRange(row), color = TextMuted, fontSize = 11.sp)
                if (row.qsoCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (!row.notes.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.pota_notes_quote, row.notes), color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onShareAdif, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.pota_share_adif), color = TextPrimary, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onOpenUploadPage, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.pota_open_pota_app), color = TextPrimary, fontSize = 12.sp)
            }
        }
        if (expanded && contacts != null) {
            Spacer(Modifier.height(8.dp))
            contacts!!.forEach { qso ->
                PotaContactRow(qso)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(12.dp))
            .border(1.dp, BorderStrong, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatBlock(label: String, value: String, hint: String, valueColor: Color = TextPrimary) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Text(value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(hint, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun ParkPill(ref: String) {
    Text(
        ref,
        color = StatusConfirmed,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(BgSurface2, RoundedCornerShape(6.dp))
            .border(1.dp, Border, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun textFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = BgSurface2,
    unfocusedContainerColor = BgSurface2,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextMuted,
    focusedIndicatorColor = Accent,
    unfocusedIndicatorColor = Border,
    cursorColor = Accent,
)

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatQsoTime(timeOn: String): String {
    if (timeOn.length < 4) return timeOn
    return "${timeOn.substring(0, 2)}:${timeOn.substring(2, 4)}z"
}

private fun formatDateRange(row: PotaActivation): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    val start = fmt.format(java.util.Date(row.startedAtMs))
    val end = row.endedAtMs?.let { fmt.format(java.util.Date(it)) } ?: "—"
    return "$start → $end"
}
