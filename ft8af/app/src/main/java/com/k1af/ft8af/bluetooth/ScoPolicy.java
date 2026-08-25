package com.k1af.ft8af.bluetooth;

import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.ControlMode;

/**
 * Decides when the app may touch the phone's Bluetooth SCO (hands-free call
 * audio) link. Opening SCO knocks any connected Bluetooth audio device out of
 * A2DP music mode — a car head unit pauses whatever is playing — so SCO must
 * only ever be toggled when the rig's audio actually flows over Bluetooth,
 * i.e. when the user selected the Bluetooth connect mode (issue: car stereo
 * pause/unpause loop while the app TXed over a USB-connected rig).
 */
public final class ScoPolicy {
    private ScoPolicy() {
    }

    /**
     * Mirror of {@code android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO} (a stable framework
     * constant = 7), kept as a literal so this class stays Android-free and unit-testable.
     */
    public static final int TYPE_BLUETOOTH_SCO = 7;

    /**
     * Whether the operator's chosen audio input/output device is a Bluetooth SCO (hands-free)
     * endpoint — the signal #723 was missing. Selecting a BT headset as the FT8 mic/speaker is
     * an explicit request to route audio over it, unlike a car merely paired for music, so it
     * is safe to bring SCO up in that case even outside Bluetooth <em>rig</em> mode.
     */
    public static boolean audioSelectionNeedsHeadsetMode(int inputDeviceType,
                                                         int outputDeviceType) {
        return inputDeviceType == TYPE_BLUETOOTH_SCO || outputDeviceType == TYPE_BLUETOOTH_SCO;
    }

    /**
     * Whether the TX/RX cycle needs to toggle SCO around PTT (stop before
     * transmit, restart after). Only in Bluetooth connect mode; there,
     * non-CAT control always needs it, and CAT control needs it unless the
     * rig carries audio over the CAT link itself.
     */
    public static boolean needControlSco(int connectMode, int controlMode,
                                         boolean hasRig, boolean rigSupportsWaveOverCat) {
        if (connectMode != ConnectMode.BLUE_TOOTH) {
            return false;
        }
        if (controlMode != ControlMode.CAT) {
            return true;
        }
        return hasRig && !rigSupportsWaveOverCat;
    }

    /**
     * Whether launch should force Bluetooth headset mode (SCO on). A connected
     * Bluetooth audio profile alone is not enough — the phone may simply be
     * paired to a car or headphones for music.
     */
    public static boolean shouldEnterHeadsetMode(int connectMode, boolean btAudioProfileConnected) {
        return connectMode == ConnectMode.BLUE_TOOTH && btAudioProfileConnected;
    }

    /**
     * Headset-mode decision that also accounts for the selected audio devices (issue #723):
     * enter SCO when a Bluetooth rig is in use <em>or</em> the operator picked a Bluetooth-SCO
     * mic/speaker — but only while a Bluetooth audio profile is actually connected, so a
     * stale device id can't force SCO on with nothing paired.
     */
    public static boolean shouldEnterHeadsetMode(int connectMode, boolean btAudioProfileConnected,
                                                 int inputDeviceType, int outputDeviceType) {
        if (!btAudioProfileConnected) {
            return false;
        }
        return connectMode == ConnectMode.BLUE_TOOTH
                || audioSelectionNeedsHeadsetMode(inputDeviceType, outputDeviceType);
    }

    /** {@link #headsetModeAction}: nothing to do. */
    public static final int HEADSET_MODE_KEEP = 0;
    /** {@link #headsetModeAction}: bring SCO up and rebuild capture. */
    public static final int HEADSET_MODE_ENTER = 1;
    /** {@link #headsetModeAction}: take SCO down and rebuild capture. */
    public static final int HEADSET_MODE_LEAVE = 2;
    /** {@link #headsetModeAction}: SCO is already gone — just forget that we entered it. */
    public static final int HEADSET_MODE_FORGET = 3;

    /**
     * What {@code refreshBluetoothHeadsetMode()} should do, given whether headset mode is
     * wanted ({@link #shouldEnterHeadsetMode}), whether we believe we entered it
     * ({@code enteredByUs}, the cached flag), and whether SCO is <em>actually</em> on right
     * now ({@code AudioManager.isBluetoothScoOn()}).
     *
     * <p>The cached flag alone isn't trustworthy: SCO drops on its own when the headset
     * disconnects, and {@code setBlueToothOn()} can fail, so a stale {@code true} would make
     * every later refresh skip re-entering and leave the selected BT mic/speaker dead until
     * restart (Copilot review on #723). Cross-checking the real SCO state fixes that while
     * still avoiding the disruptive stop+start on every settings tap when SCO is really up.
     *
     * <p>A Bluetooth <em>rig</em> owns SCO for its TX/RX path, so headset mode is never left
     * from here while one is connected — but a headset that dropped and came back still
     * re-enters, because {@code want && !scoOn} always yields {@link #HEADSET_MODE_ENTER}.
     */
    public static int headsetModeAction(boolean want, boolean enteredByUs, boolean scoOn,
                                        boolean bluetoothRig) {
        if (want) {
            return (enteredByUs && scoOn) ? HEADSET_MODE_KEEP : HEADSET_MODE_ENTER;
        }
        if (!enteredByUs || bluetoothRig) {
            return HEADSET_MODE_KEEP;
        }
        return scoOn ? HEADSET_MODE_LEAVE : HEADSET_MODE_FORGET;
    }
}
