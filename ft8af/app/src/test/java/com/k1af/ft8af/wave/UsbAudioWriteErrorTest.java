package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link UsbAudioDevice#describeLibusbWriteError(int)} — the human-readable
 * label attached to a failed {@code nativeWrite} in the debug log — and
 * {@link UsbAudioDevice#shouldFallbackToUsbRequest(int, long)} — whether a failed
 * native write may be retried through the UsbRequest loop.
 *
 * <p>Background: when the libusb native TX write fails, the bare {@code rc=-4}
 * in the log gave no clue whether the device vanished, the endpoint claim was
 * stale, or it was a transient I/O error — all of which present as the rig
 * keying up and transmitting silence. {@code nativeWrite} returns 0, a negative
 * {@code libusb_error} (setup failure), or a positive
 * {@code libusb_transfer_status} when a transfer dies after streaming started —
 * the field failure mode from the 2026-07-03 POTA log was {@code rc=5}
 * TRANSFER_NO_DEVICE (device fell off the bus mid-TX, RF into the USB link),
 * which used to be logged as UNKNOWN.
 */
public class UsbAudioWriteErrorTest {

    // ---- describeLibusbWriteError -------------------------------------------

    @Test
    public void ioError_named() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(-1)).isEqualTo("rc=-1 IO");
    }

    @Test
    public void noDevice_named() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(-4))
                .isEqualTo("rc=-4 NO_DEVICE");
    }

    @Test
    public void notFound_named() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(-5))
                .isEqualTo("rc=-5 NOT_FOUND");
    }

    @Test
    public void noMem_named() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(-11))
                .isEqualTo("rc=-11 NO_MEM");
    }

    @Test
    public void success_named() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(0))
                .isEqualTo("rc=0 SUCCESS");
    }

    @Test
    public void transferNoDevice_named() {
        // The real-world drop: onOutputComplete stores the transfer's status
        // (positive libusb_transfer_status) as firstError when the device
        // leaves the bus mid-write.
        assertThat(UsbAudioDevice.describeLibusbWriteError(5))
                .isEqualTo("rc=5 TRANSFER_NO_DEVICE");
    }

    @Test
    public void transferError_named() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(1))
                .isEqualTo("rc=1 TRANSFER_ERROR");
    }

    @Test
    public void transferCancelled_named() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(3))
                .isEqualTo("rc=3 TRANSFER_CANCELLED");
    }

    @Test
    public void transferStall_named() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(4))
                .isEqualTo("rc=4 TRANSFER_STALL");
    }

    @Test
    public void unmappedPositiveCode_isUnknown() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(7))
                .isEqualTo("rc=7 UNKNOWN");
    }

    @Test
    public void unmappedNegativeCode_isUnknown() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(-42))
                .isEqualTo("rc=-42 UNKNOWN");
    }

    // ---- shouldFallbackToUsbRequest -----------------------------------------
    // The UsbRequest fallback restarts the message from byte 0, so it is only
    // allowed for failures that happened before anything went to air.

    @Test
    public void fallback_immediateSetupFailure_allowed() {
        // The fallback's original purpose: libusb can't run on this kernel and
        // fails instantly — Android's own iso path may still work.
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(-99, 20)).isTrue();
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(-12, 0)).isTrue();
    }

    @Test
    public void fallback_midStreamFailure_denied() {
        // 6.5s into a 12.6s message (the field case): part of the audio already
        // transmitted; a restart-from-zero would send an undecodable overlap.
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(1, 6_500)).isFalse();
    }

    @Test
    public void fallback_justPastThreshold_denied() {
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(
                -1, UsbAudioDevice.MAX_FALLBACK_ELAPSED_MS + 1)).isFalse();
    }

    @Test
    public void fallback_atThreshold_allowed() {
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(
                -1, UsbAudioDevice.MAX_FALLBACK_ELAPSED_MS)).isTrue();
    }

    @Test
    public void fallback_deviceGone_deniedRegardlessOfTiming() {
        // Device off the bus: the fallback would fail too (and a fast NO_DEVICE
        // must not slip under the elapsed-time threshold).
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(5, 10)).isFalse();
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(-4, 10)).isFalse();
    }

    @Test
    public void fallback_userCancelled_denied() {
        // STOP pressed: not a fault, nothing to retry.
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(3, 10)).isFalse();
    }

    @Test
    public void fallback_success_neverFallsBack() {
        assertThat(UsbAudioDevice.shouldFallbackToUsbRequest(0, 10)).isFalse();
    }
}
