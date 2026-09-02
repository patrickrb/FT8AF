package com.k1af.ft8af.bluetooth;

/**
 * Remembers whether <em>this app</em> held a Bluetooth SCO session at the moment
 * an over (or the tune carrier) was keyed, so the TX audio path can still act on
 * that fact after the keying sequence has already asked for the link to drop.
 *
 * <p>Why a latch rather than a live query (Copilot review on #790): the keying
 * path in {@code MainViewModel.beginKeying()} calls {@code stopSco()} <em>before</em>
 * PTT, then the TX worker sleeps the PTT settle delay (100 ms by default) before
 * it builds the {@code AudioTrack} and asks whether to steer Default output to
 * A2DP. {@code stopSco()} posts {@code ScoLinkTracker.requestOff()} to the main
 * looper, which flips the tracked state to DISCONNECTED as soon as it runs — so
 * by the time the question is asked the honest answer from the tracker is
 * "no", the override never fires in exactly the CAT/RTS/DTR + Bluetooth case it
 * exists for, and if the main looper happens to be busy the answer becomes a
 * race. Meanwhile the OS is still tearing the SCO link down and keeps
 * {@code USAGE_MEDIA} on the hands-free route (issue #759 follow-up), which is
 * the failure the steering fixes.
 *
 * <p>So the keying path snapshots the tracker's answer here first, the TX path
 * reads the snapshot, and the unkey path clears it once the post-TX SCO restart
 * has been requested. A mid-slot message swap (#704) re-keys nothing, so the
 * latch simply carries across it.
 *
 * <p>Pure Java, no Android types: the ordering is what matters and it is
 * unit-tested against the real {@link ScoLinkTracker}.
 */
public final class TxScoLatch {

    private volatile boolean heldForTx;

    /**
     * Record the tracker's answer at keying time. Must be called <em>before</em>
     * {@code stopSco()} is requested, on the thread that keys the rig.
     *
     * @param scoUpOrPendingNow {@code ScoLinkCoordinator.isLinkUpOrPending()}
     *                          as read at this instant
     */
    public void onKeying(boolean scoUpOrPendingNow) {
        heldForTx = scoUpOrPendingNow;
    }

    /**
     * Whether our SCO link was up (or coming up) when the current over was keyed.
     * Stable for the whole over regardless of what the tracker says now.
     */
    public boolean heldForTx() {
        return heldForTx;
    }

    /** The over is done and the post-TX {@code startSco()} has been requested. */
    public void onUnkeyed() {
        heldForTx = false;
    }
}
