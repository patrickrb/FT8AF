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
        deviceId: Int = 0,
    ) = UsbDeviceSummary(
        vendorId = vendorId,
        productId = productId,
        hasSerialDriver = hasSerialDriver,
        hasAudioInterface = hasAudioInterface,
        hasPermission = hasPermission,
        deviceId = deviceId,
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

    @Test
    fun selectDevice_connectedPortWinsOverFirstSerialDevice() {
        // CP2102 cable enumerated first, the rig's CP2105 second; CAT is on the CP2105.
        // The first-serial ranking would describe the CP2102 next to the CP2105's port.
        val cp2102 = summary(0x10C4, 0xEA60, hasSerialDriver = true, deviceId = 1003)
        val cp2105 = summary(0x10C4, 0xEA70, hasSerialDriver = true, deviceId = 1004)
        val connected = ConnectedUsbPort(deviceId = 1004, vendorId = 0x10C4, productId = 0xEA70)
        assertThat(selectDiagnosticDevice(listOf(cp2102, cp2105), connected)).isEqualTo(cp2105)
    }

    @Test
    fun selectDevice_connectedPortMatchesByVidPidAfterReplug() {
        // The port was picked before a re-plug, which assigned a new deviceId.
        val cp2102 = summary(0x10C4, 0xEA60, hasSerialDriver = true, deviceId = 1003)
        val cp2105 = summary(0x10C4, 0xEA70, hasSerialDriver = true, deviceId = 1010)
        val stale = ConnectedUsbPort(deviceId = 1004, vendorId = 0x10C4, productId = 0xEA70)
        assertThat(selectDiagnosticDevice(listOf(cp2102, cp2105), stale)).isEqualTo(cp2105)
    }

    @Test
    fun selectDevice_recycledDeviceIdDoesNotMatchADifferentChip() {
        // The kernel handed the old deviceId to the CP2102: VID+PID must still agree.
        val cp2102 = summary(0x10C4, 0xEA60, hasSerialDriver = true, deviceId = 1004)
        val cp2105 = summary(0x10C4, 0xEA70, hasSerialDriver = true, deviceId = 1010)
        val connected = ConnectedUsbPort(deviceId = 1004, vendorId = 0x10C4, productId = 0xEA70)
        assertThat(selectDiagnosticDevice(listOf(cp2102, cp2105), connected)).isEqualTo(cp2105)
    }

    @Test
    fun selectDevice_unknownProductIdMatchesVendorOnly() {
        val other = summary(0x1111, 0x0001, hasSerialDriver = true, deviceId = 1)
        val rig = summary(0x0C26, 0x0020, deviceId = 2)
        val legacy = ConnectedUsbPort(deviceId = 99, vendorId = 0x0C26, productId = 0)
        assertThat(selectDiagnosticDevice(listOf(other, rig), legacy)).isEqualTo(rig)
    }

    @Test
    fun selectDevice_connectedPortGoneFallsBackToRanking() {
        val serial = summary(0x0C26, 0x0020, hasSerialDriver = true, deviceId = 5)
        val gone = ConnectedUsbPort(deviceId = 9, vendorId = 0x10C4, productId = 0xEA70)
        assertThat(selectDiagnosticDevice(listOf(serial), gone)).isEqualTo(serial)
    }

    // --- hasSerialDriver (CDC fallback, same rule as the port picker) ---

    @Test
    fun hasSerialDriver_proberKnownChip() {
        assertThat(hasSerialDriver(probed = true, interfaceClasses = intArrayOf())).isTrue()
    }

    @Test
    fun hasSerialDriver_cdcFallbackWithCommunicationsInterface() {
        // USB_CLASS_COMM (2) + USB_CLASS_CDC_DATA (10): no prober match, but the
        // picker lists and connects it through the CDC-ACM fallback.
        assertThat(hasSerialDriver(probed = false, interfaceClasses = intArrayOf(2, 10))).isTrue()
    }

    @Test
    fun hasSerialDriver_unknownNonCdcDeviceIsNot() {
        // e.g. the rig's USB audio codec (class 1) — not a serial port.
        assertThat(hasSerialDriver(probed = false, interfaceClasses = intArrayOf(1, 1))).isFalse()
        assertThat(hasSerialDriver(probed = false, interfaceClasses = null)).isFalse()
    }

    // --- assembleUsbDiagnosticsData ---

    @Test
    fun assemble_vidPidAndPermissionComeFromTheConnectedPortsDevice() {
        val cp2102 = summary(0x10C4, 0xEA60, hasSerialDriver = true, hasPermission = true, deviceId = 1003)
        val cp2105 = summary(0x10C4, 0xEA70, hasSerialDriver = true, hasPermission = false, deviceId = 1004)
        val data = assembleUsbDiagnosticsData(
            listOf(cp2102, cp2105),
            portOpen = true,
            catResponded = false,
            connectedPort = ConnectedUsbPort(1004, 0x10C4, 0xEA70),
        )
        assertThat(data.vendorId).isEqualTo(0x10C4)
        assertThat(data.productId).isEqualTo(0xEA70)
        assertThat(data.hasPermission).isFalse()
    }

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

    private val USB_PORT = "Port 1 of 2 · Enhanced"

    private fun fullData(
        deviceFound: Boolean = true,
        vendorId: Int? = 0x0c26,
        productId: Int? = 0x0020,
        hasPermission: Boolean = true,
        cdcSerialFound: Boolean = true,
        audioDeviceFound: Boolean = true,
        portOpen: Boolean = true,
        catResponded: Boolean = true,
        catPortName: String? = null,
        catPortHasRole: Boolean = false,
        baudRate: Int? = null,
        catReadExpected: Boolean = true,
    ) = UsbDiagnosticsData(
        deviceFound = deviceFound,
        vendorId = vendorId,
        productId = productId,
        hasPermission = hasPermission,
        cdcSerialFound = cdcSerialFound,
        audioDeviceFound = audioDeviceFound,
        portOpen = portOpen,
        catResponded = catResponded,
        catPortName = catPortName,
        catPortHasRole = catPortHasRole,
        baudRate = baudRate,
        catReadExpected = catReadExpected,
    )

    @Test
    fun build_producesNineRowsInTaskOrder() {
        val rows = buildUsbDiagnostics(fullData())
        assertThat(rows.map { it.labelRes }).containsExactly(
            R.string.usb_diag_device_found,
            R.string.usb_diag_vendor_id,
            R.string.usb_diag_product_id,
            R.string.usb_diag_permission,
            R.string.usb_diag_cdc_serial,
            R.string.usb_diag_audio_device,
            R.string.usb_diag_port_open,
            R.string.usb_diag_cat_port,
            R.string.usb_diag_cat_response,
        ).inOrder()
    }

    // --- CAT Port row + hint (issue #817) ---

    @Test
    fun build_catPortRowIsInformationalAndShowsPortWithBaud() {
        val rows = buildUsbDiagnostics(
            fullData(catPortName = "Port 2 of 2 · Standard", catPortHasRole = true, baudRate = 4800),
        ).associateBy { it.labelRes }
        val row = rows.getValue(R.string.usb_diag_cat_port)
        assertThat(row.status).isEqualTo(DiagnosticStatus.INFO)
        assertThat(row.value).isEqualTo("Port 2 of 2 · Standard · 4800 bd")
    }

    @Test
    fun build_catPortRowUsesCallersTemplate() {
        val rows = buildUsbDiagnostics(
            fullData(catPortName = "Port 1 of 1", baudRate = 38400),
            baudValueTemplate = "%1\$s @ %2\$d",
        ).associateBy { it.labelRes }
        assertThat(rows.getValue(R.string.usb_diag_cat_port).value).isEqualTo("Port 1 of 1 @ 38400")
    }

    @Test
    fun catPortValue_nullWithoutAnOpenUsbPort() {
        // Bluetooth/network rigs have no USB port; a closed port shows nothing either.
        assertThat(catPortValue(fullData(catPortName = null), DEFAULT_BAUD_VALUE_TEMPLATE)).isNull()
        assertThat(
            catPortValue(fullData(catPortName = "Port 1 of 1", portOpen = false), DEFAULT_BAUD_VALUE_TEMPLATE),
        ).isNull()
    }

    @Test
    fun catPortValue_portNameAloneWhenBaudUnknown() {
        assertThat(catPortValue(fullData(catPortName = "Port 1 of 1", baudRate = null), DEFAULT_BAUD_VALUE_TEMPLATE))
            .isEqualTo("Port 1 of 1")
    }

    @Test
    fun build_catResponseIsInfoNotFailWhenNoReadIsExpected() {
        // FT-710 cable mode never starts the read loop: red would be a false alarm.
        val rows = buildUsbDiagnostics(fullData(catResponded = false, catReadExpected = false))
            .associateBy { it.labelRes }
        assertThat(rows.getValue(R.string.usb_diag_cat_response).status).isEqualTo(DiagnosticStatus.INFO)
    }

    @Test
    fun build_catResponseStillFailsWhenNoReadExpectedButPortClosed() {
        // With the port down the problem is upstream of CAT; keep the honest red.
        val rows = buildUsbDiagnostics(fullData(portOpen = false, catResponded = false, catReadExpected = false))
            .associateBy { it.labelRes }
        assertThat(rows.getValue(R.string.usb_diag_cat_response).status).isEqualTo(DiagnosticStatus.FAIL)
    }

    @Test
    fun build_catResponsePassWinsOverReadExpectedFlag() {
        val rows = buildUsbDiagnostics(fullData(catResponded = true, catReadExpected = false))
            .associateBy { it.labelRes }
        assertThat(rows.getValue(R.string.usb_diag_cat_response).status).isEqualTo(DiagnosticStatus.PASS)
    }

    @Test
    fun hint_noneWhilePortClosedOrCatAnswering() {
        assertThat(catResponseHintRes(fullData(portOpen = false, catResponded = false, catPortName = USB_PORT)))
            .isNull()
        assertThat(catResponseHintRes(fullData(catResponded = true, catPortName = USB_PORT))).isNull()
        assertThat(catResponseHintRes(fullData(catResponded = true, catPortHasRole = true, catPortName = USB_PORT)))
            .isNull()
    }

    @Test
    fun hint_noneWithoutAUsbPort() {
        // Bluetooth/network links report portOpen too, but carry no USB port name: the
        // cable/port/baud hints don't apply there.
        assertThat(catResponseHintRes(fullData(catResponded = false, catPortName = null))).isNull()
        assertThat(catResponseHintRes(fullData(catResponded = false, catReadExpected = false, catPortName = null)))
            .isNull()
    }

    @Test
    fun hint_noFt710NoteBesideAPassRow() {
        // CAT answered: whatever the read-expected flag says, the row is PASS — no hint.
        val data = fullData(catResponded = true, catReadExpected = false, catPortName = USB_PORT)
        assertThat(buildUsbDiagnostics(data).single { it.labelRes == R.string.usb_diag_cat_response }.status)
            .isEqualTo(DiagnosticStatus.PASS)
        assertThat(catResponseHintRes(data)).isNull()
    }

    @Test
    fun hint_ft710WriteOnlyExplainsTheInformationalRow() {
        assertThat(catResponseHintRes(fullData(catResponded = false, catReadExpected = false, catPortName = USB_PORT)))
            .isEqualTo(R.string.usb_diag_hint_ft710)
        // Even on a CP2105 the FT-710 note wins: the port is not the question there.
        assertThat(
            catResponseHintRes(
                fullData(catResponded = false, catReadExpected = false, catPortHasRole = true, catPortName = USB_PORT),
            ),
        ).isEqualTo(R.string.usb_diag_hint_ft710)
    }

    @Test
    fun hint_dualPortChipGetsTheEnhancedStandardNote() {
        assertThat(catResponseHintRes(fullData(catResponded = false, catPortHasRole = true, catPortName = USB_PORT)))
            .isEqualTo(R.string.usb_diag_hint_dual_port)
    }

    @Test
    fun hint_singlePortSilenceGetsTheGenericNote() {
        assertThat(catResponseHintRes(fullData(catResponded = false, catPortHasRole = false, catPortName = USB_PORT)))
            .isEqualTo(R.string.usb_diag_hint_no_response)
    }

    @Test
    fun assemble_passesPortFactsThrough() {
        val data = assembleUsbDiagnosticsData(
            emptyList(),
            portOpen = true,
            catResponded = false,
            catPortName = "Port 1 of 2 · Enhanced",
            catPortHasRole = true,
            baudRate = 4800,
            catReadExpected = false,
        )
        assertThat(data.catPortName).isEqualTo("Port 1 of 2 · Enhanced")
        assertThat(data.catPortHasRole).isTrue()
        assertThat(data.baudRate).isEqualTo(4800)
        assertThat(data.catReadExpected).isFalse()
    }

    @Test
    fun assemble_portFactsDefaultToNoUsbPort() {
        val data = assembleUsbDiagnosticsData(emptyList(), portOpen = false, catResponded = false)
        assertThat(data.catPortName).isNull()
        assertThat(data.catPortHasRole).isFalse()
        assertThat(data.baudRate).isNull()
        assertThat(data.catReadExpected).isTrue()
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
