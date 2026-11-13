package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioDeviceInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * The Android adapter between the enumerated outputs, the pure
 * {@link AudioOutputRoutingPolicy}, and {@code AudioTrack.setPreferredDevice}.
 * Robolectric builds real {@link AudioDeviceInfo} objects (type only — their
 * addresses come back blank, which is the withheld-address path the policy's
 * single-pair fallback exists for), and a capturing {@link DefaultOutputRouting.Sink}
 * stands in for the track and debug.log.
 */
@RunWith(RobolectricTestRunner.class)
public class DefaultOutputRoutingTest {

    private static AudioDeviceInfo device(int type) {
        return AudioDeviceInfoBuilder.newBuilder().setType(type).build();
    }

    private static final class CapturingSink implements DefaultOutputRouting.Sink {
        final List<AudioDeviceInfo> preferred = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean setPreferredDevice(AudioDeviceInfo device) {
            preferred.add(device);
            return accept;
        }

        @Override
        public void log(String line) {
            log.add(line);
        }
    }

    @Test
    public void ourScoUp_singlePair_steersToTheA2dpDeviceAndLogsIt() {
        AudioDeviceInfo speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        AudioDeviceInfo a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        AudioDeviceInfo sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        CapturingSink sink = new CapturingSink();

        boolean applied = DefaultOutputRouting.apply(
                new AudioDeviceInfo[] {speaker, a2dp, sco}, true, null, sink);

        assertThat(applied).isTrue();
        assertThat(sink.preferred).containsExactly(a2dp);
        assertThat(sink.log).containsExactly(DefaultOutputRouting.LOG_STEERED);
    }

    @Test
    public void frameworkRefusesTheRoute_logsTheRejection() {
        AudioDeviceInfo a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        AudioDeviceInfo sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        CapturingSink sink = new CapturingSink();
        sink.accept = false;

        boolean applied = DefaultOutputRouting.apply(
                new AudioDeviceInfo[] {a2dp, sco}, true, null, sink);

        assertThat(applied).isFalse();
        assertThat(sink.preferred).containsExactly(a2dp);
        assertThat(sink.log).containsExactly(DefaultOutputRouting.LOG_REJECTED);
    }

    @Test
    public void noScoOfOurs_touchesNothing() {
        AudioDeviceInfo a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        AudioDeviceInfo sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        CapturingSink sink = new CapturingSink();

        assertThat(DefaultOutputRouting.apply(new AudioDeviceInfo[] {a2dp, sco}, false, null, sink))
                .isFalse();
        assertThat(sink.preferred).isEmpty();
        assertThat(sink.log).isEmpty();
    }

    @Test
    public void noBluetoothOutputs_touchesNothing() {
        AudioDeviceInfo speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        AudioDeviceInfo usb = device(AudioDeviceInfo.TYPE_USB_DEVICE);
        CapturingSink sink = new CapturingSink();

        assertThat(DefaultOutputRouting.apply(new AudioDeviceInfo[] {speaker, usb}, true, null, sink))
                .isFalse();
        assertThat(sink.preferred).isEmpty();
        assertThat(sink.log).isEmpty();
    }

    @Test
    public void nullEnumeration_touchesNothing() {
        CapturingSink sink = new CapturingSink();
        assertThat(DefaultOutputRouting.apply(null, true, null, sink)).isFalse();
        assertThat(sink.preferred).isEmpty();
    }

    @Test
    @Config(sdk = 27)
    public void belowPie_addressesAreUnknown_andTheFixStillApplies() {
        // Android 8.1 is the device this exists for; getAddress() does not exist
        // there, so the adapter must not call it — and the single-pair fallback
        // must still steer.
        AudioDeviceInfo a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        AudioDeviceInfo sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        assertThat(DefaultOutputRouting.deviceAddressOrNull(a2dp)).isNull();
        CapturingSink sink = new CapturingSink();

        assertThat(DefaultOutputRouting.apply(new AudioDeviceInfo[] {a2dp, sco}, true, null, sink))
                .isTrue();
        assertThat(sink.preferred).containsExactly(a2dp);
    }

    @Test
    public void onPieAndLater_addressIsReadWithoutThrowing() {
        AudioDeviceInfo a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        // Robolectric's builder gives no address; the point is the call path
        // itself is taken and any blank comes back as a value, not an exception.
        String address = DefaultOutputRouting.deviceAddressOrNull(a2dp);
        assertThat(address == null || address.isEmpty()).isTrue();
    }
}
