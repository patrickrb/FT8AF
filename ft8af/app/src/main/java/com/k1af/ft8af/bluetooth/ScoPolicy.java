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
}
