package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM tests for {@link HamRecorder#phoneMicCaptureInUse} — the decision
 * behind the voice-command button's "phone mic in use by FT8 RX" gate.
 */
public class HamRecorderMicGateTest {

    @Test
    public void systemMicCaptureBlocksVoiceCommands() {
        // Running, mic source, AudioRecord path (incl. Android-routed USB input).
        assertThat(HamRecorder.phoneMicCaptureInUse(true, true, false)).isTrue();
    }

    @Test
    public void directUsbCaptureLeavesMicFree() {
        // Direct-libusb USB audio: no AudioRecord exists, recognizer is safe.
        assertThat(HamRecorder.phoneMicCaptureInUse(true, true, true)).isFalse();
    }

    @Test
    public void lanAudioSourceLeavesMicFree() {
        // ICOM WiFi / Flex network audio: MicRecorder is stopped.
        assertThat(HamRecorder.phoneMicCaptureInUse(true, false, false)).isFalse();
    }

    @Test
    public void stoppedRecorderLeavesMicFree() {
        assertThat(HamRecorder.phoneMicCaptureInUse(false, true, false)).isFalse();
    }
}
