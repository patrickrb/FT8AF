package com.k1af.ft8af.rigs;

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
 *   <li>The <em>stale</em> decision only arms once the rig has answered at least once since
 *       connecting ({@code sawResponse}). Probes are still sent every tick (see
 *       {@link #shouldProbe}); it's only the "declare dead" judgement that waits for a first
 *       reply, so a rig that never answers a frequency read (or a transport that doesn't
 *       support it) can't be falsely marked dead.</li>
 * </ul>
 *
 * <p>A third guard handles the transmit→receive edge (see {@link #shouldRearmAfterTx}). We
 * don't probe during TX, so the last-response timestamp freezes for the whole transmit
 * window. An FT8 over is 12.64&nbsp;s — longer than {@link #DEFAULT_TIMEOUT_MS} — so the very
 * first tick after TX would otherwise judge the (pre-TX) timestamp as stale and flip the chip
 * red even though the rig is perfectly responsive. On the TX→RX edge the caller re-arms the
 * timestamp so the quiet timer restarts from the moment transmit ends, giving the freshly-sent
 * probe time to get a reply.
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
     * Whether the last-response timestamp should be re-armed this tick because transmit just
     * ended. True only on the falling edge (was transmitting last tick, not transmitting now).
     *
     * <p>Probing is suppressed during TX, so {@code lastResponseMs} is frozen for the whole
     * over. FT8 transmits for 12.64&nbsp;s — longer than {@link #DEFAULT_TIMEOUT_MS} — so
     * without this re-arm the first post-TX tick sees a &gt;8&nbsp;s-old timestamp and
     * {@link #isRigStale} fires a false positive. Re-arming to "now" on the TX→RX edge restarts
     * the quiet window from the end of transmit, so the probe sent this same tick has time to
     * reply before any staleness judgement.
     *
     * @param wasTransmitting whether we were transmitting on the previous tick
     * @param transmitting    whether we are transmitting now
     */
    public static boolean shouldRearmAfterTx(boolean wasTransmitting, boolean transmitting) {
        return wasTransmitting && !transmitting;
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
