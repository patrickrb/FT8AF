package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link GeneralVariables#isConfiguredUsbAudioInput(int, int)}
 * and {@link GeneralVariables#isConfiguredUsbAudioOutput(int, int)}.
 *
 * <p>These decide, on a USB-attach event, whether a freshly-plugged device is
 * the one the user picked for the direct USB audio path and therefore needs an
 * audio-permission request so the recorder rebinds to it instead of silently
 * staying on the built-in mic. "USB direct" is encoded as deviceId == -1 with a
 * non-zero saved VID/PID; anything else (0 = system default, a positive
 * AudioManager id, or a zero VID) must NOT match.
 */
@RunWith(RobolectricTestRunner.class)
public class UsbAudioSelectionTest {

    // A representative C-Media CM108-style dongle (the DigiRig case).
    private static final int VID = 0x0D8C;
    private static final int PID = 0x0012;

    @Before
    public void setUp() {
        GeneralVariables.audioInputDeviceId = 0;
        GeneralVariables.usbAudioInputVendorId = 0;
        GeneralVariables.usbAudioInputProductId = 0;
        GeneralVariables.audioOutputDeviceId = 0;
        GeneralVariables.usbAudioOutputVendorId = 0;
        GeneralVariables.usbAudioOutputProductId = 0;
    }

    @After
    public void tearDown() {
        setUp();
    }

    @Test
    public void input_matchesWhenSelectedAsUsbDirectWithSameVidPid() {
        GeneralVariables.audioInputDeviceId = -1;
        GeneralVariables.usbAudioInputVendorId = VID;
        GeneralVariables.usbAudioInputProductId = PID;

        assertThat(GeneralVariables.isConfiguredUsbAudioInput(VID, PID)).isTrue();
    }

    @Test
    public void input_doesNotMatchWhenDeviceIsSystemDefault() {
        // deviceId 0 = system default mic; never the direct USB path even if a
        // stale VID/PID lingers.
        GeneralVariables.audioInputDeviceId = 0;
        GeneralVariables.usbAudioInputVendorId = VID;
        GeneralVariables.usbAudioInputProductId = PID;

        assertThat(GeneralVariables.isConfiguredUsbAudioInput(VID, PID)).isFalse();
    }

    @Test
    public void input_doesNotMatchWhenDeviceIsAudioManagerDevice() {
        GeneralVariables.audioInputDeviceId = 7; // a positive AudioManager id
        GeneralVariables.usbAudioInputVendorId = VID;
        GeneralVariables.usbAudioInputProductId = PID;

        assertThat(GeneralVariables.isConfiguredUsbAudioInput(VID, PID)).isFalse();
    }

    @Test
    public void input_doesNotMatchADifferentDevice() {
        GeneralVariables.audioInputDeviceId = -1;
        GeneralVariables.usbAudioInputVendorId = VID;
        GeneralVariables.usbAudioInputProductId = PID;

        assertThat(GeneralVariables.isConfiguredUsbAudioInput(0x1234, 0x5678)).isFalse();
    }

    @Test
    public void input_doesNotMatchWhenNoVidSaved() {
        // USB direct selected but VID never persisted (0) — can't match anything.
        GeneralVariables.audioInputDeviceId = -1;
        GeneralVariables.usbAudioInputVendorId = 0;
        GeneralVariables.usbAudioInputProductId = 0;

        assertThat(GeneralVariables.isConfiguredUsbAudioInput(0, 0)).isFalse();
    }

    @Test
    public void inputAndOutputAreIndependent() {
        // Output is USB-direct; input is the default mic. Only output matches.
        GeneralVariables.audioOutputDeviceId = -1;
        GeneralVariables.usbAudioOutputVendorId = VID;
        GeneralVariables.usbAudioOutputProductId = PID;

        assertThat(GeneralVariables.isConfiguredUsbAudioOutput(VID, PID)).isTrue();
        assertThat(GeneralVariables.isConfiguredUsbAudioInput(VID, PID)).isFalse();
    }

    @Test
    public void output_matchesWhenSelectedAsUsbDirectWithSameVidPid() {
        GeneralVariables.audioOutputDeviceId = -1;
        GeneralVariables.usbAudioOutputVendorId = VID;
        GeneralVariables.usbAudioOutputProductId = PID;

        assertThat(GeneralVariables.isConfiguredUsbAudioOutput(VID, PID)).isTrue();
    }
}
