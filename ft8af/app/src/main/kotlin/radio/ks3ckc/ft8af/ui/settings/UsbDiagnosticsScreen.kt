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
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.connector.CableSerialPort
import com.k1af.ft8af.connector.SerialPortLabel
import com.k1af.ft8af.serialport.UsbId
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
// Pure diagnostic logic (unit-tested — no Android framework dependencies; the
// Compose value classes used below, e.g. Color, are fine on the JVM under test)
// ---------------------------------------------------------------------------

/** Default CAT vendor id the wired-serial path matches on (ICOM), see CableSerialPort. */
internal val DEFAULT_CAT_VENDOR_ID = UsbId.VENDOR_ICOM

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
    /** Kernel-assigned id; lets the diagnostics describe the device the CAT port is on. */
    val deviceId: Int = 0,
)

/** Identity of the USB device behind the connected CAT port (see [selectDiagnosticDevice]). */
internal data class ConnectedUsbPort(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
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
    /**
     * The port half of the connected USB port's label ("Port 2 of 2 · Standard"),
     * null when the rig is not on a USB cable. See [SerialPortLabel.portName].
     */
    val catPortName: String? = null,
    /** True when that port belongs to a chip with named interfaces (a CP2105). */
    val catPortHasRole: Boolean = false,
    /** Configured CAT baud rate, shown next to the port so a mismatch is visible. */
    val baudRate: Int? = null,
    /**
     * False when the current link never listens for CAT (FT-710 cable mode), in
     * which case a missing CAT response is by design, not a fault.
     */
    val catReadExpected: Boolean = true,
)

/** Default for the "CAT Port" value when the caller has no resources ("Port 1 of 2 · Enhanced · 4800 bd"). */
internal const val DEFAULT_BAUD_VALUE_TEMPLATE = "%1\$s · %2\$d bd"

/** Formats a USB id as the conventional 16-bit hex (e.g. 0x0C26); null passes through. */
internal fun formatUsbId(id: Int?): String? =
    id?.let { String.format("0x%04X", it and 0xFFFF) }

/**
 * Picks which attached device the diagnostics describe. When a CAT port is connected,
 * that port's device wins, so its VID/PID and permission rows sit next to the right
 * "CAT Port" row — with two serial chips attached (a CP2105 rig plus a CP2102 cable)
 * the first serial device could otherwise be the other one. It is matched the way
 * [com.k1af.ft8af.connector.CableSerialPort] matches the picked device: same deviceId
 * and VID+PID, else same VID+PID (a re-plug hands out a new deviceId), else vendor
 * only when the product id is unknown. Without a match it prefers one a serial driver
 * can drive, then one matching the default CAT vendor id, then one exposing a
 * USB-audio interface, then simply the first device. Returns null when nothing is
 * attached.
 */
internal fun selectDiagnosticDevice(
    devices: List<UsbDeviceSummary>,
    connected: ConnectedUsbPort? = null,
): UsbDeviceSummary? =
    connected?.let { c -> matchConnectedDevice(devices, c) }
        ?: devices.firstOrNull { it.hasSerialDriver }
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
    catPortName: String? = null,
    catPortHasRole: Boolean = false,
    baudRate: Int? = null,
    catReadExpected: Boolean = true,
    connectedPort: ConnectedUsbPort? = null,
): UsbDiagnosticsData {
    val target = selectDiagnosticDevice(devices, connectedPort)
    return UsbDiagnosticsData(
        deviceFound = target != null,
        vendorId = target?.vendorId,
        productId = target?.productId,
        hasPermission = target?.hasPermission == true,
        cdcSerialFound = devices.any { it.hasSerialDriver },
        audioDeviceFound = devices.any { it.hasAudioInterface },
        portOpen = portOpen,
        catResponded = catResponded,
        catPortName = catPortName,
        catPortHasRole = catPortHasRole,
        baudRate = baudRate,
        catReadExpected = catReadExpected,
    )
}

/**
 * The "CAT Port" row value: the port name plus the configured baud, or null when
 * no USB port is open. Exposing both on one line is what lets an operator see
 * "Port 2 of 2 · Standard · 4800 bd" and realise the rig wants port 1 / another
 * rate — the two things the app cannot decide for them (issue #817).
 */
internal fun catPortValue(data: UsbDiagnosticsData, baudValueTemplate: String): String? {
    val name = data.catPortName ?: return null
    if (!data.portOpen) return null
    val baud = data.baudRate ?: return name
    return String.format(baudValueTemplate, name, baud)
}

private fun matchConnectedDevice(
    devices: List<UsbDeviceSummary>,
    c: ConnectedUsbPort,
): UsbDeviceSummary? =
    if (c.productId != 0) {
        devices.firstOrNull {
            it.deviceId == c.deviceId && it.vendorId == c.vendorId && it.productId == c.productId
        } ?: devices.firstOrNull { it.vendorId == c.vendorId && it.productId == c.productId }
    } else {
        devices.firstOrNull { it.vendorId == c.vendorId }
    }

/**
 * Whether a device counts as a serial-driver device: a prober-known chip, or one the
 * CDC-ACM fallback will drive because it carries a Communications control interface.
 * Same rule the port picker uses ([com.k1af.ft8af.connector.CableSerialPort.listSerialPorts]),
 * so a CDC rig that is selectable and connected doesn't show "CDC Serial Found" as failed.
 */
internal fun hasSerialDriver(probed: Boolean, interfaceClasses: IntArray?): Boolean =
    probed || CableSerialPort.hasCdcControlInterface(interfaceClasses)

/**
 * Maps gathered facts to the ordered list of display rows, matching the layout in the
 * task: device found, VID, PID, permission, CDC serial, audio, port open, CAT port,
 * CAT response. The VID/PID/CAT-port rows are informational (no tick/cross) and only
 * carry a value once there is one. CAT Response is also informational (not a fail)
 * on a link that never listens for CAT, see [UsbDiagnosticsData.catReadExpected].
 */
internal fun buildUsbDiagnostics(
    data: UsbDiagnosticsData,
    baudValueTemplate: String = DEFAULT_BAUD_VALUE_TEMPLATE,
): List<UsbDiagnosticItem> {
    fun passFail(ok: Boolean) = if (ok) DiagnosticStatus.PASS else DiagnosticStatus.FAIL
    val catResponse = when {
        data.catResponded -> DiagnosticStatus.PASS
        !data.catReadExpected && data.portOpen -> DiagnosticStatus.INFO
        else -> DiagnosticStatus.FAIL
    }
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
        UsbDiagnosticItem(
            R.string.usb_diag_cat_port,
            DiagnosticStatus.INFO,
            catPortValue(data, baudValueTemplate),
        ),
        UsbDiagnosticItem(R.string.usb_diag_cat_response, catResponse),
    )
}

/**
 * The one-paragraph next step shown under the card when a USB CAT port is open but
 * CAT is silent, or null when there is nothing to say. Suppressed first when there
 * is no USB port (a Bluetooth/network link also reports the rig connected, but these
 * hints are about cables, ports and baud) or CAT has answered; only then is the
 * write-only / dual-port / generic explanation chosen. Never suggests the app switch
 * ports or baud rates itself: on the same VID:PID the CAT port is Enhanced for
 * Yaesu and Standard for Kenwood, so only the operator can know.
 */
internal fun catResponseHintRes(data: UsbDiagnosticsData): Int? = when {
    !data.portOpen || data.catPortName == null || data.catResponded -> null
    !data.catReadExpected -> R.string.usb_diag_hint_ft710
    data.catPortHasRole -> R.string.usb_diag_hint_dual_port
    else -> R.string.usb_diag_hint_no_response
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
                hasSerialDriver = runCatching {
                    hasSerialDriver(
                        probed = prober.probeDevice(device) != null,
                        interfaceClasses = CableSerialPort.interfaceClassesOf(device),
                    )
                }.getOrDefault(false),
                hasAudioInterface = audioDeviceIds.contains(device.deviceId),
                hasPermission = runCatching { usbManager.hasPermission(device) }
                    .getOrDefault(false),
                deviceId = device.deviceId,
            )
        }
    }
    val port = runCatching { mainViewModel.connectedCableSerialPort() }.getOrNull()
    val portOfTemplate = context.getString(R.string.serial_port_label_port_of)
    return assembleUsbDiagnosticsData(
        devices = summaries,
        // Guarded like the USB calls above: baseRig is reassigned (nulled then rebuilt)
        // during a reconnect, so a poll landing mid-reconnect could otherwise NPE and kill
        // the polling coroutine, freezing the screen on stale data.
        portOpen = runCatching { mainViewModel.isRigConnected() }.getOrDefault(false),
        catResponded = runCatching { mainViewModel.hasRigRespondedToCat() }.getOrDefault(false),
        catPortName = port?.let {
            SerialPortLabel.portName(it.vendorId, it.productId, it.portNum, it.portCount, portOfTemplate)
        },
        catPortHasRole = port?.let {
            SerialPortLabel.portRole(it.vendorId, it.productId, it.portNum, it.portCount) != null
        } ?: false,
        baudRate = GeneralVariables.baudRate,
        catReadExpected = runCatching { mainViewModel.isCatReadExpected() }.getOrDefault(true),
        connectedPort = port?.let { ConnectedUsbPort(it.deviceId, it.vendorId, it.productId) },
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
    val baudValueTemplate = stringResource(R.string.usb_diag_baud_value)
    var items by remember { mutableStateOf(buildUsbDiagnostics(EMPTY_DIAGNOSTICS, baudValueTemplate)) }
    var hintRes by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            val data = withContext(Dispatchers.IO) { collectUsbDiagnostics(context, mainViewModel) }
            items = buildUsbDiagnostics(data, baudValueTemplate)
            hintRes = catResponseHintRes(data)
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
        hintRes?.let { res ->
            Text(
                text = stringResource(res),
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun UsbDiagnosticRow(item: UsbDiagnosticItem) {
    val label = stringResource(item.labelRes)
    val value = resolveDiagnosticDisplayValue(item, stringResource(R.string.usb_diag_value_none))
    val statusWord = diagnosticStatusContentDescriptionRes(item.status)?.let { stringResource(it) }
    // Announce the raw item.value (null until a real VID/PID exists), NOT the rendered
    // `value` — otherwise TalkBack would read the visual "—" placeholder as "Vendor ID, —".
    val rowDescription = buildRowContentDescription(label, statusWord, item.value)

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
