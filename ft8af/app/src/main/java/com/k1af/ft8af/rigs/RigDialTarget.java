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
     * @param rigRejectedSinceCommand whether the rig has answered our last command with an
     *                                error / unparseable frame
     * @param reportedHz              the frequency just reported
     */
    public static boolean shouldAdoptAsTarget(boolean rigRejectedSinceCommand, long reportedHz) {
        if (reportedHz <= 0) return false;
        return !rigRejectedSinceCommand;
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
