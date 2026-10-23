package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link MainViewModel#shouldPauseRxCaptureForTx} -- the decision
 * behind pausing RX capture for the duration of one TX "over"/TUNE key-down
 * (real-hardware finding: on at least one phone's USB-audio HAL, opening TX
 * playback on a USB rig while RX capture from the same USB rig is still open
 * fails silently at the ALSA proxy -- confirmed via logcat
 * ("pcm_is_ready() failed: cannot set hw params: I/O error", then
 * "write(), mPcm is NULL!" on every subsequent write) even though FT8AF's
 * generated waveform measured full-scale all the way to the AudioTrack
 * write call. A reference app (QFT8) that drives the same radio to full
 * output does exactly this: close capture, play, reopen capture).
 */
public class MainViewModelRxTxUsbConflictTest {

    private static final int VID_A = 0x0483;
    private static final int PID_A = 0xA34C;
    private static final int VID_B = 0x10C4;
    private static final int PID_B = 0xEA60;

    @Test
    public void micSourceSharingTheUsbDevice_pauses() {
        assertThat(MainViewModel.shouldPauseRxCaptureForTx(
                /* rxIsMicSource= */ true, /* rxIsUsbDirect= */ false,
                /* txIsUsbDirectOutput= */ false,
                VID_A, PID_A, VID_A, PID_A)).isTrue();
    }

    @Test
    public void lanRig_neverPaused() {
        // LAN-connected rigs never touch this HAL at all, and HamRecorder's
        // doOnWaveDataReceived gates on isRunning -- pausing here would
        // spuriously drop their audio too, not just this HAL's.
        assertThat(MainViewModel.shouldPauseRxCaptureForTx(
                /* rxIsMicSource= */ false, /* rxIsUsbDirect= */ false,
                /* txIsUsbDirectOutput= */ false,
                VID_A, PID_A, VID_A, PID_A)).isFalse();
    }

    @Test
    public void rxUsbDirectCapture_neverPaused() {
        // Raw-libusb RX capture has no ALSA proxy to conflict with; that path
        // is also independently broken for other reasons and shouldn't be
        // conflated with this fix.
        assertThat(MainViewModel.shouldPauseRxCaptureForTx(
                /* rxIsMicSource= */ true, /* rxIsUsbDirect= */ true,
                /* txIsUsbDirectOutput= */ false,
                VID_A, PID_A, VID_A, PID_A)).isFalse();
    }

    @Test
    public void txUsbDirectOutput_neverPaused() {
        // USB-Direct TX output is a separate, already-broken path
        // (LIBUSB_ERROR_IO) -- don't conflate the two bugs.
        assertThat(MainViewModel.shouldPauseRxCaptureForTx(
                /* rxIsMicSource= */ true, /* rxIsUsbDirect= */ false,
                /* txIsUsbDirectOutput= */ true,
                VID_A, PID_A, VID_A, PID_A)).isFalse();
    }

    @Test
    public void differentUsbDevices_neverPaused() {
        // Two distinct USB devices (or a USB mic with a non-USB/system
        // output) have no reason to share one ALSA card.
        assertThat(MainViewModel.shouldPauseRxCaptureForTx(
                /* rxIsMicSource= */ true, /* rxIsUsbDirect= */ false,
                /* txIsUsbDirectOutput= */ false,
                VID_A, PID_A, VID_B, PID_B)).isFalse();
    }

    @Test
    public void neitherVidPidSet_neverPaused() {
        // 0:0 is "no USB device configured" (phone mic / default speaker on
        // both sides) -- matching on 0:0 would be a false positive.
        assertThat(MainViewModel.shouldPauseRxCaptureForTx(
                /* rxIsMicSource= */ true, /* rxIsUsbDirect= */ false,
                /* txIsUsbDirectOutput= */ false,
                0, 0, 0, 0)).isFalse();
    }

    @Test
    public void sameVendorDifferentProduct_neverPaused() {
        assertThat(MainViewModel.shouldPauseRxCaptureForTx(
                /* rxIsMicSource= */ true, /* rxIsUsbDirect= */ false,
                /* txIsUsbDirectOutput= */ false,
                VID_A, PID_A, VID_A, PID_B)).isFalse();
    }
}
