package com.k1af.ft8af.rigs;

/**
 * State for the CAT liveness watchdog, kept pure (no Android, no timers) so the whole
 * arm / trip / recover lifecycle is unit-testable. {@link CatLiveness} holds the
 * stateless predicates; this class sequences them across ticks.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #start(long)} on every (re)connect: nothing has been heard yet, so the
 *       stale judgement is disarmed until the rig actually answers.</li>
 *   <li>{@link #onResponse(long)} for every genuine reply parsed from the rig. This is
 *       the ONLY thing that arms the watchdog — the app pushing its own commanded dial
 *       through {@code BaseRig.setCommandedFreq} must never count, otherwise a rig that
 *       accepts commands but never answers a frequency read (one-way links, write-only
 *       CAT modes, transports that drop replies) is armed by our own write and declared
 *       dead {@code timeoutMs} later while CAT is in fact working. That was the "red chip
 *       but CAT works" report (issue #781).</li>
 *   <li>{@link #tick(boolean, boolean, long)} every watchdog period: says whether to send
 *       a probe and whether this tick crosses into (or out of) the stale state.</li>
 * </ul>
 *
 * <p>Tripping is a <em>transition</em>, not a terminal state: probing continues after a
 * trip, and the next reply {@linkplain #onResponse recovers} the link so the caller can
 * flip the status chip back to connected. A rig that goes quiet for one window (busy in
 * an ATU tune, a menu, a Bluetooth hiccup, a mangled reply) used to stay red until the
 * operator tapped the chip; now the chip heals itself as soon as the rig answers again.
 */
public final class CatLivenessTracker {

    /** What a tick decided beyond "send a probe". */
    public enum Event {
        /** Nothing changed. */
        NONE,
        /** The rig just crossed the quiet threshold: flip the chip to error. */
        TRIPPED
    }

    /** Result of one watchdog tick. */
    public static final class Tick {
        /** Send a frequency read this tick. */
        public final boolean probe;
        public final Event event;
        /** How long the rig has been quiet at this tick, for logging. */
        public final long quietMs;

        Tick(boolean probe, Event event, long quietMs) {
            this.probe = probe;
            this.event = event;
            this.quietMs = quietMs;
        }
    }

    private final long timeoutMs;
    private boolean running;
    private long lastResponseMs;
    private boolean sawResponse;
    private boolean wasTransmitting;
    private boolean tripped;

    public CatLivenessTracker(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** Arm for a fresh connection: forget everything heard on the previous one. */
    public synchronized void start(long nowMs) {
        running = true;
        lastResponseMs = nowMs;
        sawResponse = false;
        wasTransmitting = false;
        tripped = false;
    }

    /**
     * The connection went away (disconnect, I/O error, teardown). Clears the
     * "rig has answered" flag so {@link #hasSeenResponse()} can't report a stale
     * true after the rig is unplugged, and ignores any reply that straggles in.
     */
    public synchronized void stop() {
        running = false;
        sawResponse = false;
        tripped = false;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** Whether the rig has answered at least once on the current connection. */
    public synchronized boolean hasSeenResponse() {
        return sawResponse;
    }

    /** Whether the watchdog currently considers the rig unresponsive. */
    public synchronized boolean isTripped() {
        return tripped;
    }

    /**
     * A genuine reply from the rig arrived.
     *
     * @return true when this reply ends a tripped (stale) period, i.e. the caller should
     *         restore the connected state it flipped to error on {@link Event#TRIPPED}
     */
    public synchronized boolean onResponse(long nowMs) {
        if (!running) return false;
        lastResponseMs = nowMs;
        sawResponse = true;
        if (tripped) {
            tripped = false;
            return true;
        }
        return false;
    }

    /**
     * One watchdog period elapsed.
     *
     * @param connected    whether the transport still reports connected
     * @param transmitting whether we are mid-transmit (no probing or judging then)
     * @param nowMs        current time
     */
    public synchronized Tick tick(boolean connected, boolean transmitting, long nowMs) {
        if (!running) return new Tick(false, Event.NONE, 0);
        boolean probe = CatLiveness.shouldProbe(connected, transmitting);
        // Transmit freezes lastResponseMs (we don't probe while keyed). On the TX->RX
        // edge, restart the quiet window from now so the probe sent this tick has time
        // to reply before we judge staleness — otherwise a >timeout FT8 over falsely
        // trips (PR #450).
        if (CatLiveness.shouldRearmAfterTx(wasTransmitting, transmitting)) {
            lastResponseMs = nowMs;
        }
        wasTransmitting = transmitting;
        Event event = Event.NONE;
        if (!tripped && CatLiveness.isRigStale(connected, transmitting, sawResponse,
                nowMs, lastResponseMs, timeoutMs)) {
            tripped = true;
            event = Event.TRIPPED;
        }
        return new Tick(probe, event, nowMs - lastResponseMs);
    }
}
