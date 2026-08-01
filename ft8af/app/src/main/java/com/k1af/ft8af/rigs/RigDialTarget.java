package com.k1af.ft8af.rigs;

/**
 * Decides which dial the app is entitled to <em>command</em>, as distinct from the dial it
 * merely <em>observes</em> the rig reporting.
 *
 * <p>Those were the same value, and that is what made a band change take a minute. Measured
 * on the 2026-07-31 evening activation:
 *
 * <pre>
 * 19:54:02  bandSelect: band=10136000            &lt;- operator taps 30m
 * 19:54:03  serial.send: FA010136000;            &lt;- dispatched in 815 ms
 *           rig replies "?;"                     &lt;- rejected (29 such replies that session)
 * 19:54:11  setting freq=10136000 (rig.getFreq=14239985)   &lt;- rig now reports a value
 *                                                              nobody asked for
 * 19:54:29  setting freq=14239985                &lt;- THE APP COMMANDS IT BACK
 * 19:55:01  setting freq=10136000 (rig.getFreq=10136000)   &lt;- settles, ~59 s and 4 taps later
 * </pre>
 *
 * <p>{@code onFreqChanged} writes whatever the rig reports into {@code GeneralVariables.band},
 * which is also what the reassert heartbeat pushes back out. So a single bad reading is
 * promoted from an observation into a command, and then actively fights the operator's
 * selection for as long as it survives.
 *
 * <p>The distinction this class draws is deliberately narrow, because in general a report
 * the app did not ask for is indistinguishable from the operator turning the VFO by hand —
 * and fighting a manual tune would be its own bug. The one case we can identify from
 * evidence is a report arriving while the rig is refusing our commands: every one of those
 * 29 rejections followed an {@code FA} set-frequency, and the bogus reading appeared inside
 * that window. A reading taken while the command stream is known to be desynchronised is
 * not trustworthy enough to command back.
 */
public final class RigDialTarget {

    private RigDialTarget() {}

    /**
     * Whether a frequency the rig has just reported should become the dial the app asserts.
     *
     * <p>Adopting is the normal case: it is how the app follows the operator turning the
     * VFO. It is refused only while the rig is rejecting our commands, where the reading
     * may be a mis-parse or a stale frame rather than the operator's intent.
     *
     * @param nowMs           current wall clock
     * @param rigRejectedAtMs when the rig last answered with an error / unparseable frame,
     *                        or 0 if never
     * @param reportedHz      the frequency just reported
     */
    /**
     * How long after a rejection the rig's frequency reports stay untrusted.
     *
     * <p>A timestamp rather than a "rejected since last command" flag, deliberately. The
     * flag had to be cleared by the next outgoing command — but the CAT liveness watchdog
     * polls the rig every {@code CAT_LIVENESS_TICK_MS} (3 s) with a frequency read, and
     * that unrelated poll would clear the flag between the rejection and the bad report,
     * defeating the guard entirely. A window depends on nothing but the clock.
     *
     * <p>Sized to the poll interval: long enough to cover the ~800 ms between the command
     * batch and the rejection plus the report that follows it, short enough that a healthy
     * stream is trusted again almost immediately.
     */
    public static final long DESYNC_DISTRUST_MS = 3_000;

    public static boolean shouldAdoptAsTarget(long nowMs, long rigRejectedAtMs, long reportedHz) {
        if (reportedHz <= 0) return false;
        if (rigRejectedAtMs <= 0) return true;
        long since = nowMs - rigRejectedAtMs;
        // A backwards clock correction must not silently re-trust the stream.
        if (since < 0) return false;
        return since >= DESYNC_DISTRUST_MS;
    }

    /**
     * The dial {@code setOperationBand()} should actually send.
     *
     * <p>Falls back to the observed band when no commanded dial has been established yet
     * (first run, or a config load that predates this field), so behaviour is unchanged
     * until the operator or the app picks a frequency explicitly.
     *
     * @param commandedHz the last dial the app or operator explicitly selected, or 0
     * @param observedHz  {@code GeneralVariables.band} — may have been overwritten by a
     *                    rig report
     */
    public static long dialToCommand(long commandedHz, long observedHz) {
        return commandedHz > 0 ? commandedHz : observedHz;
    }
}
