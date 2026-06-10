package com.bg7yoz.ft8cn.rigs;

/**
 * Pure decision logic for the CAT connection liveness watchdog, kept separate from
 * MainViewModel so it can be unit-tested without Android.
 *
 * <p>The problem it solves: a Bluetooth (or other) CAT link can stay "connected" at the
 * transport level after the radio itself is powered off — the BT module keeps the socket
 * up, so no I/O error ever fires and the status chip stays green while frequency writes
 * silently go nowhere. The watchdog actively probes the rig (a periodic frequency read) and
 * watches for replies; if the rig has gone quiet for too long it flips the chip to error.
 *
 * <p>Two guards keep it from crying wolf:
 * <ul>
 *   <li>It never judges during transmit (we don't poll while keyed).</li>
 *   <li>It only arms once the rig has answered at least once since connecting
 *       ({@code sawResponse}). A rig that never echoes a frequency read (or a transport that
 *       doesn't support it) therefore can't be falsely marked dead — the watchdog simply
 *       stays dormant for it.</li>
 * </ul>
 */
public final class CatLiveness {

    private CatLiveness() {}

    /** Default quiet period after which a previously-responsive rig is considered dead. */
    public static final long DEFAULT_TIMEOUT_MS = 8000;

    /**
     * Whether we should send a liveness probe (a frequency read) this tick: only when
     * connected and not transmitting.
     */
    public static boolean shouldProbe(boolean connected, boolean transmitting) {
        return connected && !transmitting;
    }

    /**
     * Whether the rig should now be treated as stale (unresponsive) and the chip flipped to
     * error.
     *
     * @param connected      whether the transport still reports connected
     * @param transmitting   whether we are mid-transmit (never judge during TX)
     * @param sawResponse    whether the rig has replied at least once since connecting
     * @param nowMs          current time (ms)
     * @param lastResponseMs time of the last reply from the rig (ms)
     * @param timeoutMs      allowed quiet period before declaring the rig dead
     */
    public static boolean isRigStale(boolean connected, boolean transmitting,
                                     boolean sawResponse, long nowMs,
                                     long lastResponseMs, long timeoutMs) {
        if (!connected) return false;     // not connected -> nothing to police
        if (transmitting) return false;   // don't judge during TX (no polling then)
        if (!sawResponse) return false;   // never armed -> can't false-positive a quiet rig
        return (nowMs - lastResponseMs) > timeoutMs;
    }
}
