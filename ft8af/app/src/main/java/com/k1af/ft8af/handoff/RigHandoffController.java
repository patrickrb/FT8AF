package com.k1af.ft8af.handoff;

/**
 * Decides what this app does with the rig when it, or a sibling app, comes to the
 * foreground. Android-free: the rig, the broadcasts and the delays are injected, so the
 * whole state machine is unit-tested on the JVM. See {@link RigHandoffProtocol} for why.
 *
 * <p>Rule: the app in front of the operator owns the rig.
 * <ul>
 *   <li>{@link #onForegrounded()} — tell the siblings to let go; if we had lent the rig
 *       out, take it back shortly after.</li>
 *   <li>{@link #onTakeoverRequested} — a sibling is in front now: stop any transmission,
 *       give the unkey time to reach the rig, close the CAT link and USB audio, then
 *       confirm with RELEASED.</li>
 *   <li>{@link #onReleased} — a sibling confirmed it let go: connect now rather than
 *       waiting on a retry timer.</li>
 * </ul>
 *
 * <p>All entry points are expected on one thread (the main thread in the app).
 */
public final class RigHandoffController {

    /** What the controller needs from the app's radio layer. */
    public interface Rig {
        /** A transmission or tune carrier is on the air right now. */
        boolean isTransmitting();

        /** Stop any transmission and drop PTT. */
        void stopTransmit();

        /** Close the CAT link and release USB audio. The gate is already set. */
        void release();

        /** (Re)open the CAT link and audio input. The gate is already clear. */
        void reclaim();
    }

    /** Outgoing protocol messages. */
    public interface Transport {
        void sendTakeover();

        void sendReleased(String toPackage);
    }

    /** Delayed execution on the controller's thread. */
    public interface Scheduler {
        void postDelayed(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    /** Told when the handoff state changes, for the UI and the service notification. */
    public interface Listener {
        /** We let go of the rig for {@code toPackage}. */
        void onYielded(String toPackage);

        /** We may use the rig again; {@code fromPackage} is who released it, or null. */
        void onRegained(String fromPackage);
    }

    /**
     * How long a cancelled transmission gets to send its PTT-off before the CAT port is
     * closed under it. Closing first would leave the rig keyed with nobody left to unkey.
     */
    public static final long UNKEY_GRACE_MS = 500;

    /**
     * How long after asking for the rig back we reconnect without waiting for a RELEASED
     * — the sibling may have exited, or be an older build that never answers.
     */
    public static final long RECLAIM_DELAY_MS = 750;

    private final String selfPackage;
    private final Rig rig;
    private final Transport transport;
    private final Scheduler scheduler;
    private final Listener listener;

    private long lastForegroundedAtMs;
    private Runnable pendingRelease;
    private Runnable pendingReclaim;

    public RigHandoffController(String selfPackage, Rig rig, Transport transport,
                                Scheduler scheduler, Listener listener) {
        this.selfPackage = selfPackage;
        this.rig = rig;
        this.transport = transport;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    /**
     * This app's UI just became visible to the operator.
     *
     * @param nowMs current wall clock; remembered so an older takeover delivered late
     *              cannot undo this claim
     */
    public void onForegrounded(long nowMs) {
        lastForegroundedAtMs = nowMs;
        boolean wasYielded = RigHandoffGate.isYielded();
        // A release still waiting out its unkey grace must not fire after we are back.
        cancelPendingRelease();
        RigHandoffGate.clear();
        transport.sendTakeover();
        if (wasYielded) {
            listener.onRegained(null);
            scheduleReclaim();
        }
    }

    /**
     * A sibling asked for the rig.
     *
     * @param fromPackage    the package named in the message
     * @param verifiedSender the platform-attested sender, or null when unavailable
     * @param sentAtMs       when the message says it was sent
     * @param nowMs          current wall clock
     * @return whether the request was honoured
     */
    public boolean onTakeoverRequested(String fromPackage, String verifiedSender,
                                       long sentAtMs, long nowMs) {
        if (!RigHandoffProtocol.shouldHonor(
                selfPackage, fromPackage, verifiedSender, sentAtMs, nowMs)) {
            return false;
        }
        if (RigHandoffProtocol.predatesOwnClaim(sentAtMs, lastForegroundedAtMs)) {
            return false;
        }
        // We are not coming back for the rig after all.
        cancelPendingReclaim();
        if (RigHandoffGate.isYielded() && pendingRelease == null) {
            // Already off the rig: nothing to release and nothing to confirm. Answering
            // again would make the claimant rebuild its audio input on every one of its
            // own foreground transitions — a capture gap in the middle of a decode.
            RigHandoffGate.yieldTo(fromPackage);
            return true;
        }
        // Gate first: from here no retry path may reopen the rig.
        RigHandoffGate.yieldTo(fromPackage);
        cancelPendingRelease();
        if (rig.isTransmitting()) {
            rig.stopTransmit();
            pendingRelease = () -> {
                pendingRelease = null;
                finishRelease(fromPackage);
            };
            scheduler.postDelayed(pendingRelease, UNKEY_GRACE_MS);
        } else {
            finishRelease(fromPackage);
        }
        return true;
    }

    /** A sibling confirmed it released the rig to us. */
    public boolean onReleased(String fromPackage, String verifiedSender,
                              long sentAtMs, long nowMs) {
        if (!RigHandoffProtocol.shouldHonor(
                selfPackage, fromPackage, verifiedSender, sentAtMs, nowMs)) {
            return false;
        }
        // Stale: we have since handed the rig on to someone else.
        if (RigHandoffGate.isYielded()) return false;
        cancelPendingReclaim();
        listener.onRegained(fromPackage);
        rig.reclaim();
        return true;
    }

    private void finishRelease(String toPackage) {
        // Foregrounded again while the unkey grace ran — keep the rig.
        if (!RigHandoffGate.isYielded()) return;
        rig.release();
        transport.sendReleased(toPackage);
        listener.onYielded(toPackage);
    }

    private void scheduleReclaim() {
        cancelPendingReclaim();
        pendingReclaim = () -> {
            pendingReclaim = null;
            if (!RigHandoffGate.isYielded()) rig.reclaim();
        };
        scheduler.postDelayed(pendingReclaim, RECLAIM_DELAY_MS);
    }

    private void cancelPendingRelease() {
        if (pendingRelease != null) {
            scheduler.cancel(pendingRelease);
            pendingRelease = null;
        }
    }

    private void cancelPendingReclaim() {
        if (pendingReclaim != null) {
            scheduler.cancel(pendingReclaim);
            pendingReclaim = null;
        }
    }
}
