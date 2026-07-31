package com.k1af.ft8af.rigs;

/**
 * Rate limiting for {@code MainViewModel.setOperationBand()} — the CAT retune that pushes
 * the app's dial and USB mode to the rig.
 *
 * <p>Motivating data (2026-07-30 POTA activation, from {@code debug.log}): {@code
 * setOperationBand()} ran in a continuous ~1 Hz loop for the ENTIRE session, re-sending
 * {@code FA014074000; MD0C; NA00;SH0117;} about 57 times a minute to a rig that was
 * already on that exact frequency and mode — {@code rig.getFreq} matched the target on
 * every single iteration. 20,124 occurrences across the pulled log, present in every POTA
 * session in it.
 *
 * <p><strong>This is containment, not a root-cause fix.</strong> The caller driving that
 * loop has not been identified. It is provably not the connect path (only 11 autoConnect
 * attempts in the whole window, and no connect/disconnect churn logged), not a band change
 * (no {@code bandSelect:} lines), and not self-triggering through {@code onFreqChanged}
 * ({@code BaseRig.setFreq} early-returns on an unchanged dial, and the dial never changed).
 * Two independent ~1.05 s series interleave about 0.53 s apart, one always observing the
 * rig connected and one always observing it disconnected, which suggests duplicated
 * observers or two live view-model instances rather than one runaway timer.
 *
 * <p>So this class does two things: makes the retune idempotent so the spam stops whatever
 * the caller turns out to be, and gives {@code setOperationBand()} a rate-limited
 * suppression log that names the caller — so the next activation's {@code debug.log}
 * identifies the culprit instead of leaving it to inference.
 */
public final class RetunePolicy {

    /**
     * How often an unchanged dial is re-asserted to the rig anyway. A push that changes
     * nothing is not useless — a rig can be moved at the front panel, or drop a command —
     * so we keep a slow heartbeat rather than going fully edge-triggered. 30 s is two
     * orders of magnitude below the observed 1 Hz loop and far above any legitimate
     * retune cadence.
     */
    public static final long REASSERT_INTERVAL_MS = 30_000L;

    /** Minimum gap between "suppressed N retunes" log lines, so the fix can't itself spam. */
    public static final long SUPPRESSION_LOG_INTERVAL_MS = 10_000L;

    /** {@link #shouldRetune} sentinel for "nothing pushed yet this session". */
    public static final long NO_PUSH = 0L;

    private RetunePolicy() {}

    /**
     * Whether this retune request should actually reach the rig.
     *
     * <p>Ordered so correctness always beats the rate limit: a genuinely new target, or a
     * rig that is not where we want it, is pushed immediately and unconditionally. Only a
     * request that is redundant in BOTH senses — same target as last time, and the rig
     * already reports being there — is subject to the reassert interval.
     *
     * @param requestedFreq  the dial we want the rig on ({@code GeneralVariables.band})
     * @param rigFreq        what the rig last reported ({@code baseRig.getFreq()})
     * @param lastPushedFreq the dial we last actually pushed, or {@link #NO_PUSH}
     * @param nowMs          current wall clock
     * @param lastPushAtMs   when we last actually pushed (meaningless if {@link #NO_PUSH})
     */
    public static boolean shouldRetune(long requestedFreq, long rigFreq, long lastPushedFreq,
                                       long nowMs, long lastPushAtMs) {
        // Never throttle the first push of a session: on connect the rig may still be on
        // whatever frequency it powered up on, and its cached freq may coincidentally
        // match ours without the mode ever having been sent.
        if (lastPushedFreq == NO_PUSH) return true;
        // A real band/dial change must go out at once — this is the whole point of the
        // call, and delaying it would leave the operator transmitting on the old dial.
        if (requestedFreq != lastPushedFreq) return true;
        // The rig disagrees with us (front-panel move, dropped command): correct it now.
        if (rigFreq != requestedFreq) return true;
        // Fully redundant. Re-assert only on the slow heartbeat.
        return nowMs - lastPushAtMs >= REASSERT_INTERVAL_MS;
    }

    /** Whether enough time has passed to emit another suppression summary. */
    public static boolean shouldLogSuppression(long nowMs, long lastLogAtMs) {
        return nowMs - lastLogAtMs >= SUPPRESSION_LOG_INTERVAL_MS;
    }

    /**
     * First stack frame outside {@code selfClassName} — i.e. whoever called the method
     * doing the logging. Returns {@code "unknown"} rather than throwing on a stack that
     * doesn't contain one (possible under aggressive inlining or a synthetic frame).
     *
     * <p>Exists so the suppression log can name the runaway caller. Only ever invoked on
     * the rate-limited log path, never per suppressed call.
     */
    public static String callerOf(StackTraceElement[] stack, String selfClassName) {
        if (stack == null || selfClassName == null) return "unknown";
        boolean seenSelf = false;
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls == null) continue;
            // Skip the Thread.getStackTrace()/Throwable frames that precede the caller.
            if (cls.equals(selfClassName)) {
                seenSelf = true;
                continue;
            }
            if (!seenSelf) continue;
            return cls + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        }
        return "unknown";
    }
}
