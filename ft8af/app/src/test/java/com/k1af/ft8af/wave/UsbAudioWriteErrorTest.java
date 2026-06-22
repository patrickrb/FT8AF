package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link UsbAudioDevice#describeLibusbWriteError(int)} — the human-readable
 * label attached to a failed {@code nativeWrite} in the debug log.
 *
 * <p>Background: when the libusb native TX write fails, the bare {@code rc=-4}
 * in the log gave no clue whether the device vanished, the endpoint claim was
 * stale, or it was a transient I/O error — all of which present as the rig
 * keying up and transmitting silence. Naming the libusb_error code makes the
 * dropped cycle diagnosable. {@code nativeWrite} returns 0 or a (negative)
 * libusb_error code, so anything else is reported as UNKNOWN rather than guessed.
 */
public class UsbAudioWriteErrorTest {

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
    public void unexpectedPositiveCode_isUnknownNotGuessed() {
        // nativeWrite on this branch never returns a positive code; if a
        // differently-built lib ever does, label it honestly rather than
        // pretending to know what it means.
        assertThat(UsbAudioDevice.describeLibusbWriteError(5))
                .isEqualTo("rc=5 UNKNOWN");
    }

    @Test
    public void unmappedNegativeCode_isUnknown() {
        assertThat(UsbAudioDevice.describeLibusbWriteError(-42))
                .isEqualTo("rc=-42 UNKNOWN");
    }
}
