package radio.ks3ckc.ft8af.ui.settings

import com.k1af.ft8af.wave.AudioChannelCapability

/**
 * Why an RX/TX channel selector row is, or is not, offered to the operator.
 *
 * The rows exist to answer "which side of a stereo path do you want?", and there
 * are three situations where that question has no answer. Each greys the row out
 * with its own note, because an operator who taps Left, hears no change, and has
 * no explanation cannot tell a broken setting from an inapplicable one — the
 * confusion the greying exists to prevent.
 */
internal enum class AudioChannelRowGate {
    /** The selection applies; the row is live. */
    ENABLED,

    /** The device reports a single channel, so Left, Right and Mix are the same audio. */
    MONO_DEVICE,

    /**
     * Android's "Default" output: we cannot see what the OS routes it to, and a
     * one-sided stereo open landing on a mono route would be downmixed to half
     * drive with no warning. The TX path keeps the mono open there (see
     * `TxChannelLayout.resolve`), so the row says so instead of pretending.
     */
    DEFAULT_SINK,

    /**
     * RX audio comes from a network rig (Icom WLAN, Flex, X6100, tr-uSDX over
     * CAT) that hands us mono directly, bypassing the capture the selector
     * configures — so the row would do nothing but reopen an unused input.
     */
    NETWORK_SOURCE,
}

/**
 * Gate for the receive row.
 *
 * @param micSource   whether RX audio comes through `MicRecorder` at all
 *                    (`HamRecorder.isMicSource`)
 * @param maxChannels the selected input's channel count, or
 *                    [AudioChannelCapability.UNKNOWN]
 */
internal fun rxChannelRowGate(micSource: Boolean, maxChannels: Int): AudioChannelRowGate =
    when {
        !micSource -> AudioChannelRowGate.NETWORK_SOURCE
        !AudioChannelCapability.stereoCapable(maxChannels) -> AudioChannelRowGate.MONO_DEVICE
        else -> AudioChannelRowGate.ENABLED
    }

/**
 * Gate for the transmit row.
 *
 * @param deviceId    `GeneralVariables.audioOutputDeviceId`: `0` Default, `-1`
 *                    USB-direct, positive a framework device
 * @param maxChannels the selected output's channel count, or
 *                    [AudioChannelCapability.UNKNOWN]
 */
internal fun txChannelRowGate(deviceId: Int, maxChannels: Int): AudioChannelRowGate =
    when {
        deviceId == 0 -> AudioChannelRowGate.DEFAULT_SINK
        !AudioChannelCapability.stereoCapable(maxChannels) -> AudioChannelRowGate.MONO_DEVICE
        else -> AudioChannelRowGate.ENABLED
    }

/**
 * Where a selected device's channel count comes from — the three ways
 * `AudioDeviceSpinnerAdapter` identifies a device. Split out so the decision is
 * testable apart from the Android queries that act on it.
 */
internal enum class ChannelCountSource { DEFAULT, USB_DIRECT, FRAMEWORK }

internal fun channelCountSource(deviceId: Int): ChannelCountSource =
    when {
        deviceId == -1 -> ChannelCountSource.USB_DIRECT
        deviceId <= 0 -> ChannelCountSource.DEFAULT
        else -> ChannelCountSource.FRAMEWORK
    }
