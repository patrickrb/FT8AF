package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.wave.AudioChannelCapability
import org.junit.Test

/**
 * Pure unit tests for the RX/TX channel-row gates and the channel-count source
 * decision — the logic behind "is this selector offered, and if not, which note
 * explains why". No Android runtime involved.
 */
class AudioChannelRowGateTest {
    // ---- receive ----

    @Test
    fun rx_stereoMicSourceIsEnabled() {
        assertThat(rxChannelRowGate(micSource = true, maxChannels = 2))
            .isEqualTo(AudioChannelRowGate.ENABLED)
    }

    @Test
    fun rx_unknownCountStaysEnabled() {
        // An unreported count must not read as mono — see AudioChannelCapability.
        assertThat(rxChannelRowGate(micSource = true, maxChannels = AudioChannelCapability.UNKNOWN))
            .isEqualTo(AudioChannelRowGate.ENABLED)
    }

    @Test
    fun rx_monoInputIsGreyedAsMono() {
        assertThat(rxChannelRowGate(micSource = true, maxChannels = 1))
            .isEqualTo(AudioChannelRowGate.MONO_DEVICE)
    }

    @Test
    fun rx_networkRigWinsOverEverything() {
        // Audio bypasses MicRecorder entirely; the channel count is irrelevant.
        assertThat(rxChannelRowGate(micSource = false, maxChannels = 2))
            .isEqualTo(AudioChannelRowGate.NETWORK_SOURCE)
        assertThat(rxChannelRowGate(micSource = false, maxChannels = 1))
            .isEqualTo(AudioChannelRowGate.NETWORK_SOURCE)
    }

    // ---- transmit ----

    @Test
    fun tx_namedStereoDeviceIsEnabled() {
        assertThat(txChannelRowGate(deviceId = 7, maxChannels = 2))
            .isEqualTo(AudioChannelRowGate.ENABLED)
        assertThat(txChannelRowGate(deviceId = -1, maxChannels = 2))
            .isEqualTo(AudioChannelRowGate.ENABLED)
    }

    @Test
    fun tx_defaultSinkIsGreyedRegardlessOfCount() {
        // TxChannelLayout keeps the mono open on Default; the row must say so.
        assertThat(txChannelRowGate(deviceId = 0, maxChannels = 2))
            .isEqualTo(AudioChannelRowGate.DEFAULT_SINK)
        assertThat(txChannelRowGate(deviceId = 0, maxChannels = AudioChannelCapability.UNKNOWN))
            .isEqualTo(AudioChannelRowGate.DEFAULT_SINK)
    }

    @Test
    fun tx_monoDeviceIsGreyedAsMono() {
        assertThat(txChannelRowGate(deviceId = 7, maxChannels = 1))
            .isEqualTo(AudioChannelRowGate.MONO_DEVICE)
        assertThat(txChannelRowGate(deviceId = -1, maxChannels = 1))
            .isEqualTo(AudioChannelRowGate.MONO_DEVICE)
    }

    // ---- channel count source ----

    @Test
    fun source_mirrorsTheAdapterIdConvention() {
        assertThat(channelCountSource(0)).isEqualTo(ChannelCountSource.DEFAULT)
        assertThat(channelCountSource(-1)).isEqualTo(ChannelCountSource.USB_DIRECT)
        assertThat(channelCountSource(12)).isEqualTo(ChannelCountSource.FRAMEWORK)
        // Anything else non-positive is treated like Default, never like USB.
        assertThat(channelCountSource(-5)).isEqualTo(ChannelCountSource.DEFAULT)
    }
}
