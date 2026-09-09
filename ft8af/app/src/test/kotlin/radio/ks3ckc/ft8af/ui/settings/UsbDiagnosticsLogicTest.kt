package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.TextMuted

/**
 * Unit tests for the pure USB-diagnostics logic extracted from
 * [UsbDiagnosticsScreen] so the Composable stays a thin wrapper. No Android
 * runtime needed: everything under test is plain data classes, ints and the
 * JVM-value-class Compose [androidx.compose.ui.graphics.Color].
 */
class UsbDiagnosticsLogicTest {
    private fun summary(
        vendorId: Int = 0x1234,
        productId: Int = 0x0001,
        hasSerialDriver: Boolean = false,
        hasAudioInterface: Boolean = false,
        hasPermission: Boolean = false,
    ) = UsbDeviceSummary(
        vendorId = vendorId,
        productId = productId,
        hasSerialDriver = hasSerialDriver,
        hasAudioInterface = hasAudioInterface,
        hasPermission = hasPermission,
    )

    // --- formatUsbId ---

    @Test
    fun formatUsbId_padsToFourHexDigits() {
        assertThat(formatUsbId(0x0c26)).isEqualTo("0x0C26")
        assertThat(formatUsbId(0x20)).isEqualTo("0x0020")
    }

    @Test
    fun formatUsbId_masksToSixteenBits() {
        // UsbDevice ids are unsigned 16-bit; a sign-extended int must not print extra digits.
        assertThat(formatUsbId(0xFFFF)).isEqualTo("0xFFFF")
        assertThat(formatUsbId(-1)).isEqualTo("0xFFFF")
    }

    @Test
    fun formatUsbId_nullPassesThrough() {
        assertThat(formatUsbId(null)).isNull()
    }

    // --- selectDiagnosticDevice ---

    @Test
    fun selectDevice_emptyReturnsNull() {
        assertThat(selectDiagnosticDevice(emptyList())).isNull()
    }

    @Test
    fun selectDevice_prefersSerialDriverOverEverythingElse() {
        val audio = summary(vendorId = 0x0c26, hasAudioInterface = true)
        val serial = summary(vendorId = 0x9999, hasSerialDriver = true)
        assertThat(selectDiagnosticDevice(listOf(audio, serial))).isEqualTo(serial)
    }

    @Test
    fun selectDevice_prefersCatVendorWhenNoSerialDriver() {
        val other = summary(vendorId = 0x1111)
        val cat = summary(vendorId = DEFAULT_CAT_VENDOR_ID)
        assertThat(selectDiagnosticDevice(listOf(other, cat))).isEqualTo(cat)
    }

    @Test
    fun selectDevice_prefersAudioWhenNoSerialOrCatVendor() {
        val plain = summary(vendorId = 0x1111)
        val audio = summary(vendorId = 0x2222, hasAudioInterface = true)
        assertThat(selectDiagnosticDevice(listOf(plain, audio))).isEqualTo(audio)
    }

    @Test
    fun selectDevice_fallsBackToFirstDevice() {
        val first = summary(vendorId = 0x1111)
        val second = summary(vendorId = 0x2222)
        assertThat(selectDiagnosticDevice(listOf(first, second))).isEqualTo(first)
    }

    // --- assembleUsbDiagnosticsData ---

    @Test
    fun assemble_noDevices_allNegative() {
        val data = assembleUsbDiagnosticsData(emptyList(), portOpen = false, catResponded = false)
        assertThat(data.deviceFound).isFalse()
        assertThat(data.vendorId).isNull()
        assertThat(data.productId).isNull()
        assertThat(data.hasPermission).isFalse()
        assertThat(data.cdcSerialFound).isFalse()
        assertThat(data.audioDeviceFound).isFalse()
    }

    @Test
    fun assemble_reportsTargetDeviceVidPidAndPermission() {
        val target = summary(
            vendorId = 0x0c26,
            productId = 0x0020,
            hasSerialDriver = true,
            hasPermission = true,
        )
        val data = assembleUsbDiagnosticsData(listOf(target), portOpen = true, catResponded = false)
        assertThat(data.deviceFound).isTrue()
        assertThat(data.vendorId).isEqualTo(0x0c26)
        assertThat(data.productId).isEqualTo(0x0020)
        assertThat(data.hasPermission).isTrue()
        assertThat(data.cdcSerialFound).isTrue()
        assertThat(data.portOpen).isTrue()
        assertThat(data.catResponded).isFalse()
    }

    @Test
    fun assemble_serialAndAudioAggregateAcrossDevices() {
        // The audio device is a different device than the serial one; both flags should trip.
        val serial = summary(vendorId = 0x0c26, hasSerialDriver = true)
        val audio = summary(vendorId = 0x08bb, hasAudioInterface = true)
        val data = assembleUsbDiagnosticsData(listOf(serial, audio), portOpen = false, catResponded = false)
        assertThat(data.cdcSerialFound).isTrue()
        assertThat(data.audioDeviceFound).isTrue()
        // The serial device is the chosen target.
        assertThat(data.vendorId).isEqualTo(0x0c26)
    }

    @Test
    fun assemble_permissionFromTargetOnly() {
        // Target (serial) lacks permission even though another device has it.
        val serial = summary(vendorId = 0x0c26, hasSerialDriver = true, hasPermission = false)
        val audio = summary(vendorId = 0x08bb, hasAudioInterface = true, hasPermission = true)
        val data = assembleUsbDiagnosticsData(listOf(serial, audio), portOpen = false, catResponded = false)
        assertThat(data.hasPermission).isFalse()
    }

    // --- buildUsbDiagnostics ---

    private fun fullData(
        deviceFound: Boolean = true,
        vendorId: Int? = 0x0c26,
        productId: Int? = 0x0020,
        hasPermission: Boolean = true,
        cdcSerialFound: Boolean = true,
        audioDeviceFound: Boolean = true,
        portOpen: Boolean = true,
        catResponded: Boolean = true,
    ) = UsbDiagnosticsData(
        deviceFound = deviceFound,
        vendorId = vendorId,
        productId = productId,
        hasPermission = hasPermission,
        cdcSerialFound = cdcSerialFound,
        audioDeviceFound = audioDeviceFound,
        portOpen = portOpen,
        catResponded = catResponded,
    )

    @Test
    fun build_producesEightRowsInTaskOrder() {
        val rows = buildUsbDiagnostics(fullData())
        assertThat(rows.map { it.labelRes }).containsExactly(
            R.string.usb_diag_device_found,
            R.string.usb_diag_vendor_id,
            R.string.usb_diag_product_id,
            R.string.usb_diag_permission,
            R.string.usb_diag_cdc_serial,
            R.string.usb_diag_audio_device,
            R.string.usb_diag_port_open,
            R.string.usb_diag_cat_response,
        ).inOrder()
    }

    @Test
    fun build_vidPidRowsAreInformationalWithHexValues() {
        val rows = buildUsbDiagnostics(fullData()).associateBy { it.labelRes }
        val vid = rows.getValue(R.string.usb_diag_vendor_id)
        val pid = rows.getValue(R.string.usb_diag_product_id)
        assertThat(vid.status).isEqualTo(DiagnosticStatus.INFO)
        assertThat(vid.value).isEqualTo("0x0C26")
        assertThat(pid.status).isEqualTo(DiagnosticStatus.INFO)
        assertThat(pid.value).isEqualTo("0x0020")
    }

    @Test
    fun build_vidPidHaveNoValueWhenNoDevice() {
        val rows = buildUsbDiagnostics(fullData(deviceFound = false, vendorId = null, productId = null))
            .associateBy { it.labelRes }
        assertThat(rows.getValue(R.string.usb_diag_vendor_id).value).isNull()
        assertThat(rows.getValue(R.string.usb_diag_product_id).value).isNull()
    }

    @Test
    fun build_passFailReflectsData() {
        // Mirrors the task's example: everything up passes except CAT response.
        val rows = buildUsbDiagnostics(fullData(catResponded = false)).associateBy { it.labelRes }
        assertThat(rows.getValue(R.string.usb_diag_device_found).status).isEqualTo(DiagnosticStatus.PASS)
        assertThat(rows.getValue(R.string.usb_diag_permission).status).isEqualTo(DiagnosticStatus.PASS)
        assertThat(rows.getValue(R.string.usb_diag_cdc_serial).status).isEqualTo(DiagnosticStatus.PASS)
        assertThat(rows.getValue(R.string.usb_diag_audio_device).status).isEqualTo(DiagnosticStatus.PASS)
        assertThat(rows.getValue(R.string.usb_diag_port_open).status).isEqualTo(DiagnosticStatus.PASS)
        assertThat(rows.getValue(R.string.usb_diag_cat_response).status).isEqualTo(DiagnosticStatus.FAIL)
    }

    @Test
    fun build_allFailWhenNothingConnected() {
        val rows = buildUsbDiagnostics(
            fullData(
                deviceFound = false,
                vendorId = null,
                productId = null,
                hasPermission = false,
                cdcSerialFound = false,
                audioDeviceFound = false,
                portOpen = false,
                catResponded = false,
            ),
        ).associateBy { it.labelRes }
        assertThat(rows.getValue(R.string.usb_diag_device_found).status).isEqualTo(DiagnosticStatus.FAIL)
        assertThat(rows.getValue(R.string.usb_diag_port_open).status).isEqualTo(DiagnosticStatus.FAIL)
        // VID/PID stay INFO regardless — they are readouts, not checks.
        assertThat(rows.getValue(R.string.usb_diag_vendor_id).status).isEqualTo(DiagnosticStatus.INFO)
    }

    // --- status presentation helpers ---

    @Test
    fun statusGlyph_mapsPassFailInfo() {
        assertThat(diagnosticStatusGlyph(DiagnosticStatus.PASS)).isEqualTo("✔")
        assertThat(diagnosticStatusGlyph(DiagnosticStatus.FAIL)).isEqualTo("✖")
        assertThat(diagnosticStatusGlyph(DiagnosticStatus.INFO)).isEmpty()
    }

    @Test
    fun statusColor_passIsGreenFailIsRed() {
        assertThat(diagnosticStatusColor(DiagnosticStatus.PASS)).isEqualTo(StatusConfirmed)
        assertThat(diagnosticStatusColor(DiagnosticStatus.FAIL)).isEqualTo(StatusBad)
        assertThat(diagnosticStatusColor(DiagnosticStatus.INFO)).isEqualTo(TextMuted)
    }

    @Test
    fun statusContentDescription_nullForInfoOnly() {
        assertThat(diagnosticStatusContentDescriptionRes(DiagnosticStatus.PASS))
            .isEqualTo(R.string.usb_diag_status_pass)
        assertThat(diagnosticStatusContentDescriptionRes(DiagnosticStatus.FAIL))
            .isEqualTo(R.string.usb_diag_status_fail)
        assertThat(diagnosticStatusContentDescriptionRes(DiagnosticStatus.INFO)).isNull()
    }

    // --- resolveDiagnosticDisplayValue ---

    @Test
    fun displayValue_usesItemValueWhenPresent() {
        val item = UsbDiagnosticItem(R.string.usb_diag_vendor_id, DiagnosticStatus.INFO, "0x0C26")
        assertThat(resolveDiagnosticDisplayValue(item, "—")).isEqualTo("0x0C26")
    }

    @Test
    fun displayValue_infoWithoutValueFallsBackToNone() {
        val item = UsbDiagnosticItem(R.string.usb_diag_vendor_id, DiagnosticStatus.INFO, null)
        assertThat(resolveDiagnosticDisplayValue(item, "—")).isEqualTo("—")
    }

    @Test
    fun displayValue_passFailRowsShowNoValue() {
        val pass = UsbDiagnosticItem(R.string.usb_diag_device_found, DiagnosticStatus.PASS, null)
        val fail = UsbDiagnosticItem(R.string.usb_diag_port_open, DiagnosticStatus.FAIL, null)
        assertThat(resolveDiagnosticDisplayValue(pass, "—")).isNull()
        assertThat(resolveDiagnosticDisplayValue(fail, "—")).isNull()
    }

    // --- buildRowContentDescription ---

    @Test
    fun rowDescription_passFailAnnouncesLabelAndStatusWord() {
        assertThat(buildRowContentDescription("Device Found", "pass", null))
            .isEqualTo("Device Found, pass")
        assertThat(buildRowContentDescription("CAT Response", "fail", null))
            .isEqualTo("CAT Response, fail")
    }

    @Test
    fun rowDescription_infoAnnouncesLabelAndValue() {
        // No spoken status word (INFO) — the VID/PID value must still be announced.
        assertThat(buildRowContentDescription("Vendor ID", null, "0x0C26"))
            .isEqualTo("Vendor ID, 0x0C26")
    }

    @Test
    fun rowDescription_infoWithoutValueIsJustLabel() {
        assertThat(buildRowContentDescription("Vendor ID", null, null)).isEqualTo("Vendor ID")
    }
}
