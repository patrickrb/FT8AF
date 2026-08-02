package radio.ks3ckc.ft8af.ui.settings

/**
 * Decision logic for the "Direct USB TX" toggle in [RadioAudioSettings].
 *
 * <p>The transmit path is chosen in {@code FT8TransmitSignal.playFT8Signal} by
 * the same two persisted fields this toggle drives:
 * {@code audioOutputDeviceId == -1 && usbAudioOutputVendorId != 0} selects the
 * direct libusb output (bypassing Android's shared mixer, so notification and
 * other-app sounds can't leak on air); anything else goes out the AudioTrack
 * sink. Keeping the toggle's on/off state derived from — and written back to —
 * exactly those fields means the toggle and the existing "Audio Output" device
 * picker stay consistent: they are two views of one setting.
 *
 * <p>The Composable is a thin wrapper; the on/off predicate and the field
 * values for each direction live here as pure functions so they are unit-tested
 * without Android.
 */

/** The three persisted audio-output fields the toggle reads and writes. */
internal data class AudioOutputSelection(
    val audioOutputDeviceId: Int,
    val usbVendorId: Int,
    val usbProductId: Int,
)

/**
 * True when the persisted selection routes TX through the direct libusb path.
 * Mirrors the branch condition in {@code FT8TransmitSignal.playFT8Signal}
 * exactly — if that condition changes, this must change with it.
 */
internal fun isDirectUsbTxEnabled(audioOutputDeviceId: Int, usbOutputVendorId: Int): Boolean =
    audioOutputDeviceId == -1 && usbOutputVendorId != 0

/**
 * Selection that enables the direct libusb TX path for a radio codec identified
 * by [vendorId]/[productId] (from a discovered USB audio output device). The
 * caller must only apply this when a real device was found: a `vendorId` of 0
 * would fail the enable predicate and silently leave TX on the AudioTrack sink.
 */
internal fun directUsbTxSelection(vendorId: Int, productId: Int): AudioOutputSelection =
    AudioOutputSelection(audioOutputDeviceId = -1, usbVendorId = vendorId, usbProductId = productId)

/**
 * Selection that disables the direct path, falling back to Android's
 * system-default output sink (device id 0). Clears the USB VID/PID so a later
 * read of [isDirectUsbTxEnabled] reports off even though device id alone would.
 */
internal fun systemDefaultTxSelection(): AudioOutputSelection =
    AudioOutputSelection(audioOutputDeviceId = 0, usbVendorId = 0, usbProductId = 0)
