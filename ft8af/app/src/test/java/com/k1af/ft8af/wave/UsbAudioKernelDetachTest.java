package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.wave.UsbAudioDevice.KernelDetachPort;
import com.k1af.ft8af.wave.UsbAudioDevice.KernelDetachResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests {@link UsbAudioDevice#detachKernelAudioDriver(KernelDetachPort, boolean)} — the
 * stateful part of the fix for Android sounds killing a USB-direct TX: that the
 * AudioControl interface actually gets force-claimed, that the claim is skipped when the
 * RX session on the same device already holds it, and that a refused claim is reported
 * rather than pretended. {@link UsbAudioControlInterfaceTest} covers only the index
 * lookup; without this test the {@code forceClaim} call could be deleted and every test
 * would stay green.
 */
public class UsbAudioKernelDetachTest {

    private static final int AUDIO = UsbAudioDevice.USB_CLASS_AUDIO;
    private static final int CONTROL = UsbAudioDevice.USB_SUBCLASS_AUDIOCONTROL;
    private static final int STREAMING = UsbAudioDevice.USB_SUBCLASS_AUDIOSTREAMING;
    private static final int HID = 0x03;

    /** Scripted device: interface (class, subclass) pairs plus a canned claim answer. */
    private static final class FakePort implements KernelDetachPort {
        final int[] classes;
        final int[] subclasses;
        final boolean claimAnswer;
        final List<Integer> claimed = new ArrayList<>();

        FakePort(int[] classes, int[] subclasses, boolean claimAnswer) {
            this.classes = classes;
            this.subclasses = subclasses;
            this.claimAnswer = claimAnswer;
        }

        @Override public int interfaceCount() { return classes.length; }
        @Override public int interfaceClass(int index) { return classes[index]; }
        @Override public int interfaceSubclass(int index) { return subclasses[index]; }
        @Override public boolean forceClaim(int index) {
            claimed.add(index);
            return claimAnswer;
        }
    }

    private static FakePort cm108(boolean claimAnswer) {
        return new FakePort(
                new int[]{AUDIO, AUDIO, AUDIO, HID},
                new int[]{CONTROL, STREAMING, STREAMING, 0},
                claimAnswer);
    }

    @Test
    public void claimsTheAudioControlInterface_exactlyOnce() {
        FakePort port = cm108(true);
        assertThat(UsbAudioDevice.detachKernelAudioDriver(port, false))
                .isEqualTo(KernelDetachResult.CLAIMED);
        assertThat(port.claimed).containsExactly(0);
    }

    /** Composite CAT+audio device: the claim lands on the AC interface, not index 0. */
    @Test
    public void claimsControlInterface_whenNotFirst() {
        FakePort port = new FakePort(
                new int[]{0x02, 0x0A, AUDIO, AUDIO, AUDIO},
                new int[]{0x02, 0x00, CONTROL, STREAMING, STREAMING},
                true);
        assertThat(UsbAudioDevice.detachKernelAudioDriver(port, false))
                .isEqualTo(KernelDetachResult.CLAIMED);
        assertThat(port.claimed).containsExactly(2);
    }

    /** The per-cycle TX open must not steal the claim the RX session already holds. */
    @Test
    public void holderAlreadyDetached_skipsWithoutClaiming() {
        FakePort port = cm108(true);
        assertThat(UsbAudioDevice.detachKernelAudioDriver(port, true))
                .isEqualTo(KernelDetachResult.SKIPPED_HOLDER);
        assertThat(port.claimed).isEmpty();
    }

    @Test
    public void refusedClaim_reportedAsFailure() {
        FakePort port = cm108(false);
        assertThat(UsbAudioDevice.detachKernelAudioDriver(port, false))
                .isEqualTo(KernelDetachResult.CLAIM_FAILED);
        assertThat(port.claimed).containsExactly(0);
    }

    /** A streaming-only descriptor set: nothing to claim, and no claim is attempted. */
    @Test
    public void noControlInterface_claimsNothing() {
        FakePort port = new FakePort(
                new int[]{AUDIO, AUDIO},
                new int[]{STREAMING, STREAMING},
                true);
        assertThat(UsbAudioDevice.detachKernelAudioDriver(port, false))
                .isEqualTo(KernelDetachResult.NO_CONTROL_INTERFACE);
        assertThat(port.claimed).isEmpty();
    }

    @Test
    public void noInterfacesAtAll_claimsNothing() {
        FakePort port = new FakePort(new int[0], new int[0], true);
        assertThat(UsbAudioDevice.detachKernelAudioDriver(port, false))
                .isEqualTo(KernelDetachResult.NO_CONTROL_INTERFACE);
        assertThat(port.claimed).isEmpty();
    }
}
