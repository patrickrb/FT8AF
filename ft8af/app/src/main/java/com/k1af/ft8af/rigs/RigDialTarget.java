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
 *
 * <p>A second measured way an observation steals the target, from the 2026-08-04 session,
 * on a USB link that was flapping (write errors + auto-reconnect every few seconds):
 *
 * <pre>
 * 20:19:40  bandSelect: band=10136000, rigConnected=false   &lt;- operator taps 30m
 * 20:19:41  setOperationBand: rig not connected, skipping   &lt;- FA never dispatched
 * 20:19:4x  poll reads the rig, still on 14074000           &lt;- healthy stream, no "?;"
 *           -&gt; adopted as commandedBandHz                   &lt;- the tap is ERASED
 * 20:23:35  serial.send: FA014074000;                       &lt;- heartbeat re-asserts 20m,
 *                                                              every ~2 min, all evening
 * </pre>
 *
 * <p>The desync window can't catch this: the stream is healthy, the reading is truthful —
 * the rig really is still on 20m, precisely because the operator's command was dropped by
 * the connected-gate before ever reaching the wire. So an explicit operator selection is
 * additionally tracked as PENDING until the app has actually dispatched it
 * ({@code operatorDialAssertedAtMs} / {@code operatorDialDeliveredAtMs} in
 * {@code GeneralVariables}); while pending, a differing report is an echo of the past, not
 * the operator's intent, and must not be adopted. Delivery plus a short settle grace ends
 * the protection, so a hand-turned VFO is followed again within seconds.
 */
public final class RigDialTarget {

    private RigDialTarget() {}

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

    /**
     * How long after an operator selection is actually dispatched to the rig its reports
     * stay untrusted, so the rig has time to QSY and the next poll reflects it.
     *
     * <p>Sized to cover the 2 s freq-poll interval plus rig settle time: a differing
     * report landing later than this after the last dispatch means the rig refused the
     * command or the operator turned the VFO by hand — either way, follow the rig.
     */
    public static final long CONFIRM_GRACE_MS = 5_000;

    /**
     * Legacy form: no operator selection is pending. Kept because "no pending state"
     * is a meaningful default (config load, first run), not just a test convenience.
     */
    public static boolean shouldAdoptAsTarget(long nowMs, long rigRejectedAtMs, long reportedHz) {
        return shouldAdoptAsTarget(nowMs, rigRejectedAtMs, reportedHz, 0L, 0L, 0L);
    }

    /**
     * Whether a frequency the rig has just reported should become the dial the app asserts,
     * given the state of the operator's most recent explicit selection.
     *
     * <p>Refusal cases, each from a measured field failure (class javadoc):
     * <ul>
     *   <li>the stream is desynchronised ({@code DESYNC_DISTRUST_MS} after a "?;"), or</li>
     *   <li>an operator selection is pending and has not yet been dispatched to the rig
     *       (the 20:19:40 dropped-tap trace), or</li>
     *   <li>it was dispatched less than {@link #CONFIRM_GRACE_MS} ago, so a differing
     *       report may predate the rig's QSY.</li>
     * </ul>
     *
     * <p>A report that matches the commanded dial is always adopted (a no-op write) — the
     * caller uses that same equality to clear the pending state.
     *
     * @param nowMs                    current wall clock
     * @param rigRejectedAtMs          when the rig last answered with an error /
     *                                 unparseable frame, or 0 if never
     * @param reportedHz               the frequency just reported
     * @param commandedHz              the dial the app currently asserts
     *                                 ({@code GeneralVariables.commandedBandHz})
     * @param operatorAssertedAtMs     when the operator last explicitly selected a dial in
     *                                 the app, or 0 if never / already confirmed
     * @param operatorDeliveredAtMs    when that selection was last actually dispatched to
     *                                 the rig, or 0 if not yet
     */
    public static boolean shouldAdoptAsTarget(long nowMs, long rigRejectedAtMs, long reportedHz,
                                              long commandedHz, long operatorAssertedAtMs,
                                              long operatorDeliveredAtMs) {
        if (reportedHz <= 0) return false;
        if (reportedHz != commandedHz && operatorAssertedAtMs > 0) {
            // An explicit selection the rig has not confirmed yet. Undelivered, it is
            // protected unconditionally: the report is an echo of the dial the operator
            // just left, not a choice. (No time limit — while the link is down no reports
            // arrive anyway, and the moment it is back the ~1 Hz reassert delivers.)
            if (operatorDeliveredAtMs < operatorAssertedAtMs) return false;
            long sinceDelivery = nowMs - operatorDeliveredAtMs;
            // Backwards clock (< 0) is treated as still-in-grace, same posture as below.
            if (sinceDelivery < CONFIRM_GRACE_MS) return false;
        }
        if (rigRejectedAtMs <= 0) return true;
        long since = nowMs - rigRejectedAtMs;
        // A backwards clock correction must not silently re-trust the stream.
        if (since < 0) return false;
        return since >= DESYNC_DISTRUST_MS;
    }

    /**
     * The delivery stamp to record after a set-frequency dispatch.
     *
     * <p>The CAT write can fail without throwing — {@code CableSerialPort.sendData}
     * returns {@code false} on a port that has already gone away — and stamping such an
     * attempt as "delivered" would start the {@link #CONFIRM_GRACE_MS} countdown on a
     * command the rig never saw, re-opening the overwrite this class exists to prevent.
     * Only a write the connector believes reached the rig advances the stamp.
     *
     * @param writeOk         whether the connector reports the write as delivered
     * @param nowMs           current wall clock
     * @param previousStampMs the stamp as it stood before this dispatch
     */
    public static long deliveredStamp(boolean writeOk, long nowMs, long previousStampMs) {
        return writeOk ? nowMs : previousStampMs;
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
