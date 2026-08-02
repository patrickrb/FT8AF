package radio.ks3ckc.ft8af.ui.rtota

import android.Manifest
import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8af.location.hasLocationPermission
import radio.ks3ckc.ft8af.rtota.BeaconReason
import radio.ks3ckc.ft8af.rtota.RtotaActivation
import radio.ks3ckc.ft8af.rtota.RtotaHttpException
import radio.ks3ckc.ft8af.rtota.activationMatchesNow
import radio.ks3ckc.ft8af.rtota.RTOTA_CQ_MODIFIER
import radio.ks3ckc.ft8af.rtota.RtotaClient
import radio.ks3ckc.ft8af.rtota.RtotaSettings
import radio.ks3ckc.ft8af.rtota.RtotaTripManager
import radio.ks3ckc.ft8af.rtota.RtotaTripState
import radio.ks3ckc.ft8af.rtota.SmartBeaconProfile
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow
import radio.ks3ckc.ft8af.ui.settings.SettingsDetailScaffold
import radio.ks3ckc.ft8af.ui.settings.SettingsSection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RTOTA trip mode screen: register with rtota.app, start and end a road trip,
 * watch what has reached the server, and announce a trip before it starts.
 *
 * The screen is a thin view over [RtotaTripManager] — it never touches GPS or
 * the queue itself, so closing it (or the whole app) has no effect on a trip in
 * progress. Everything long-running is reported through
 * [RtotaTripManager.state] rather than owned here.
 */
@Composable
fun RoadTripScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by RtotaTripManager.state.collectAsState()

    var enabled by remember { mutableStateOf(RtotaSettings.enabled) }
    var callsign by remember { mutableStateOf(RtotaSettings.callsign) }
    var baseUrl by remember { mutableStateOf(RtotaSettings.baseUrl) }
    var apiKey by remember { mutableStateOf(RtotaSettings.apiKey) }
    var privacy by remember { mutableStateOf(RtotaSettings.defaultPrivacy) }
    var tripName by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var showCallsignDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showTripNameDialog by remember { mutableStateOf(false) }
    var showPlanPicker by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf<List<RtotaActivation>>(emptyList()) }
    var plansLoading by remember { mutableStateOf(false) }
    var plansError by remember { mutableStateOf<String?>(null) }
    var showAnnounceDialog by remember { mutableStateOf(false) }

    // Trip tracking needs location; ask here rather than at the moment the user
    // taps Start, so a denied prompt doesn't silently produce a trip with no route.
    fun ensureLocationPermission(): Boolean {
        // Fine *or* coarse, matching what RtotaLocationTracker actually needs.
        // Gating on fine alone rejected an approximate-only grant — an ordinary
        // choice in the Android 12+ permission dialog — and re-prompted for a
        // permission the user had already given.
        val granted = hasLocationPermission(context)
        if (!granted) {
            (context as? Activity)?.let {
                ActivityCompat.requestPermissions(
                    it,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                    REQUEST_LOCATION,
                )
            }
        }
        return granted
    }

    if (showCallsignDialog) {
        RtotaTextDialog(
            title = stringResource(R.string.rtota_callsign),
            initial = callsign,
            onDismiss = { showCallsignDialog = false },
            onSave = {
                callsign = it.trim().uppercase(Locale.US)
                RtotaSettings.callsign = callsign
                showCallsignDialog = false
            },
        )
    }

    if (showServerDialog) {
        RtotaTextDialog(
            title = stringResource(R.string.rtota_server),
            initial = baseUrl,
            onDismiss = { showServerDialog = false },
            onSave = {
                RtotaSettings.baseUrl = it
                baseUrl = RtotaSettings.baseUrl
                showServerDialog = false
            },
        )
    }

    if (showKeyDialog) {
        RtotaTextDialog(
            title = stringResource(R.string.rtota_api_key),
            initial = apiKey,
            hint = stringResource(R.string.rtota_api_key_hint),
            onDismiss = { showKeyDialog = false },
            onSave = {
                apiKey = it.trim()
                RtotaSettings.apiKey = apiKey
                showKeyDialog = false
                // A key that arrives after a trip started offline unblocks the
                // whole backlog.
                RtotaTripManager.requestFlush("api-key-set")
            },
        )
    }

    if (showPlanPicker) {
        TripPlanPickerDialog(
            plans = plans,
            loading = plansLoading,
            error = plansError,
            nowMs = System.currentTimeMillis(),
            onDismiss = { showPlanPicker = false },
            onPick = { picked ->
                tripName = picked
                showPlanPicker = false
            },
            onTypeName = {
                showPlanPicker = false
                showTripNameDialog = true
            },
        )
    }

    if (showTripNameDialog) {
        RtotaTextDialog(
            title = stringResource(R.string.rtota_trip_name),
            initial = tripName,
            hint = stringResource(R.string.rtota_trip_name_hint),
            onDismiss = { showTripNameDialog = false },
            onSave = {
                tripName = it.trim()
                showTripNameDialog = false
            },
        )
    }

    if (showAnnounceDialog) {
        AnnounceActivationDialog(
            onDismiss = { showAnnounceDialog = false },
            onAnnounce = { title, hoursFromNow ->
                showAnnounceDialog = false
                busy = true
                scope.launch {
                    val start = System.currentTimeMillis() + hoursFromNow * 3_600_000L
                    val result =
                        RtotaClient.createActivation(
                            baseUrl = RtotaSettings.baseUrl,
                            apiKey = RtotaSettings.apiKey,
                            title = title,
                            startTimeMs = start,
                            privacy = privacy.takeIf { it.isNotBlank() },
                        )
                    busy = false
                    message =
                        result.fold(
                            onSuccess = { context.getString(R.string.rtota_announced) },
                            onFailure = { it.message ?: it.javaClass.simpleName },
                        )
                }
            },
        )
    }

    SettingsDetailScaffold(title = stringResource(R.string.rtota_title), onBack = onBack) {
        // =====================================================================
        // ACCOUNT
        // =====================================================================
        SettingsSection(title = stringResource(R.string.rtota_section_account)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.rtota_enable),
                        description = stringResource(R.string.rtota_enable_desc),
                        toggle = enabled,
                        onToggleChange = { checked ->
                            enabled = checked
                            RtotaSettings.enabled = checked
                            if (checked) RtotaTripManager.init(context)
                        },
                    )
                    SettingsRow(
                        label = stringResource(R.string.rtota_callsign),
                        value =
                            callsign.ifBlank { GeneralVariables.myCallsign.orEmpty() }
                                .ifBlank { "--" },
                        showChevron = true,
                        onClick = { showCallsignDialog = true },
                    )
                    SettingsRow(
                        label = stringResource(R.string.rtota_server),
                        value = baseUrl.removePrefix("https://"),
                        showChevron = true,
                        onClick = { showServerDialog = true },
                    )
                    SettingsRow(
                        label = stringResource(R.string.rtota_api_key),
                        value =
                            if (apiKey.isBlank()) {
                                stringResource(R.string.common_not_configured)
                            } else {
                                maskKey(apiKey)
                            },
                        showChevron = true,
                        onClick = { showKeyDialog = true },
                    )
                    if (apiKey.isBlank()) {
                        SettingsRow(
                            label =
                                if (busy) {
                                    stringResource(R.string.rtota_registering)
                                } else {
                                    stringResource(R.string.rtota_register)
                                },
                            description = stringResource(R.string.rtota_register_desc),
                            showChevron = true,
                            onClick = {
                                if (busy) return@SettingsRow
                                busy = true
                                message = null
                                scope.launch {
                                    val result =
                                        RtotaClient.registerOperator(
                                            baseUrl = RtotaSettings.baseUrl,
                                            callsign =
                                                callsign.ifBlank {
                                                    GeneralVariables.myCallsign.orEmpty()
                                                },
                                            homeGrid = GeneralVariables.getMyMaidenheadGrid(),
                                        )
                                    busy = false
                                    result.fold(
                                        onSuccess = { key ->
                                            apiKey = key
                                            RtotaSettings.apiKey = key
                                            message = context.getString(R.string.rtota_registered)
                                        },
                                        onFailure = {
                                            message = it.message ?: it.javaClass.simpleName
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
            message?.let { NoticeText(it) }
        }

        // =====================================================================
        // TRIP
        // =====================================================================
        SettingsSection(title = stringResource(R.string.rtota_section_trip)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                if (state.active) {
                    ActiveTripCard(
                        state = state,
                        onEnd = { RtotaTripManager.endTrip() },
                        onAbandon = { RtotaTripManager.abandonTrip() },
                    )
                } else {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.rtota_trip_name),
                            value = tripName.ifBlank { "--" },
                            showChevron = true,
                            onClick = {
                                // Straight to the free-text box when there is no key —
                                // the plans live behind it, and an empty picker with an
                                // auth error in it explains nothing.
                                if (RtotaSettings.apiKey.isBlank()) {
                                    showTripNameDialog = true
                                    return@SettingsRow
                                }
                                showPlanPicker = true
                                plansLoading = true
                                plansError = null
                                scope.launch {
                                    RtotaClient.fetchMyActivations(
                                        RtotaSettings.baseUrl,
                                        RtotaSettings.apiKey,
                                    ).fold(
                                        onSuccess = { plans = it },
                                        onFailure = { e ->
                                            plansError =
                                                (e as? RtotaHttpException)?.serverMessage
                                                    ?: e.message
                                                    ?: e.javaClass.simpleName
                                        },
                                    )
                                    plansLoading = false
                                }
                            },
                        )
                        SettingsRow(
                            label = stringResource(R.string.rtota_privacy),
                            value =
                                privacy.ifBlank {
                                    stringResource(R.string.rtota_privacy_default)
                                },
                            onClick = {
                                // Cycle: account default -> public -> delayed ->
                                // followers -> private -> back to default.
                                privacy = nextPrivacy(privacy)
                                RtotaSettings.defaultPrivacy = privacy
                            },
                        )
                        SettingsRow(
                            label = stringResource(R.string.rtota_start_trip),
                            showChevron = true,
                            onClick = {
                                when {
                                    RtotaSettings.apiKey.isBlank() ->
                                        message = context.getString(R.string.rtota_need_key)
                                    !ensureLocationPermission() ->
                                        message = context.getString(R.string.rtota_need_location)
                                    else -> {
                                        if (!RtotaSettings.enabled) {
                                            RtotaSettings.enabled = true
                                            enabled = true
                                        }
                                        RtotaTripManager.init(context)
                                        RtotaTripManager.startTrip(
                                            name = tripName,
                                            privacy = privacy.takeIf { it.isNotBlank() },
                                        )
                                        message = null
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        // =====================================================================
        // TRACKING (SmartBeaconing)
        // =====================================================================
        SettingsSection(title = stringResource(R.string.rtota_section_tracking)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                // Read-only: the sampling is tuned for a vehicle and there is
                // nothing here worth a knob. Stating what it does still matters —
                // a trail that goes quiet at a fuel stop looks broken otherwise.
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.rtota_beacon_heading),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = describeProfile(SmartBeaconProfile.DEFAULT),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // =====================================================================
        // ANNOUNCE
        // =====================================================================
        SettingsSection(title = stringResource(R.string.rtota_section_activation)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    label = stringResource(R.string.rtota_announce),
                    description = stringResource(R.string.rtota_announce_desc),
                    showChevron = true,
                    onClick = {
                        if (RtotaSettings.apiKey.isBlank()) {
                            message = context.getString(R.string.rtota_need_key)
                        } else {
                            showAnnounceDialog = true
                        }
                    },
                )
            }
        }
    }
}

private const val REQUEST_LOCATION = 4201

/** Live trip readout: what has been recorded, what is still waiting. */
@Composable
private fun ActiveTripCard(
    state: RtotaTripState,
    onEnd: () -> Unit,
    onAbandon: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = state.tripName.ifBlank { stringResource(R.string.rtota_service_title) },
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        StatLine(
            stringResource(R.string.rtota_stat_miles),
            String.format(Locale.US, "%.1f", state.miles),
        )
        StatLine(
            stringResource(R.string.rtota_stat_qsos),
            "${state.sentQsos}",
        )
        StatLine(
            stringResource(R.string.rtota_stat_pending),
            "${state.pendingPoints + state.pendingQsos}",
        )
        StatLine(
            stringResource(R.string.rtota_stat_last_upload),
            if (state.lastUploadMs > 0) {
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(state.lastUploadMs))
            } else {
                stringResource(R.string.rtota_stat_never)
            },
        )
        // Why the last route point was kept — makes SmartBeaconing legible from
        // the passenger seat ("corner" on a curve, "parked" at a fuel stop).
        StatLine(
            stringResource(R.string.rtota_stat_last_point),
            when {
                state.parked -> stringResource(R.string.rtota_beacon_parked)
                else -> stringResource(beaconReasonLabelRes(state.lastBeaconReason))
            },
        )
        // Spelled out because it is not the spelling anyone expects: an FT8 CQ
        // modifier is at most four letters, so the trip calls RTOA, not RTOTA.
        StatLine(
            stringResource(R.string.rtota_stat_cq),
            "CQ $RTOTA_CQ_MODIFIER",
        )
        if (state.pendingCreate) {
            Text(
                text = stringResource(R.string.rtota_pending_create),
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
        state.lastError?.let {
            Text(
                text = stringResource(R.string.rtota_error, it),
                color = StatusBad,
                fontSize = 12.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onEnd) {
                Text(stringResource(R.string.rtota_end_trip), color = Accent)
            }
            TextButton(onClick = onAbandon) {
                Text(stringResource(R.string.rtota_abandon), color = TextMuted)
            }
        }
    }
}

@Composable
private fun StatLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = TextMuted, fontSize = 13.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
        )
    }
}

@Composable
private fun NoticeText(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/**
 * Pick the trip's name from the plans already saved on rtota.app, or type one.
 *
 * The plans are *scheduled activations* — what the site's plan wizard writes —
 * because a trip only exists once someone drives it. Choosing one here does not
 * bind anything: the server decides which plan a trip fulfils by comparing start
 * times (±12 h), so the value of picking is that the name matches the plan and
 * the operator can see, before setting off, whether starting now will inherit
 * the privacy they chose in the wizard. A plan outside that window is still
 * listed — driving early is normal — just marked so the inheritance isn't a
 * surprise.
 */
@Composable
private fun TripPlanPickerDialog(
    plans: List<RtotaActivation>,
    loading: Boolean,
    error: String?,
    nowMs: Long,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onTypeName: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgSurface2)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.rtota_pick_plan),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )

            when {
                loading ->
                    Text(
                        text = stringResource(R.string.rtota_pick_plan_loading),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                error != null ->
                    // Not rtota_error ("Last error: …") — that phrasing belongs to the
                    // trip's own upload failures, and reusing it here would report a
                    // failed plan fetch as though the running trip were in trouble.
                    Text(
                        text = stringResource(R.string.rtota_pick_plan_error, error),
                        color = StatusBad,
                        fontSize = 13.sp,
                    )
                plans.isEmpty() ->
                    Text(
                        text = stringResource(R.string.rtota_pick_plan_empty),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                else ->
                    // Bounded and scrollable: a rover who plans a season of trips can
                    // have a long list, and an unconstrained Column inside a Dialog
                    // simply runs off the bottom of a small screen — the rows below the
                    // fold become unreachable, which on a *picker* means unselectable.
                    // Height-capped rather than a LazyColumn so the dialog still hugs a
                    // short list instead of always claiming the cap.
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        plans.forEach { plan ->
                            val matches = activationMatchesNow(plan, nowMs)
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onPick(plan.title) }
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                            ) {
                                Text(text = plan.title, color = TextPrimary, fontSize = 15.sp)
                                Text(
                                    text =
                                        if (matches) {
                                            stringResource(R.string.rtota_plan_matches_now)
                                        } else {
                                            stringResource(
                                                R.string.rtota_plan_starts,
                                                formatPlanStart(plan.startTimeMs),
                                            )
                                        },
                                    color = if (matches) Accent else TextFaint,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(onClick = onTypeName) {
                    Text(stringResource(R.string.rtota_pick_plan_custom), color = Accent)
                }
            }
        }
    }
}

/** Local-time "Aug 4, 11:00" for a plan's departure, so it reads as the operator's clock. */
internal fun formatPlanStart(startMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(startMs))

/** Single-field dialog shared by the callsign / server / key / trip-name rows. */
@Composable
private fun RtotaTextDialog(
    title: String,
    initial: String,
    hint: String? = null,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var input by remember { mutableStateOf(initial) }
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
            Text(text = title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = hint?.let { { Text(it, color = TextFaint) } },
                singleLine = true,
                colors = rtotaFieldColors(),
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
                TextButton(onClick = { onSave(input) }) {
                    Text(stringResource(R.string.action_save), color = Accent)
                }
            }
        }
    }
}

/** Title + "starting in N hours" — enough to put a plan on the map. */
@Composable
private fun AnnounceActivationDialog(
    onDismiss: () -> Unit,
    onAnnounce: (title: String, hoursFromNow: Long) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("24") }
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
                text = stringResource(R.string.rtota_announce),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.rtota_announce_title_field)) },
                singleLine = true,
                colors = rtotaFieldColors(),
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hours,
                onValueChange = { hours = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.rtota_announce_hours)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = rtotaFieldColors(),
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        if (title.isNotBlank()) {
                            onAnnounce(title, hours.toLongOrNull()?.coerceIn(0, 8760) ?: 24L)
                        }
                    },
                ) {
                    Text(stringResource(R.string.rtota_announce_send), color = Accent)
                }
            }
        }
    }
}

@Composable
private fun rtotaFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

// ---------------------------------------------------------------------------
// Small pure helpers (unit-tested)
// ---------------------------------------------------------------------------

/**
 * Show enough of the key to recognise it without printing a working credential
 * on a screen that may be mirrored to a car display.
 */
internal fun maskKey(key: String): String = if (key.length <= 8) "••••" else key.take(6) + "…" + key.takeLast(4)

/** Privacy cycle for the tap-to-change row; "" means "let the account decide". */
internal fun nextPrivacy(current: String): String {
    val order = listOf("") + RtotaSettings.PRIVACY_LEVELS
    val idx = order.indexOf(current).takeIf { it >= 0 } ?: 0
    return order[(idx + 1) % order.size]
}

@StringRes
internal fun beaconReasonLabelRes(reason: BeaconReason?): Int =
    when (reason) {
        BeaconReason.FIRST -> R.string.rtota_beacon_first
        BeaconReason.RATE -> R.string.rtota_beacon_rate
        BeaconReason.CORNER -> R.string.rtota_beacon_corner
        BeaconReason.RESUME -> R.string.rtota_beacon_resume
        BeaconReason.QSO -> R.string.rtota_beacon_qso
        null -> R.string.rtota_beacon_waiting
    }

/**
 * Plain-language summary of what the sampler will actually do, so the numbers
 * behind SmartBeaconing are visible without an options screen full of them.
 */
internal fun describeProfile(profile: SmartBeaconProfile): String =
    buildString {
        append("A point every ")
        append(profile.fastRateSec)
        append(" s above ")
        append(profile.fastSpeedMph.toInt())
        append(" mph, stretching to ")
        append(profile.slowRateSec / 60)
        append(" min when crawling; extra points through turns sharper than ")
        append(profile.minTurnAngleDeg.toInt())
        append("° + ")
        append(profile.turnSlope.toInt())
        append("/mph. Nothing at all while parked.")
    }
