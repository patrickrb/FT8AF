package com.k1af.ft8af.ft8transmit;

/**
 * Lifecycle + safety state machine for the Tune function (issue #408).
 *
 * <p>A continuous carrier is the most dangerous thing a station-control app
 * can emit (100% duty cycle, open-ended), so the invariants live here, in a
 * plain class with an injected clock:
 * <ul>
 *   <li><b>Hard max-on timeout</b>: {@link #shouldContinue()} goes false once
 *       the deadline passes even if the operator does nothing.</li>
 *   <li><b>Single flight</b>: {@link #tryStart} is an atomic check-and-claim;
 *       a double start is refused.</li>
 *   <li><b>Deterministic stop reason</b>: the first stop cause wins and is
 *       reported for logging/toasts.</li>
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

    /** Millisecond clock; injected so tests drive time explicitly. */
    public interface Clock {
        long nowMs();
    }

    private final Clock clock;

    private boolean active = false;
    private long startedAtMs;
    private long deadlineMs;
    private String stopReason = null;

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

    /**
     * Atomically claim the tune session and arm the timeout. Returns false when
     * a session is already active (double start is a no-op for the caller).
     */
    public synchronized boolean tryStart(int maxOnSeconds) {
        if (active) {
            return false;
        }
        startedAtMs = clock.nowMs();
        deadlineMs = startedAtMs + clampMaxOnSeconds(maxOnSeconds) * 1000L;
        stopReason = null;
        active = true;
        return true;
    }

    /**
     * Polled by the audio worker between chunks: true while the tone should
     * keep playing. Enforces the max-on deadline as a side effect — this is
     * the code-level guarantee that a forgotten tune cannot run past the cap.
     */
    public synchronized boolean shouldContinue() {
        if (!active) {
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
        if (active) {
            stopInternal(reason);
        }
    }

    private void stopInternal(String reason) {
        active = false;
        if (stopReason == null) {
            stopReason = reason;
        }
    }

    public synchronized boolean isActive() {
        return active;
    }

    /** Why the last session ended, or null if none ended yet. */
    public synchronized String lastStopReason() {
        return stopReason;
    }

    /** Milliseconds until the deadline; 0 when inactive or already past it. */
    public synchronized long remainingMs() {
        if (!active) {
            return 0;
        }
        return Math.max(0, deadlineMs - clock.nowMs());
    }

    /** Milliseconds since the session started; 0 when inactive. */
    public synchronized long elapsedMs() {
        if (!active) {
            return 0;
        }
        return Math.max(0, clock.nowMs() - startedAtMs);
    }
}
