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

    /**
     * Most bounded auto-reconnect attempts before falling back to the manual
     * <em>tap to retry</em> chip. Chosen so a device that re-enumerates within a
     * few seconds is picked back up, but a truly-gone cable stops churning.
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
     * @param kind          the classification of the error
     * @param attemptsSoFar how many auto-reconnects have already been tried in
     *                      this burst ({@code 0} on the first error)
     */
    public static boolean shouldAutoReconnect(Kind kind, int attemptsSoFar) {
        if (kind == Kind.FATAL) return false;
        return attemptsSoFar < MAX_AUTO_RECONNECT_ATTEMPTS;
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
