package com.k1af.ft8af;

/**
 * Tracks the one piece of rig state we must never lose track of: whether we have
 * keyed the transmitter and not yet confirmed it back off.
 *
 * <p>The transmit path keys via CAT, plays ~12.6s of audio, then unkeys via CAT.
 * If the USB link dies anywhere in between, the unkey write fails and the rig is
 * left transmitting. Field log (POTA, 2026-07-23) shows this three times in one
 * session: an OTG brown-out re-enumerated the bus mid-transmission and the rig
 * stayed keyed for 97s, 30s, and — when the failing write also killed the
 * process — 89s. In every case the port came back within ~2s, so the fix is not
 * to write harder at a dead port but to <em>remember the debt</em> and settle it
 * the moment a link exists again.
 *
 * <p>Usage: {@link #onKeyed()} when PTT goes on, {@link #onUnkeyAttempted(boolean)}
 * with the write's success after PTT-off, and {@link #needsUnkey()} at every
 * opportunity a link might be back (reconnect, slot boundary). The latch stays
 * armed until an unkey write is confirmed, so a failed attempt simply leaves it
 * armed for the next opportunity.
 *
 * <p>Deliberately not "is the rig keyed" — we cannot know that once the link is
 * down. It is "we owe this rig an unkey", which is always safe to act on: CAT
 * PTT-off is idempotent, so re-sending it to an already-receiving rig is a no-op.
 *
 * <p>Thread-safe: keying runs on the TX pool thread, retries on the main thread
 * and the audio thread.
 */
public final class PttSafetyLatch {

    private volatile boolean armed;

    /**
     * Record that we have keyed the transmitter. From here we owe an unkey until
     * one is confirmed, regardless of how transmission ends.
     */
    public synchronized void onKeyed() {
        armed = true;
    }

    /**
     * Record the outcome of an unkey attempt.
     *
     * @param writeSucceeded true when the CAT PTT-off write actually reached the
     *                       rig; false when the port was closed or the write
     *                       threw. Only a confirmed write disarms — a failed one
     *                       leaves the debt outstanding for {@link #needsUnkey()}.
     */
    public synchronized void onUnkeyAttempted(boolean writeSucceeded) {
        if (writeSucceeded) {
            armed = false;
        }
    }

    /**
     * Whether an unkey is still owed. Call at any point a CAT link may have come
     * back — reconnect, slot boundary — and send PTT-off if true.
     */
    public boolean needsUnkey() {
        return armed;
    }

    /**
     * Drop the debt without sending anything. For teardown paths where the rig
     * object itself is going away (disconnect, mode change), so a stale latch
     * cannot key-chase a rig we are no longer driving.
     */
    public synchronized void reset() {
        armed = false;
    }
}
