package com.k1af.ft8af.ft8transmit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Lifecycle + safety state machine for the Tune function (issue #408).
 *
 * <p>A continuous carrier is the most dangerous thing a station-control app
 * can emit (100% duty cycle, open-ended), so the invariants live here, in a
 * plain class with an injected clock:
 * <ul>
 *   <li><b>Hard max-on timeout</b>: {@link #shouldContinue()} goes false once
 *       the deadline passes even if the operator does nothing. The deadline
 *       counts from {@link #markCarrierOn()}; before that, setup is bounded by
 *       max-on plus {@link #SETUP_GRACE_MS}, and the carrier deadline never
 *       extends past that.</li>
 *   <li><b>Single flight</b>: {@link #tryStart} is an atomic check-and-claim;
 *       a double start is refused.</li>
 *   <li><b>Deterministic stop reason</b>: the first stop cause wins and is
 *       reported for logging/toasts.</li>
 *   <li><b>Session scoping</b>: each {@link #tryStartSession} returns a new id.
 *       A worker passes its id to the scoped calls, so a stopped worker still
 *       tearing down can't stop, time out, or re-arm the session that replaced
 *       it (issue #982: stop, then a quick re-tap, killed the new tune).</li>
 * </ul>
 *
 * The audio worker owns the actual teardown (PTT off, track release) in a
 * {@code finally}; this class only decides <i>whether</i> the tone keeps
 * going. Thread-safe: the UI thread starts/stops while the audio worker polls.
 */
public final class TuneController {

    /** Default hard cap on a single tune carrier. */
    public static final int DEFAULT_MAX_ON_SECONDS = 10;
    /** Bounds for the configurable max-on time. */
    public static final int MIN_MAX_ON_SECONDS = 2;
    public static final int MAX_MAX_ON_SECONDS = 60;

    /** Stop reasons, used for logging and operator feedback. */
    public static final String STOP_USER = "user";
    public static final String STOP_TIMEOUT = "timeout";
    public static final String STOP_TX = "tx-start";
    public static final String STOP_ERROR = "error";

    /** Returned by {@link #tryStartSession} when a session is already active. */
    public static final long NO_SESSION = 0;

    /**
     * How long setup (thread start, PTT key-down, audio focus, AudioTrack build)
     * may take before the carrier comes up. The max-on clock starts at
     * {@link #markCarrierOn()}, so setup doesn't eat into the operator's tune
     * time (issue #982); until then the session is bounded by max-on plus this
     * grace, so a hung setup still can't keep a session alive.
     */
    public static final long SETUP_GRACE_MS = 2000;

    /** Millisecond clock; injected so tests drive time explicitly. */
    public interface Clock {
        long nowMs();
    }

    private final Clock clock;

    private boolean active = false;
    private long sessionStartMs;
    private long startedAtMs;
    private long deadlineMs;
    private long maxOnMs;
    private boolean carrierOn = false;
    private String stopReason = null;
    // Current session id; bumped by every successful start.
    private long session = NO_SESSION;
    // The session before the current one, and how it ended. A stopped worker
    // reads its reason after the operator may already have started a new one.
    private long previousSession = NO_SESSION;
    private String previousStopReason = null;

    /**
     * How long after PTT drops a tune still exempts SWR readings from the halt.
     * CAT meter replies lag the carrier, so a high reading taken at the end of
     * the tune can land after key-up (issue #943). Safe to be generous: an FT8
     * over revokes the exemption the moment it keys (see
     * {@link #revokeSwrExemption()} and {@link #suppressesSwrHalt(boolean)}).
     */
    public static final long SWR_SETTLE_MS = 3000;

    /**
     * Upper bound on how long a stopped tune may stay "keyed" for SWR purposes.
     * The real ramp-out and key-up take well under a second; if teardown hangs
     * (a CAT write blocked on a dropped link), the exemption lapses here rather
     * than leaving SWR protection off for as long as the worker is stuck.
     */
    public static final long KEYUP_TAIL_MAX_MS = 5000;

    // PTT state, tracked apart from {@link #active}: active drops the instant
    // the operator taps stop (or the timeout fires), but the carrier keeps
    // going while the tone ramps out, the track stops, and PTT is released.
    // A count, not a flag: a quick stop-then-tune can start a new worker while
    // the previous one is still in its teardown, and the old worker's key-up
    // must not end the new one's exemption.
    private int keyedWorkers = 0;
    private long stoppedAtMs = Long.MIN_VALUE;
    private long unkeyedAtMs = Long.MIN_VALUE;
    // Set when an FT8 over keys; cleared by the next tryStart.
    private boolean swrExemptionRevoked = false;
    // Tune worker threads, oldest first, until seen terminated; see registerWorker.
    private final List<Thread> workers = new ArrayList<>();

    /**
     * Wait up to {@code timeoutMs} for the previous tune worker to finish its
     * teardown (ramp-out, track release, PTT key-up), so a re-tap keys only
     * after the old carrier is off: keyUp(old) always precedes keyDown(new).
     * Returns true when it finished (or there was none); false on timeout or
     * interrupt, in which case the caller must not key.
     */
    public static boolean awaitPreviousWorker(Thread previous, long timeoutMs) {
        return awaitPreviousWorkers(
                previous == null ? Collections.emptyList() : Collections.singletonList(previous),
                timeoutMs);
    }

    /**
     * {@link #awaitPreviousWorker} for every worker {@link #registerWorker}
     * returned, within one shared {@code timeoutMs} bound.
     */
    public static boolean awaitPreviousWorkers(List<Thread> previous, long timeoutMs) {
        long deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L;
        for (Thread t : previous) {
            if (t == Thread.currentThread()) {
                continue;
            }
            long leftMs = (deadlineNs - System.nanoTime()) / 1_000_000L;
            try {
                if (leftMs > 0) {
                    t.join(leftMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (t.isAlive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Record {@code worker} as the newest tune worker and return the earlier
     * ones that haven't finished, for it to wait on before keying. All of them,
     * not just the last: a re-tap refused because worker A hung leaves A alive,
     * and the next re-tap must still wait for A, not just for the refused one.
     */
    public synchronized List<Thread> registerWorker(Thread worker) {
        for (Iterator<Thread> it = workers.iterator(); it.hasNext(); ) {
            if (it.next().getState() == Thread.State.TERMINATED) {
                it.remove();
            }
        }
        List<Thread> previous = new ArrayList<>(workers);
        workers.add(worker);
        return previous;
    }

    public TuneController(Clock clock) {
        this.clock = clock;
    }

    /** Clamp a configured max-on time (e.g. a persisted setting) to the legal range. */
    public static int clampMaxOnSeconds(int seconds) {
        if (seconds < MIN_MAX_ON_SECONDS) {
            return seconds <= 0 ? DEFAULT_MAX_ON_SECONDS : MIN_MAX_ON_SECONDS;
        }
        return Math.min(seconds, MAX_MAX_ON_SECONDS);
    }

    /** {@link #tryStartSession} for callers that don't need the session id. */
    public synchronized boolean tryStart(int maxOnSeconds) {
        return tryStartSession(maxOnSeconds) != NO_SESSION;
    }

    /**
     * Atomically claim a new tune session and return its id, or
     * {@link #NO_SESSION} when one is already active (double start is a no-op
     * for the caller). Arms only the provisional setup deadline (max-on plus
     * {@link #SETUP_GRACE_MS}); the max-on clock itself starts at
     * {@link #markCarrierOn()}.
     */
    public synchronized long tryStartSession(int maxOnSeconds) {
        if (active) {
            return NO_SESSION;
        }
        previousSession = session;
        previousStopReason = stopReason;
        session++;
        sessionStartMs = clock.nowMs();
        startedAtMs = sessionStartMs;
        maxOnMs = clampMaxOnSeconds(maxOnSeconds) * 1000L;
        deadlineMs = sessionStartMs + maxOnMs + SETUP_GRACE_MS;
        carrierOn = false;
        stopReason = null;
        swrExemptionRevoked = false;
        active = true;
        return session;
    }

    /** Whether {@code id} is the latest session (active or not). */
    public synchronized boolean ownsSession(long id) {
        return id != NO_SESSION && id == session;
    }

    /**
     * Run {@code publish} (posting the Tune button state) only if session
     * {@code id} may still set it: {@code tuning=true} needs the session still
     * running, {@code false} needs it still the latest. Checked under this
     * lock, so a start or stop can't slip in between check and post, and a
     * worker whose key-down returned after it was stopped and replaced can't
     * flip the button back to Stop.
     */
    public synchronized boolean publishTuningState(long id, boolean tuning, Runnable publish) {
        boolean current = tuning ? shouldContinue(id) : ownsSession(id);
        if (current) {
            publish.run();
        }
        return current;
    }

    /**
     * The carrier is on the air: start the max-on clock from now. Returns the
     * setup latency (ms since {@link #tryStart}) for logging, or -1 when nothing
     * changed: no active session, or the carrier was already marked on (a second
     * call never pushes the deadline out). If setup outran the provisional
     * deadline, the session times out here instead of getting a fresh cap. The
     * carrier deadline never moves past the provisional one, so a setup slower
     * than {@link #SETUP_GRACE_MS} shortens the carrier rather than stretching
     * the keyed session past max-on plus the grace.
     */
    public synchronized long markCarrierOn() {
        return markCarrierOn(session);
    }

    /** {@link #markCarrierOn()} for session {@code id}; -1 if it isn't current. */
    public synchronized long markCarrierOn(long id) {
        if (id != session || !active || carrierOn) {
            return -1;
        }
        long now = clock.nowMs();
        if (now >= deadlineMs) {
            stopInternal(STOP_TIMEOUT);
            return -1;
        }
        carrierOn = true;
        startedAtMs = now;
        deadlineMs = Math.min(now + maxOnMs, deadlineMs);
        return now - sessionStartMs;
    }

    /**
     * Polled by the audio worker between chunks: true while the tone should
     * keep playing. Enforces the max-on deadline as a side effect — this is
     * the code-level guarantee that a forgotten tune cannot run past the cap.
     */
    public synchronized boolean shouldContinue() {
        return shouldContinue(session);
    }

    /** {@link #shouldContinue()} for session {@code id}; false once it's been replaced. */
    public synchronized boolean shouldContinue(long id) {
        if (id != session || !active) {
            return false;
        }
        if (clock.nowMs() >= deadlineMs) {
            stopInternal(STOP_TIMEOUT);
            return false;
        }
        return true;
    }

    /**
     * Request the session end (operator tap, FT8 TX starting, worker error).
     * The first reason wins; later calls are no-ops. Safe when not active.
     */
    public synchronized void requestStop(String reason) {
        requestStop(session, reason);
    }

    /**
     * {@link #requestStop(String)} for session {@code id}. A no-op when a newer
     * session has replaced it: a worker's teardown must never stop its successor.
     */
    public synchronized void requestStop(long id, String reason) {
        if (id == session && active) {
            stopInternal(reason);
        }
    }

    private void stopInternal(String reason) {
        active = false;
        stoppedAtMs = clock.nowMs();
        if (stopReason == null) {
            stopReason = reason;
        }
    }

    public synchronized boolean isActive() {
        return active;
    }

    /** The audio worker is about to key PTT for the tune carrier. */
    public synchronized void markKeyed() {
        keyedWorkers++;
    }

    /** The audio worker has released PTT for the tune carrier. */
    public synchronized void markUnkeyed() {
        if (keyedWorkers > 0) {
            keyedWorkers--;
            if (keyedWorkers == 0) {
                unkeyedAtMs = clock.nowMs();
            }
        }
    }

    /**
     * An FT8 over is keying: from here on every SWR reading belongs to it, so
     * the tune's tail and settle window no longer exempt anything. Lasts until
     * the next {@link #tryStart}.
     */
    public synchronized void revokeSwrExemption() {
        swrExemptionRevoked = true;
    }

    /**
     * True while an SWR reading should be treated as coming from the tune carrier,
     * not a fault: the session is active, PTT is still keyed for the tune (at most
     * {@link #KEYUP_TAIL_MAX_MS} after the stop), or PTT dropped less than
     * {@link #SWR_SETTLE_MS} ago. High SWR is expected while tuning, and halting
     * would set the lockout and block the next tune.
     *
     * <p>Never true while an FT8 over is in progress ({@code ft8TxInProgress}) or
     * after one keyed ({@link #revokeSwrExemption()}): a real over's high SWR must
     * always halt, even if it follows a tune within the windows above.
     */
    public synchronized boolean suppressesSwrHalt(boolean ft8TxInProgress) {
        if (ft8TxInProgress || swrExemptionRevoked) {
            return false;
        }
        if (active) {
            return true;
        }
        long now = clock.nowMs();
        if (keyedWorkers > 0) {
            return stoppedAtMs != Long.MIN_VALUE && now - stoppedAtMs < KEYUP_TAIL_MAX_MS;
        }
        return unkeyedAtMs != Long.MIN_VALUE && now - unkeyedAtMs < SWR_SETTLE_MS;
    }

    /** Why the last session ended, or null if none ended yet. */
    public synchronized String lastStopReason() {
        return stopReason;
    }

    /**
     * Why session {@code id} ended: null while it's still running, or when it's
     * older than the previous session (a worker that never keyed).
     */
    public synchronized String stopReason(long id) {
        if (id == session) {
            return stopReason;
        }
        return id == previousSession ? previousStopReason : null;
    }

    /**
     * Milliseconds until the deadline; 0 when inactive or already past it.
     * Before the carrier is on, never more than the full max-on: the setup
     * grace is a safety bound, not tune time to show the operator.
     */
    public synchronized long remainingMs() {
        if (!active) {
            return 0;
        }
        return Math.max(0, Math.min(maxOnMs, deadlineMs - clock.nowMs()));
    }

    /** Milliseconds since the carrier came on (or the session started); 0 when inactive. */
    public synchronized long elapsedMs() {
        if (!active) {
            return 0;
        }
        return Math.max(0, clock.nowMs() - startedAtMs);
    }
}
