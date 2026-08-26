package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link UsbAudioDevice#audioControlInterfaceIndex(int[], int[])} — which interface
 * {@code detachKernelAudioDriver()} force-claims to get the kernel's {@code snd-usb-audio}
 * driver off a rig's sound card.
 *
 * <p>Background (2026-08-25 bench log): the app used to claim only the UAC AudioStreaming
 * interfaces. For {@code snd-usb-audio} that is a silent no-op — the card is bound to the
 * AudioControl interface — so Android kept the CM108 registered as a {@code usb_headset}
 * and every sound it routed there (the app's own QSO-complete alert ding, a BT car-kit
 * re-route, a nav prompt) flipped the playback alt-setting under our iso URBs; the TX died
 * ~280 ms in with {@code rc=5 TRANSFER_NO_DEVICE} while the device stayed on the bus. The
 * driver only really disconnects (and the ALSA card with it) when the AudioControl
 * interface is claimed, so picking that interface correctly is the whole fix.
 */
public class UsbAudioControlInterfaceTest {

    private static final int AUDIO = UsbAudioDevice.USB_CLASS_AUDIO;
    private static final int CONTROL = UsbAudioDevice.USB_SUBCLASS_AUDIOCONTROL;
    private static final int STREAMING = UsbAudioDevice.USB_SUBCLASS_AUDIOSTREAMING;
    private static final int HID = 0x03;

    /** CM108/CM108B (Digirig, many rig interfaces): AC, AS out, AS in, HID. */
    @Test
    public void cm108Layout_controlIsInterfaceZero() {
        int[] classes = {AUDIO, AUDIO, AUDIO, HID};
        int[] subclasses = {CONTROL, STREAMING, STREAMING, 0};
        assertThat(UsbAudioDevice.audioControlInterfaceIndex(classes, subclasses)).isEqualTo(0);
    }

    /** Composite CAT+audio dongles put a CDC/vendor interface first; the AC index moves. */
    @Test
    public void compositeDevice_controlNotFirst() {
        int[] classes = {0x02, 0x0A, AUDIO, AUDIO, AUDIO};
        int[] subclasses = {0x02, 0x00, CONTROL, STREAMING, STREAMING};
        assertThat(UsbAudioDevice.audioControlInterfaceIndex(classes, subclasses)).isEqualTo(2);
    }

    /** Only the first AC interface is returned when a device exposes two audio functions. */
    @Test
    public void twoAudioFunctions_firstControlWins() {
        int[] classes = {AUDIO, AUDIO, AUDIO, AUDIO};
        int[] subclasses = {CONTROL, STREAMING, CONTROL, STREAMING};
        assertThat(UsbAudioDevice.audioControlInterfaceIndex(classes, subclasses)).isEqualTo(0);
    }

    /** A streaming-only descriptor set (no AC interface) must not be mistaken for control. */
    @Test
    public void streamingOnly_returnsMinusOne() {
        int[] classes = {AUDIO, AUDIO};
        int[] subclasses = {STREAMING, STREAMING};
        assertThat(UsbAudioDevice.audioControlInterfaceIndex(classes, subclasses)).isEqualTo(-1);
    }

    /** Subclass 1 on a non-audio class (e.g. HID boot subclass) is not an AC interface. */
    @Test
    public void controlSubclassOnOtherClass_ignored() {
        int[] classes = {HID, 0xFF};
        int[] subclasses = {CONTROL, CONTROL};
        assertThat(UsbAudioDevice.audioControlInterfaceIndex(classes, subclasses)).isEqualTo(-1);
    }

    @Test
    public void noInterfaces_returnsMinusOne() {
        assertThat(UsbAudioDevice.audioControlInterfaceIndex(new int[0], new int[0]))
                .isEqualTo(-1);
    }

    /** Mismatched lengths only scan the common prefix rather than throwing. */
    @Test
    public void mismatchedLengths_scansCommonPrefix() {
        int[] classes = {HID, AUDIO, AUDIO};
        int[] subclasses = {0, CONTROL};
        assertThat(UsbAudioDevice.audioControlInterfaceIndex(classes, subclasses)).isEqualTo(1);

        int[] shortClasses = {HID};
        int[] longSubclasses = {0, CONTROL, CONTROL};
        assertThat(UsbAudioDevice.audioControlInterfaceIndex(shortClasses, longSubclasses))
                .isEqualTo(-1);
    }
}
