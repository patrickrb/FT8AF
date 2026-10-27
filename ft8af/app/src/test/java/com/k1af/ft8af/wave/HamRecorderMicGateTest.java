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

    /**
     * {@link HamRecorder.TxPauseState} -- resumeAfterTx() must only restart
     * capture when pauseForTx() actually stopped a running one, never reviving
     * capture the operator had deliberately turned off.
     */
    @Test
    public void txPause_resumesOnlyWhenPauseActuallyStoppedCapture() {
        HamRecorder.TxPauseState running = new HamRecorder.TxPauseState();
        assertThat(running.onPause(true)).isTrue();
        assertThat(running.onResume()).isTrue();

        HamRecorder.TxPauseState stopped = new HamRecorder.TxPauseState();
        assertThat(stopped.onPause(false)).isFalse();
        assertThat(stopped.onResume()).isFalse();
    }

    /**
     * A tune carrier and an FT8 over can overlap: the over's key-down can land
     * before the tune's key-up. Only the last resume may restart capture, and
     * it must still restart it -- the second pause sees capture already
     * stopped by the first and must not record "wasn't running".
     */
    @Test
    public void txPause_nestedPausesRestartOnlyOnTheLastResume() {
        HamRecorder.TxPauseState s = new HamRecorder.TxPauseState();
        assertThat(s.onPause(true)).isTrue();   // tune key-down stops capture
        assertThat(s.onPause(false)).isFalse(); // over key-down: already paused
        assertThat(s.onResume()).isFalse();     // tune key-up: over still keyed
        assertThat(s.onResume()).isTrue();      // over key-up restarts capture
    }

    @Test
    public void txPause_resumeWithoutPauseIsIgnored() {
        HamRecorder.TxPauseState s = new HamRecorder.TxPauseState();
        assertThat(s.onResume()).isFalse();
        // and doesn't unbalance a later pause/resume pair
        assertThat(s.onPause(true)).isTrue();
        assertThat(s.onResume()).isTrue();
    }
}
