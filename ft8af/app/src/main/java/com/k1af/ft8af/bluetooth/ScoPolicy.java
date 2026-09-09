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
     * ({@code enteredByUs}, the cached flag), and what the app's own SCO coordinator
     * ({@code ScoLinkCoordinator}) says about the link.
     *
     * <p>The cached flag alone isn't trustworthy: SCO drops on its own when the headset
     * disconnects, and {@code setBlueToothOn()} can fail, so a stale {@code true} would make
     * every later refresh skip re-entering and leave the selected BT mic/speaker dead until
     * restart (Copilot review on #723). The cross-check deliberately uses the coordinator's
     * tracked state rather than {@code AudioManager.isBluetoothScoOn()}: that getter only
     * mirrors the legacy force-use flag, which {@code setSpeakerphoneOn(false)} (issued right
     * after {@code setBluetoothScoOn(true)} in the SCO sink) clears on newer Android builds —
     * so it can read {@code false} while the link is up. Deciding on it made a USB/VOX user's
     * "deselect BT headset" pick {@link #HEADSET_MODE_FORGET} instead of
     * {@link #HEADSET_MODE_LEAVE}, leaving the coordinator wanted and SCO on (Copilot review
     * on #778).
     *
     * <p>A Bluetooth <em>rig</em> owns SCO for its TX/RX path, so headset mode is never left
     * from here while one is connected — but a headset that dropped and came back still
     * re-enters, because {@code want && !linkUp} always yields {@link #HEADSET_MODE_ENTER}.
     *
     * @param want         headset mode is wanted for the current rig + audio selection
     * @param enteredByUs  the cached "we entered headset mode" flag
     * @param linkUp       the coordinator has the link CONNECTING or CONNECTED (so a fresh
     *                     request would only restart a link that is being built)
     * @param linkHeld     the coordinator still holds an SCO request — up, retrying, or gave
     *                     up but never told to stop; it must be told to stop to really leave
     * @param bluetoothRig the rig itself is connected over Bluetooth
     */
    public static int headsetModeAction(boolean want, boolean enteredByUs, boolean linkUp,
                                        boolean linkHeld, boolean bluetoothRig) {
        if (want) {
            return (enteredByUs && linkUp) ? HEADSET_MODE_KEEP : HEADSET_MODE_ENTER;
        }
        if (!enteredByUs || bluetoothRig) {
            return HEADSET_MODE_KEEP;
        }
        return linkHeld ? HEADSET_MODE_LEAVE : HEADSET_MODE_FORGET;
    }

    /** {@link #profileChangeAction}: Bluetooth rig — enter headset mode directly. */
    public static final int PROFILE_ENTER = 0;
    /** {@link #profileChangeAction}: Bluetooth rig — leave headset mode directly. */
    public static final int PROFILE_LEAVE = 1;
    /**
     * {@link #profileChangeAction}: not a Bluetooth rig — run the selection-aware
     * {@code refreshBluetoothHeadsetMode()} so a selected BT headset that reconnects (or
     * goes away) is handled the same way a settings change would be.
     */
    public static final int PROFILE_REFRESH = 2;

    /**
     * What the Bluetooth broadcast receiver should do when a headset/A2DP profile connection
     * state changes. A Bluetooth rig keeps its original direct on/off behaviour. Any other
     * rig mode used to ignore profile changes entirely, so for the #723 case (USB/VOX rig +
     * BT headset selected as mic/speaker) a headset that reconnected after the SCO retry
     * budget was exhausted never re-entered headset mode and RX stayed dead until restart or
     * another settings change (Copilot review on #778). Routing it through the refresh keeps
     * the car-stereo protection: the refresh only acts when a BT-SCO device is selected.
     */
    public static int profileChangeAction(int connectMode, boolean profileConnected) {
        if (connectMode == ConnectMode.BLUE_TOOTH) {
            return profileConnected ? PROFILE_ENTER : PROFILE_LEAVE;
        }
        return PROFILE_REFRESH;
    }
}
