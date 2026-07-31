package com.k1af.ft8af.connector;

/**
 * Decides how {@link CableConnector} reacts when the CAT / serial read loop
 * reports an error, so a single-packet glitch (marginal connector, cheap OTG
 * hub, RFI into the cable during TX) no longer drops the whole session and
 * strands the user on the manual <em>"tap to retry"</em> chip.
 *
 * <p>Pure logic, no Android types — unit-tested. {@link CableConnector} stays a
 * thin wrapper that consults these helpers, matching
 * {@link com.k1af.ft8af.wave.UsbCaptureRetryPolicy} on the audio side.
 *
 * <p>Two questions:
 *
 * <ol>
 *   <li><b>Is the error transient or fatal?</b> Most serial read-loop
 *       {@link Exception}s are a brief I/O stall the device recovers from by
 *       re-enumerating with the same VID/PID. Only a message that names the
 *       device as genuinely gone or access-denied is fatal.</li>
 *   <li><b>Should we auto-reconnect, and after how long?</b> A bounded number of
 *       attempts with exponential backoff. If they are exhausted we surface the
 *       manual retry state; a single glitch is absorbed silently.</li>
 * </ol>
 */
public final class CatReconnectPolicy {

    private CatReconnectPolicy() {}

    /** How a serial read-loop error should be handled. */
    public enum Kind {
        /** Recoverable — try an automatic reconnect before bothering the user. */
        TRANSIENT,
        /** The device is really gone / access denied — surface the error now. */
        FATAL
    }

    /** What {@link CableConnector} should do about a serial read-loop error. */
    public enum Action {
        /** A deliberate user disconnect caused it — expected, do nothing. */
        IGNORE,
        /** Transient glitch with budget left — attempt a bounded auto-reconnect. */
        RECONNECT,
        /** Fatal, or reconnect budget exhausted — surface the manual retry state. */
        SURFACE
    }

    /**
     * Decides how to react to a serial read-loop error.
     *
     * <p>A deliberate user disconnect closes the port to interrupt the blocking
     * read, which itself raises an {@code IOException} on the read loop. That
     * error is <em>expected</em> and must be ignored rather than surfaced as a
     * "Lost connection" error state. Otherwise a transient error with reconnect
     * budget left reconnects; a fatal error or an exhausted budget surfaces.
     *
     * @param userDisconnected whether the user asked to disconnect
     * @param kind             the classification of the error
     * @param attemptsSoFar    auto-reconnects already tried this burst ({@code 0} first)
     */
    public static Action decide(boolean userDisconnected, Kind kind, int attemptsSoFar) {
        if (userDisconnected) return Action.IGNORE;
        if (shouldAutoReconnect(kind, attemptsSoFar)) return Action.RECONNECT;
        return Action.SURFACE;
    }

    /**
     * How long a freshly-opened port must survive before the link counts as recovered and
     * the escalating backoff resets.
     *
     * <p>This is the fix for the reconnect storm measured on the 2026-07-31 activation:
     * {@code CableConnector} treated {@code connect()} returning true — the port merely
     * <em>opening</em> — as success, ended the burst there, and reset the escalation. With
     * a link that opened and immediately errored again, every error therefore started a
     * fresh burst at attempt 1, so the backoff never got past its first step. Result:
     * <strong>13,190 port opens in 88 minutes</strong> (2.5/s), inter-arrival pinned at
     * 0.51–0.53 s — exactly {@link #BASE_BACKOFF_MS} plus the open — and the
     * budget-exhausted path never reached once.
     *
     * <p>A port that opens is not a link that works. Only elapsed time proves that, so the
     * burst now persists across opens and resets only after the connection has genuinely
     * held for this long.
     */
    public static final long STABLE_CONNECTION_MS = 10_000;

    /**
     * Whether a connection that has just failed had lasted long enough to count as a
     * recovery, meaning the next failure starts a fresh escalation rather than continuing
     * the previous burst.
     *
     * @param nowMs         when the failure arrived
     * @param connectedAtMs when the port was last opened, or {@code 0} if never
     */
    public static boolean shouldResetBurst(long nowMs, long connectedAtMs) {
        if (connectedAtMs <= 0) return true;
        long held = nowMs - connectedAtMs;
        // A backwards clock correction must not make a brief connection look stable.
        if (held < 0) return false;
        return held >= STABLE_CONNECTION_MS;
    }

    /**
     * Attempts over which the backoff escalates before pinning at {@link #MAX_BACKOFF_MS}.
     *
     * <p>No longer a give-up budget. A transient error now retries indefinitely, because
     * giving up strands the operator: the storm this policy failed to prevent was at least
     * landing CAT commands intermittently, and surfacing the manual <em>tap to retry</em>
     * chip mid-activation would have taken CAT away entirely until the user noticed and
     * tapped. Escalating to one attempt per {@link #MAX_BACKOFF_MS} is a ~20x reduction in
     * churn (2.5/s measured, down to 0.125/s) while the link still recovers unattended.
     *
     * <p>A FATAL classification still surfaces immediately — a device that has left the bus
     * or refused permission will not come back by retrying.
     */
    public static final int MAX_AUTO_RECONNECT_ATTEMPTS = 5;

    /** First backoff step; also the debounce window that swallows a lone glitch. */
    public static final long BASE_BACKOFF_MS = 500;

    /** Backoff ceiling so a persistently-flaky link doesn't spin the port open. */
    public static final long MAX_BACKOFF_MS = 8_000;

    /**
     * Classifies a serial read-loop error. Fatal only when the message clearly
     * says the device left the bus or access was refused ({@code ENODEV},
     * "no device", "not found", "permission", "access", "disconnected"). Every
     * other {@link Exception} — a bare {@code IOException}, a timeout, a null
     * message — is treated as transient and worth an auto-reconnect.
     */
    public static Kind classify(Exception e) {
        if (e == null) return Kind.TRANSIENT;
        String msg = e.getMessage();
        if (msg == null) return Kind.TRANSIENT;
        String m = msg.toLowerCase(java.util.Locale.US);
        if (m.contains("enodev")
                || m.contains("no device")
                || m.contains("no such device")
                || m.contains("not found")
                || m.contains("permission")
                || m.contains("access")
                || m.contains("disconnected")) {
            return Kind.FATAL;
        }
        return Kind.TRANSIENT;
    }

    /**
     * Whether an automatic reconnect should be attempted.
     *
     * <p>Transient errors retry without limit — see {@link #MAX_AUTO_RECONNECT_ATTEMPTS}
     * for why the budget was dropped. {@code attemptsSoFar} no longer gates the decision;
     * it survives only so callers reading this alongside {@link #backoffMs} see the same
     * burst counter, and so the signature stays stable for existing callers.
     *
     * @param kind          the classification of the error
     * @param attemptsSoFar how many auto-reconnects have already been tried in
     *                      this burst ({@code 0} on the first error)
     */
    public static boolean shouldAutoReconnect(Kind kind, int attemptsSoFar) {
        return kind != Kind.FATAL;
    }

    /**
     * Delay before the given auto-reconnect attempt. Exponential
     * ({@code 500&nbsp;ms, 1&nbsp;s, 2&nbsp;s, 4&nbsp;s, 8&nbsp;s}) capped at
     * {@link #MAX_BACKOFF_MS}. The first attempt ({@code attempt == 1}) also acts
     * as the debounce window: a lone glitch waits {@link #BASE_BACKOFF_MS} and is
     * silently recovered before any error reaches the UI.
     *
     * @param attempt 1-based attempt number; {@code <= 0} yields no wait
     */
    public static long backoffMs(int attempt) {
        if (attempt <= 0) return 0;
        int shift = Math.min(attempt - 1, 20);
        long delay = BASE_BACKOFF_MS << shift;
        if (delay <= 0 || delay > MAX_BACKOFF_MS) return MAX_BACKOFF_MS;
        return delay;
    }

    // ---- PTT fail-safe ------------------------------------------------------

    /**
     * Most times a PTT-<em>off</em> control-line toggle / CAT command is re-sent
     * when the write fails on a dropped port. Leaving the rig keyed is the worst
     * failure mode (it can transmit indefinitely and desense the band), so
     * PTT-off is retried where an ordinary command would just be dropped.
     */
    public static final int MAX_PTT_OFF_RETRIES = 3;

    /**
     * Whether a failed PTT write should be retried. Only PTT-<em>off</em> is
     * retried (fail-safe: never leave the rig keyed); PTT-on failures are left to
     * the normal QSO sequencer, which re-keys next cycle.
     *
     * @param turningOn     whether this write was keying the rig (PTT on)
     * @param writeSucceeded whether the just-attempted write reported success
     * @param attemptsSoFar how many retries have already happened ({@code 0} on
     *                      the first failure)
     */
    public static boolean shouldRetryPtt(boolean turningOn, boolean writeSucceeded,
                                         int attemptsSoFar) {
        if (writeSucceeded) return false;
        if (turningOn) return false;
        return attemptsSoFar < MAX_PTT_OFF_RETRIES;
    }
}
