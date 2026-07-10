package radio.ks3ckc.ft8af.ui.settings

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.serialport.UsbSerialProber
import com.k1af.ft8af.wave.UsbAudioDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.GlassCard

// ---------------------------------------------------------------------------
// Pure diagnostic logic (unit-tested — kept free of Android/Compose runtime types)
// ---------------------------------------------------------------------------

/** Default CAT vendor id the wired-serial path matches on (ICOM), see CableSerialPort. */
internal const val DEFAULT_CAT_VENDOR_ID = 0x0c26

/**
 * Outcome of a single diagnostic check. [PASS]/[FAIL] render a coloured tick/cross;
 * [INFO] is a neutral value-only row (used for the Vendor/Product ID readouts, which
 * are data, not a pass/fail).
 */
internal enum class DiagnosticStatus { PASS, FAIL, INFO }

/** One row in the diagnostics list: a label, a status, and an optional detail value. */
internal data class UsbDiagnosticItem(
    val labelRes: Int,
    val status: DiagnosticStatus,
    val value: String? = null,
)

/** A single attached USB device reduced to the facts the diagnostics care about. */
internal data class UsbDeviceSummary(
    val vendorId: Int,
    val productId: Int,
    val hasSerialDriver: Boolean,
    val hasAudioInterface: Boolean,
    val hasPermission: Boolean,
)

/** The full set of USB/CAT facts a single diagnostics refresh gathers. */
internal data class UsbDiagnosticsData(
    val deviceFound: Boolean,
    val vendorId: Int?,
    val productId: Int?,
    val hasPermission: Boolean,
    val cdcSerialFound: Boolean,
    val audioDeviceFound: Boolean,
    val portOpen: Boolean,
    val catResponded: Boolean,
)

/** Formats a USB id as the conventional 16-bit hex (e.g. 0x0C26); null passes through. */
internal fun formatUsbId(id: Int?): String? =
    id?.let { String.format("0x%04X", it and 0xFFFF) }

/**
 * Picks which attached device the diagnostics describe. Prefers one a serial driver
 * can drive (that's the rig's CAT port), then one matching the default CAT vendor id,
 * then one exposing a USB-audio interface, then simply the first device. Returns null
 * when nothing is attached.
 */
internal fun selectDiagnosticDevice(devices: List<UsbDeviceSummary>): UsbDeviceSummary? =
    devices.firstOrNull { it.hasSerialDriver }
        ?: devices.firstOrNull { it.vendorId == DEFAULT_CAT_VENDOR_ID }
        ?: devices.firstOrNull { it.hasAudioInterface }
        ?: devices.firstOrNull()

/**
 * Collapses the raw per-device facts (plus the rig-link/CAT signals read from the
 * ViewModel) into the flat [UsbDiagnosticsData] the row builder consumes. Pure so it
 * can be exercised without a UsbManager.
 */
internal fun assembleUsbDiagnosticsData(
    devices: List<UsbDeviceSummary>,
    portOpen: Boolean,
    catResponded: Boolean,
): UsbDiagnosticsData {
    val target = selectDiagnosticDevice(devices)
    return UsbDiagnosticsData(
        deviceFound = target != null,
        vendorId = target?.vendorId,
        productId = target?.productId,
        hasPermission = target?.hasPermission == true,
        cdcSerialFound = devices.any { it.hasSerialDriver },
        audioDeviceFound = devices.any { it.hasAudioInterface },
        portOpen = portOpen,
        catResponded = catResponded,
    )
}

/**
 * Maps gathered facts to the ordered list of display rows, matching the layout in the
 * task: device found, VID, PID, permission, CDC serial, audio, port open, CAT response.
 * The VID/PID rows are informational (no tick/cross) and only carry a value once a
 * device is actually found.
 */
internal fun buildUsbDiagnostics(data: UsbDiagnosticsData): List<UsbDiagnosticItem> {
    fun passFail(ok: Boolean) = if (ok) DiagnosticStatus.PASS else DiagnosticStatus.FAIL
    return listOf(
        UsbDiagnosticItem(R.string.usb_diag_device_found, passFail(data.deviceFound)),
        UsbDiagnosticItem(
            R.string.usb_diag_vendor_id,
            DiagnosticStatus.INFO,
            if (data.deviceFound) formatUsbId(data.vendorId) else null,
        ),
        UsbDiagnosticItem(
            R.string.usb_diag_product_id,
            DiagnosticStatus.INFO,
            if (data.deviceFound) formatUsbId(data.productId) else null,
        ),
        UsbDiagnosticItem(R.string.usb_diag_permission, passFail(data.hasPermission)),
        UsbDiagnosticItem(R.string.usb_diag_cdc_serial, passFail(data.cdcSerialFound)),
        UsbDiagnosticItem(R.string.usb_diag_audio_device, passFail(data.audioDeviceFound)),
        UsbDiagnosticItem(R.string.usb_diag_port_open, passFail(data.portOpen)),
        UsbDiagnosticItem(R.string.usb_diag_cat_response, passFail(data.catResponded)),
    )
}

/** Glyph shown for a status; INFO rows carry no glyph (value only). */
internal fun diagnosticStatusGlyph(status: DiagnosticStatus): String = when (status) {
    DiagnosticStatus.PASS -> "✔"
    DiagnosticStatus.FAIL -> "✖"
    DiagnosticStatus.INFO -> ""
}

/** Colour for a status glyph: green = pass, red = fail. INFO has no glyph so is neutral. */
internal fun diagnosticStatusColor(status: DiagnosticStatus): Color = when (status) {
    DiagnosticStatus.PASS -> StatusConfirmed
    DiagnosticStatus.FAIL -> StatusBad
    DiagnosticStatus.INFO -> TextMuted
}

/** The TalkBack status-res for a status, or null for INFO (nothing to announce). */
internal fun diagnosticStatusContentDescriptionRes(status: DiagnosticStatus): Int? = when (status) {
    DiagnosticStatus.PASS -> R.string.usb_diag_status_pass
    DiagnosticStatus.FAIL -> R.string.usb_diag_status_fail
    DiagnosticStatus.INFO -> null
}

/**
 * The value string shown on a row: the row's own value when it has one, a dash
 * placeholder for INFO rows with no value (VID/PID before a device is attached), and
 * nothing for PASS/FAIL rows (their glyph is the whole story). [noneValue] is the
 * localized "—".
 */
internal fun resolveDiagnosticDisplayValue(item: UsbDiagnosticItem, noneValue: String): String? =
    when {
        item.value != null -> item.value
        item.status == DiagnosticStatus.INFO -> noneValue
        else -> null
    }

/**
 * TalkBack description for a row. PASS/FAIL announce "<label>, <pass|fail>"; INFO rows
 * (no spoken status) announce "<label>, <value>" so the VID/PID readout is still spoken.
 * [statusWord] is the localized "pass"/"fail", or null for INFO.
 */
internal fun buildRowContentDescription(label: String, statusWord: String?, value: String?): String =
    if (statusWord != null) {
        "$label, $statusWord"
    } else {
        listOfNotNull(label, value).joinToString(", ")
    }

// ---------------------------------------------------------------------------
// Android collector (thin adapter over the read-only USB/CAT runtime APIs)
// ---------------------------------------------------------------------------

/**
 * Reads the live USB/CAT state and reduces it to [UsbDiagnosticsData]. Only enumeration
 * and permission checks touch the framework here; everything with a decision in it lives
 * in the pure helpers above. Every framework/ViewModel call is guarded so a flaky USB
 * stack or a rig reconnect racing the poll degrades to a failing check rather than
 * crashing the diagnostics screen.
 */
internal fun collectUsbDiagnostics(
    context: Context,
    mainViewModel: MainViewModel,
): UsbDiagnosticsData {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    val summaries: List<UsbDeviceSummary> = if (usbManager == null) {
        emptyList()
    } else {
        val audioDeviceIds: Set<Int> = runCatching {
            UsbAudioDevice.findUsbAudioDevices(context).map { it.device.deviceId }.toSet()
        }.getOrDefault(emptySet())
        val prober = UsbSerialProber.getDefaultProber()
        usbManager.deviceList.values.map { device ->
            UsbDeviceSummary(
                vendorId = device.vendorId,
                productId = device.productId,
                hasSerialDriver = runCatching { prober.probeDevice(device) != null }
                    .getOrDefault(false),
                hasAudioInterface = audioDeviceIds.contains(device.deviceId),
                hasPermission = runCatching { usbManager.hasPermission(device) }
                    .getOrDefault(false),
            )
        }
    }
    return assembleUsbDiagnosticsData(
        devices = summaries,
        // Guarded like the USB calls above: baseRig is reassigned (nulled then rebuilt)
        // during a reconnect, so a poll landing mid-reconnect could otherwise NPE and kill
        // the polling coroutine, freezing the screen on stale data.
        portOpen = runCatching { mainViewModel.isRigConnected() }.getOrDefault(false),
        catResponded = runCatching { mainViewModel.hasRigRespondedToCat() }.getOrDefault(false),
    )
}

private val EMPTY_DIAGNOSTICS = UsbDiagnosticsData(
    deviceFound = false,
    vendorId = null,
    productId = null,
    hasPermission = false,
    cdcSerialFound = false,
    audioDeviceFound = false,
    portOpen = false,
    catResponded = false,
)

// ---------------------------------------------------------------------------
// Composable (thin — polls the collector and renders the rows)
// ---------------------------------------------------------------------------

/**
 * USB Diagnostics settings page. Shows a live pass/fail readout for each stage of the
 * USB → serial → CAT chain so an operator can tell at a glance where a rig connection is
 * breaking down. Re-collected every 1.5 s (off the main thread) so plugging/unplugging or
 * granting permission updates without leaving the screen.
 */
@Composable
fun UsbDiagnosticsScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(buildUsbDiagnostics(EMPTY_DIAGNOSTICS)) }

    LaunchedEffect(Unit) {
        while (true) {
            val data = withContext(Dispatchers.IO) { collectUsbDiagnostics(context, mainViewModel) }
            items = buildUsbDiagnostics(data)
            delay(1_500)
        }
    }

    // The scaffold's top bar already shows "USB Diagnostics", so the description and card
    // sit directly under it — no redundant section header repeating the title.
    SettingsDetailScaffold(
        title = stringResource(R.string.usb_diag_title),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.usb_diag_desc),
            color = TextMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                items.forEachIndexed { index, item ->
                    if (index > 0) SectionDivider()
                    UsbDiagnosticRow(item)
                }
            }
        }
    }
}

@Composable
private fun UsbDiagnosticRow(item: UsbDiagnosticItem) {
    val label = stringResource(item.labelRes)
    val value = resolveDiagnosticDisplayValue(item, stringResource(R.string.usb_diag_value_none))
    val statusWord = diagnosticStatusContentDescriptionRes(item.status)?.let { stringResource(it) }
    val rowDescription = buildRowContentDescription(label, statusWord, value)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // clearAndSetSemantics (not semantics): the Row isn't a merge root, so without
            // it TalkBack would read the label and the raw "✔"/"✖" glyph as separate nodes.
            // This replaces the subtree with one clean "<label>, <pass|fail|value>" phrase.
            .clearAndSetSemantics { contentDescription = rowDescription }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (value != null) {
                Text(
                    text = value,
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontFamily = GeistMonoFamily,
                )
            }
            if (item.status != DiagnosticStatus.INFO) {
                Text(
                    text = diagnosticStatusGlyph(item.status),
                    color = diagnosticStatusColor(item.status),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
