package com.k1af.ft8af.ft8listener;

/**
 * Lets the transmitter wait for the fast decode of the slot that just ended, instead of
 * keying up while it is still running and acting on the result a cycle late.
 *
 * <p>The fast pass is delivered about {@code earlyDecodeMillis} plus decode time into the
 * slot, and key-up happens roughly 0.45 s into the next one — so the decode has under two
 * seconds to finish. Whether it does depends entirely on how busy the band is. Measured
 * across one morning's activation:
 *
 * <table>
 *   <tr><td></td><td>busy (09:15-09:45)</td><td>quiet (09:45 on)</td></tr>
 *   <tr><td>decodes kept per cycle</td><td>14.2</td><td>6.3</td></tr>
 *   <tr><td>deliveries landing after key-up</td><td>35</td><td>0</td></tr>
 * </table>
 *
 * <p>That is backwards from what an operator needs: the busier the band, the more likely
 * the app is to miss the station calling them. A pileup is precisely when auto-answer
 * matters most. Those late deliveries are no longer lost — they are stashed and replayed —
 * but a replay acts on the NEXT cycle, so a third of callers were answered 15 s late.
 *
 * <p>There is roughly 1.9 s of unused headroom before the audio slack runs out, so the fix
 * is to spend it only when there is something to wait for. On a quiet band the decode has
 * already finished and {@link #awaitIdle} returns immediately, leaving key-up timing
 * untouched. On a busy band the transmitter waits the few hundred milliseconds the decode
 * still needs and then sends the RIGHT message, in the right cycle.
 *
 * <p>A fixed delay would have been the wrong shape — it would tax every transmission to fix
 * a load-dependent problem. This is conditional, so it costs nothing when idle.
 */
public final class FastDecodeGate {

    private final Object lock = new Object();
    private boolean inFlight;

    /**
     * Marks a fast (non-deep) pass as pending. Called by whichever thread is about to
     * SPAWN the decode thread — deliberately not by the decode thread itself, which may
     * not be scheduled before the transmitter reaches its key-up check and would leave it
     * looking at an idle gate. See the comment at the {@code begin()} call site in
     * {@code FT8SignalListener.decodeFt8}.
     */
    public void begin() {
        synchronized (lock) {
            inFlight = true;
        }
    }

    /**
     * Called by the decode thread once the fast pass has been DELIVERED — not merely
     * decoded. Waiting only until decode finished would still race the sequencer, which
     * acts inside the delivery callback.
     *
     * <p>Also called from a finally around the whole decode thread body, so a throw before
     * delivery cannot strand the gate in flight. Idempotent, so the two paths can both fire.
     */
    public void end() {
        synchronized (lock) {
            inFlight = false;
            lock.notifyAll();
        }
    }

    /** Whether a fast pass is currently running. */
    public boolean inFlight() {
        synchronized (lock) {
            return inFlight;
        }
    }

    /**
     * Block until no fast pass is in flight, or {@code deadlineEpochMs} passes.
     *
     * <p>Returns immediately when nothing is running, which is the common case on a quiet
     * band — so this must stay cheap.
     *
     * @param deadlineEpochMs absolute wall-clock instant to give up at; see
     *                        {@code FT8TransmitSignal.keyUpHoldDeadline}
     * @param nowMs           current wall clock
     * @return true if the decode finished (or was never running) before the deadline
     */
    public boolean awaitIdle(long deadlineEpochMs, long nowMs) {
        synchronized (lock) {
            long remaining = deadlineEpochMs - nowMs;
            while (inFlight && remaining > 0) {
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return !inFlight;
                }
                // Recompute from the clock rather than trusting a single wake: wait() can
                // return spuriously, and notifyAll() from end() must not be mistaken for
                // the deadline having arrived.
                remaining = deadlineEpochMs - System.currentTimeMillis();
            }
            return !inFlight;
        }
    }
}
