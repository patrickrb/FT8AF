package radio.ks3ckc.ft8af.ui.rota

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
import radio.ks3ckc.ft8af.rota.BeaconReason
import radio.ks3ckc.ft8af.rota.RotaPlannedTrip
import radio.ks3ckc.ft8af.rota.RotaHttpException
import radio.ks3ckc.ft8af.rota.ROTA_CQ_MODIFIER
import radio.ks3ckc.ft8af.rota.RotaClient
import radio.ks3ckc.ft8af.rota.RotaSettings
import radio.ks3ckc.ft8af.rota.RotaTripManager
import radio.ks3ckc.ft8af.rota.RotaTripState
import radio.ks3ckc.ft8af.rota.SmartBeaconProfile
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow
import radio.ks3ckc.ft8af.ui.settings.SettingsDetailScaffold
import radio.ks3ckc.ft8af.ui.settings.SettingsSection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ROTA trip mode screen: register with roadsontheair.com, start and end a road trip,
 * watch what has reached the server, and announce a trip before it starts.
 *
 * The screen is a thin view over [RotaTripManager] — it never touches GPS or
 * the queue itself, so closing it (or the whole app) has no effect on a trip in
 * progress. Everything long-running is reported through
 * [RotaTripManager.state] rather than owned here.
 */
@Composable
fun RoadTripScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by RotaTripManager.state.collectAsState()

    var enabled by remember { mutableStateOf(RotaSettings.enabled) }
    var callsign by remember { mutableStateOf(RotaSettings.callsign) }
    var baseUrl by remember { mutableStateOf(RotaSettings.baseUrl) }
    var apiKey by remember { mutableStateOf(RotaSettings.apiKey) }
    var privacy by remember { mutableStateOf(RotaSettings.defaultPrivacy) }
    var tripName by remember { mutableStateOf("") }
    // The announced trip this drive fulfils, when one was picked. Held so Start
    // promotes that row rather than creating a second trip beside it.
    var tripPlanId by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var showCallsignDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showTripNameDialog by remember { mutableStateOf(false) }
    var showPlanPicker by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf<List<RotaPlannedTrip>>(emptyList()) }
    var plansLoading by remember { mutableStateOf(false) }
    var plansError by remember { mutableStateOf<String?>(null) }
    var showAnnounceDialog by remember { mutableStateOf(false) }
    var showSsbLogDialog by remember { mutableStateOf(false) }

    // Trip tracking needs location; ask here rather than at the moment the user
    // taps Start, so a denied prompt doesn't silently produce a trip with no route.
    fun ensureLocationPermission(): Boolean {
        // Fine *or* coarse, matching what RotaLocationTracker actually needs.
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
        RotaTextDialog(
            title = stringResource(R.string.rota_callsign),
            initial = callsign,
            onDismiss = { showCallsignDialog = false },
            onSave = {
                callsign = it.trim().uppercase(Locale.US)
                RotaSettings.callsign = callsign
                showCallsignDialog = false
            },
        )
    }

    if (showServerDialog) {
        RotaTextDialog(
            title = stringResource(R.string.rota_server),
            initial = baseUrl,
            onDismiss = { showServerDialog = false },
            onSave = {
                RotaSettings.baseUrl = it
                baseUrl = RotaSettings.baseUrl
                showServerDialog = false
            },
        )
    }

    if (showKeyDialog) {
        RotaTextDialog(
            title = stringResource(R.string.rota_api_key),
            initial = apiKey,
            hint = stringResource(R.string.rota_api_key_hint),
            onDismiss = { showKeyDialog = false },
            onSave = {
                apiKey = it.trim()
                RotaSettings.apiKey = apiKey
                showKeyDialog = false
                // A key that arrives after a trip started offline unblocks the
                // whole backlog.
                RotaTripManager.requestFlush("api-key-set")
            },
        )
    }

    if (showPlanPicker) {
        TripPlanPickerDialog(
            plans = plans,
            loading = plansLoading,
            error = plansError,
            onDismiss = { showPlanPicker = false },
            onPick = { picked ->
                tripName = picked.name
                tripPlanId = picked.id
                showPlanPicker = false
            },
            onTypeName = {
                showPlanPicker = false
                showTripNameDialog = true
            },
        )
    }

    if (showTripNameDialog) {
        RotaTextDialog(
            title = stringResource(R.string.rota_trip_name),
            initial = tripName,
            hint = stringResource(R.string.rota_trip_name_hint),
            onDismiss = { showTripNameDialog = false },
            onSave = {
                tripName = it.trim()
                // A hand-typed name is a different trip from the plan that was
                // picked; keeping the id would start that announcement under a
                // name its followers never saw.
                tripPlanId = ""
                showTripNameDialog = false
            },
        )
    }

    if (showSsbLogDialog) {
        LogSsbQsoDialog(
            onDismiss = { showSsbLogDialog = false },
            onLogged = { loggedCall ->
                showSsbLogDialog = false
                message = context.getString(R.string.rota_ssb_logged, loggedCall)
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
                        RotaClient.announceTrip(
                            baseUrl = RotaSettings.baseUrl,
                            apiKey = RotaSettings.apiKey,
                            name = title,
                            startTimeMs = start,
                            privacy = privacy.takeIf { it.isNotBlank() },
                        )
                    busy = false
                    message =
                        result.fold(
                            onSuccess = { context.getString(R.string.rota_announced) },
                            onFailure = { it.message ?: it.javaClass.simpleName },
                        )
                }
            },
        )
    }

    SettingsDetailScaffold(title = stringResource(R.string.rota_title), onBack = onBack) {
        // =====================================================================
        // ACCOUNT
        // =====================================================================
        SettingsSection(title = stringResource(R.string.rota_section_account)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.rota_enable),
                        description = stringResource(R.string.rota_enable_desc),
                        toggle = enabled,
                        onToggleChange = { checked ->
                            enabled = checked
                            RotaSettings.enabled = checked
                            if (checked) RotaTripManager.init(context)
                        },
                    )
                    SettingsRow(
                        label = stringResource(R.string.rota_callsign),
                        value =
                            callsign.ifBlank { GeneralVariables.myCallsign.orEmpty() }
                                .ifBlank { "--" },
                        showChevron = true,
                        onClick = { showCallsignDialog = true },
                    )
                    SettingsRow(
                        label = stringResource(R.string.rota_server),
                        value = baseUrl.removePrefix("https://"),
                        showChevron = true,
                        onClick = { showServerDialog = true },
                    )
                    SettingsRow(
                        label = stringResource(R.string.rota_api_key),
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
                                    stringResource(R.string.rota_registering)
                                } else {
                                    stringResource(R.string.rota_register)
                                },
                            description = stringResource(R.string.rota_register_desc),
                            showChevron = true,
                            onClick = {
                                if (busy) return@SettingsRow
                                busy = true
                                message = null
                                scope.launch {
                                    val result =
                                        RotaClient.registerOperator(
                                            baseUrl = RotaSettings.baseUrl,
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
                                            RotaSettings.apiKey = key
                                            message = context.getString(R.string.rota_registered)
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
        SettingsSection(title = stringResource(R.string.rota_section_trip)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                if (state.active) {
                    ActiveTripCard(
                        state = state,
                        onLogSsb = { showSsbLogDialog = true },
                        onEnd = { RotaTripManager.endTrip() },
                        onAbandon = { RotaTripManager.abandonTrip() },
                    )
                } else {
                    Column {
                        SettingsRow(
                            label = stringResource(R.string.rota_trip_name),
                            value = tripName.ifBlank { "--" },
                            showChevron = true,
                            onClick = {
                                // Straight to the free-text box when there is no key —
                                // the plans live behind it, and an empty picker with an
                                // auth error in it explains nothing.
                                if (RotaSettings.apiKey.isBlank()) {
                                    showTripNameDialog = true
                                    return@SettingsRow
                                }
                                showPlanPicker = true
                                plansLoading = true
                                plansError = null
                                scope.launch {
                                    RotaClient.fetchMyPlannedTrips(
                                        RotaSettings.baseUrl,
                                        RotaSettings.apiKey,
                                    ).fold(
                                        onSuccess = { plans = it },
                                        onFailure = { e ->
                                            plansError =
                                                (e as? RotaHttpException)?.serverMessage
                                                    ?: e.message
                                                    ?: e.javaClass.simpleName
                                        },
                                    )
                                    plansLoading = false
                                }
                            },
                        )
                        SettingsRow(
                            label = stringResource(R.string.rota_privacy),
                            value =
                                privacy.ifBlank {
                                    stringResource(R.string.rota_privacy_default)
                                },
                            onClick = {
                                // Cycle: account default -> public -> delayed ->
                                // followers -> private -> back to default.
                                privacy = nextPrivacy(privacy)
                                RotaSettings.defaultPrivacy = privacy
                            },
                        )
                        SettingsRow(
                            label = stringResource(R.string.rota_start_trip),
                            showChevron = true,
                            onClick = {
                                when {
                                    RotaSettings.apiKey.isBlank() ->
                                        message = context.getString(R.string.rota_need_key)
                                    !ensureLocationPermission() ->
                                        message = context.getString(R.string.rota_need_location)
                                    else -> {
                                        if (!RotaSettings.enabled) {
                                            RotaSettings.enabled = true
                                            enabled = true
                                        }
                                        RotaTripManager.init(context)
                                        RotaTripManager.startTrip(
                                            name = tripName,
                                            privacy = privacy.takeIf { it.isNotBlank() },
                                            planId = tripPlanId.takeIf { it.isNotEmpty() },
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
        SettingsSection(title = stringResource(R.string.rota_section_tracking)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                // Read-only: the sampling is tuned for a vehicle and there is
                // nothing here worth a knob. Stating what it does still matters —
                // a trail that goes quiet at a fuel stop looks broken otherwise.
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.rota_beacon_heading),
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
        SettingsSection(title = stringResource(R.string.rota_section_activation)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    label = stringResource(R.string.rota_announce),
                    description = stringResource(R.string.rota_announce_desc),
                    showChevron = true,
                    onClick = {
                        if (RotaSettings.apiKey.isBlank()) {
                            message = context.getString(R.string.rota_need_key)
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
    state: RotaTripState,
    onLogSsb: () -> Unit,
    onEnd: () -> Unit,
    onAbandon: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = state.tripName.ifBlank { stringResource(R.string.rota_service_title) },
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        StatLine(
            stringResource(R.string.rota_stat_miles),
            String.format(Locale.US, "%.1f", state.miles),
        )
        StatLine(
            stringResource(R.string.rota_stat_qsos),
            "${state.sentQsos}",
        )
        StatLine(
            stringResource(R.string.rota_stat_pending),
            "${state.pendingPoints + state.pendingQsos}",
        )
        StatLine(
            stringResource(R.string.rota_stat_last_upload),
            if (state.lastUploadMs > 0) {
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(state.lastUploadMs))
            } else {
                stringResource(R.string.rota_stat_never)
            },
        )
        // Why the last route point was kept — makes SmartBeaconing legible from
        // the passenger seat ("corner" on a curve, "parked" at a fuel stop).
        StatLine(
            stringResource(R.string.rota_stat_last_point),
            when {
                state.parked -> stringResource(R.string.rota_beacon_parked)
                else -> stringResource(beaconReasonLabelRes(state.lastBeaconReason))
            },
        )
        // Shown verbatim so the operator can see exactly what is going out.
        StatLine(
            stringResource(R.string.rota_stat_cq),
            "CQ $ROTA_CQ_MODIFIER",
        )
        if (state.pendingCreate) {
            Text(
                text = stringResource(R.string.rota_pending_create),
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
        state.lastError?.let {
            Text(
                text = stringResource(R.string.rota_error, it),
                color = StatusBad,
                fontSize = 12.sp,
            )
        }
        // Its own row, above End/Discard: this is the button a driver reaches
        // for mid-trip, and sitting beside "End trip" invites a fat-finger that
        // closes the whole trip instead of logging a contact.
        TextButton(onClick = onLogSsb) {
            Text(stringResource(R.string.rota_log_ssb), color = Accent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onEnd) {
                Text(stringResource(R.string.rota_end_trip), color = Accent)
            }
            TextButton(onClick = onAbandon) {
                Text(stringResource(R.string.rota_abandon), color = TextMuted)
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
 * Pick the trip to drive from the ones already announced on roadsontheair.com,
 * or type a name for an unannounced one.
 *
 * Picking binds: the announced trip *is* the trip, held server-side at
 * `planned`, and Start promotes that row. So its privacy — the delay, the route
 * trim, the replay lock chosen in the wizard — applies to this drive by
 * construction. It used to be a name-only convenience, with the server guessing
 * which plan a new trip fulfilled by comparing departure times within ±12 h;
 * driving outside that window silently fell back to the account default. There
 * is nothing left to warn about, so the rows just show when each one departs.
 */
@Composable
private fun TripPlanPickerDialog(
    plans: List<RotaPlannedTrip>,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPick: (RotaPlannedTrip) -> Unit,
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
                text = stringResource(R.string.rota_pick_plan),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )

            when {
                loading ->
                    Text(
                        text = stringResource(R.string.rota_pick_plan_loading),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                error != null ->
                    // Not rota_error ("Last error: …") — that phrasing belongs to the
                    // trip's own upload failures, and reusing it here would report a
                    // failed plan fetch as though the running trip were in trouble.
                    Text(
                        text = stringResource(R.string.rota_pick_plan_error, error),
                        color = StatusBad,
                        fontSize = 13.sp,
                    )
                plans.isEmpty() ->
                    Text(
                        text = stringResource(R.string.rota_pick_plan_empty),
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
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onPick(plan) }
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                            ) {
                                Text(text = plan.name, color = TextPrimary, fontSize = 15.sp)
                                Text(
                                    text =
                                        stringResource(
                                            R.string.rota_plan_starts,
                                            formatPlanStart(plan.startTimeMs),
                                        ),
                                    color = TextFaint,
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
                    Text(stringResource(R.string.rota_pick_plan_custom), color = Accent)
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
private fun RotaTextDialog(
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
                text = stringResource(R.string.rota_announce),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.rota_announce_title_field)) },
                singleLine = true,
                colors = rotaFieldColors(),
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hours,
                onValueChange = { hours = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.rota_announce_hours)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = rotaFieldColors(),
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
                    Text(stringResource(R.string.rota_announce_send), color = Accent)
                }
            }
        }
    }
}

@Composable
internal fun rotaFieldColors() =
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
 *
 * The head is cut at the prefix separator rather than a fixed width, because the
 * server mints `rota_…` now and `rtota_…` before the rename — the two differ by a
 * character, and a hardcoded width would expose a character of the secret itself
 * on whichever one it was not sized for.
 */
internal fun maskKey(key: String): String {
    if (key.length <= 8) return "••••"
    val afterPrefix = key.indexOf('_') + 1
    val head = if (afterPrefix in 1..8) key.take(afterPrefix) else key.take(6)
    return head + "…" + key.takeLast(4)
}

/** Privacy cycle for the tap-to-change row; "" means "let the account decide". */
internal fun nextPrivacy(current: String): String {
    val order = listOf("") + RotaSettings.PRIVACY_LEVELS
    val idx = order.indexOf(current).takeIf { it >= 0 } ?: 0
    return order[(idx + 1) % order.size]
}

@StringRes
internal fun beaconReasonLabelRes(reason: BeaconReason?): Int =
    when (reason) {
        BeaconReason.FIRST -> R.string.rota_beacon_first
        BeaconReason.RATE -> R.string.rota_beacon_rate
        BeaconReason.CORNER -> R.string.rota_beacon_corner
        BeaconReason.RESUME -> R.string.rota_beacon_resume
        BeaconReason.QSO -> R.string.rota_beacon_qso
        null -> R.string.rota_beacon_waiting
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
