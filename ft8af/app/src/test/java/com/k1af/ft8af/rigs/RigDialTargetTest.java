package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link RigDialTarget} — the separation between the dial the app commands
 * and the dial it merely observes.
 *
 * <p>Reported as "it takes a loooong time for the radio to change frequencies". Measured:
 * the command itself was dispatched in 815 ms, but the rig rejected it ("?;", 29 times that
 * session), reported a frequency nobody had asked for, and the app then commanded that
 * value back — so a 30m selection took ~59 s and four taps to stick.
 */
public class RigDialTargetTest {

    private static final long M30 = 10_136_000L;
    private static final long M20 = 14_074_000L;
    /** The value the rig reported that nobody selected, from the 19:54 trace. */
    private static final long BOGUS = 14_239_985L;
    private static final long T0 = 1_700_000_000_000L;

    // ---- shouldAdoptAsTarget ------------------------------------------------

    @Test
    public void healthyStream_adoptsTheReportedDial() {
        // The normal case, and the reason this isn't simply "never trust the rig": this is
        // how the app follows the operator turning the VFO by hand.
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, 0L, M20)).isTrue();
    }

    @Test
    public void desyncedStream_refusesTheReportedDial() {
        // The measured failure: a reading taken while the rig is rejecting our commands
        // must not become something we command back at it.
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, T0, BOGUS)).isFalse();
    }

    @Test
    public void desyncRefusesEvenAPlausibleLookingDial() {
        // The bogus value was in the same band as the real one, so plausibility is no
        // defence — only the stream state distinguishes them.
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, T0, M20)).isFalse();
    }

    @Test
    public void zeroOrNegativeIsNeverAdopted() {
        // rig.getFreq=0 shows up in the logs on a fresh connect, before any real read.
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, 0L, 0)).isFalse();
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, 0L, -1)).isFalse();
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, T0, 0)).isFalse();
    }

    @Test
    public void trustReturnsAfterTheDistrustWindow() {
        // A window, not a "rejected since last command" flag: the CAT liveness watchdog
        // polls the rig every 3 s, and that unrelated send would have cleared a flag
        // between the rejection and the bad report — defeating the guard entirely.
        long after = T0 + RigDialTarget.DESYNC_DISTRUST_MS;
        assertThat(RigDialTarget.shouldAdoptAsTarget(after, T0, M20)).isTrue();
        assertThat(RigDialTarget.shouldAdoptAsTarget(after - 1, T0, M20)).isFalse();
    }

    @Test
    public void windowOutlastsTheGapBetweenCommandAndRejection() {
        // The rejection arrived ~800 ms after the command batch, and the bogus report
        // followed it. Too short a window would re-trust before the bad report lands.
        assertThat(RigDialTarget.DESYNC_DISTRUST_MS).isAtLeast(1_500L);
    }

    @Test
    public void backwardsClockDoesNotSilentlyReTrust() {
        // System.currentTimeMillis() is not monotonic and this app disciplines its clock.
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0 - 60_000, T0, M20)).isFalse();
    }

    @Test
    public void neverRejectedMeansAlwaysTrusted() {
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, 0L, M20)).isTrue();
    }

    // ---- shouldAdoptAsTarget: pending operator selection ---------------------
    //
    // The 2026-08-04 failure: a 30m tap while the flapping USB link was down was
    // dropped by the connected-gate, a healthy poll then echoed the still-on-20m rig,
    // the echo was adopted as the commanded dial, and the reassert heartbeat pushed 20m
    // back at the rig every ~2 minutes all evening — against repeated 30m taps.

    @Test
    public void undeliveredSelection_pollEchoOfOldBandIsNotAdopted() {
        // Tap 30m at T0, never dispatched (delivered=0). The rig truthfully reports the
        // OLD band — that report must not overwrite the operator's choice.
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0 + 2_000, 0L, M20, M30, T0, 0L))
                .isFalse();
    }

    @Test
    public void undeliveredSelection_protectedWithoutTimeLimit() {
        // The link can stay down for minutes; while it is, no dispatch can happen, and
        // the choice must survive until the reconnect push delivers it.
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0 + 600_000, 0L, M20, M30, T0, 0L))
                .isFalse();
    }

    @Test
    public void deliveryFromAnEarlierSelectionDoesNotCount() {
        // delivered stamp predates this selection: it belongs to the previous choice.
        assertThat(RigDialTarget.shouldAdoptAsTarget(
                T0 + 60_000, 0L, M20, M30, T0, T0 - 10_000)).isFalse();
    }

    @Test
    public void justDelivered_differingReportStillRefusedDuringGrace() {
        // The FA went out; the rig needs a moment to QSY and the next poll may still
        // carry the old dial. Inside the grace, keep asserting the choice.
        long delivered = T0 + 1_000;
        long stillInGrace = delivered + RigDialTarget.CONFIRM_GRACE_MS - 1;
        assertThat(RigDialTarget.shouldAdoptAsTarget(
                stillInGrace, 0L, M20, M30, T0, delivered)).isFalse();
    }

    @Test
    public void afterGrace_differingReportIsFollowedAgain() {
        // Delivered, grace expired, rig still reports something else: either the rig
        // refused the command or the operator turned the VFO by hand. Follow the rig —
        // fighting a manual tune would be its own bug.
        long delivered = T0 + 1_000;
        long afterGrace = delivered + RigDialTarget.CONFIRM_GRACE_MS;
        assertThat(RigDialTarget.shouldAdoptAsTarget(
                afterGrace, 0L, M20, M30, T0, delivered)).isTrue();
    }

    @Test
    public void matchingReportIsAlwaysAdoptable() {
        // The rig confirming the commanded dial is a no-op write, never refused by the
        // pending guard (the caller also uses this equality to clear the pending state).
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0 + 1, 0L, M30, M30, T0, 0L))
                .isTrue();
    }

    @Test
    public void noPendingSelection_behavesExactlyAsBefore() {
        // operatorAssertedAtMs == 0 (config load, first run, or already confirmed):
        // the guard is inert and only the desync window applies.
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, 0L, M20, M30, 0L, 0L)).isTrue();
        assertThat(RigDialTarget.shouldAdoptAsTarget(T0, T0, M20, M30, 0L, 0L)).isFalse();
    }

    @Test
    public void desyncStillRefusesEvenAfterGrace() {
        // The two guards compose: surviving the pending guard does not bypass the
        // desync window.
        long delivered = T0 + 1_000;
        long afterGrace = delivered + RigDialTarget.CONFIRM_GRACE_MS;
        assertThat(RigDialTarget.shouldAdoptAsTarget(
                afterGrace, afterGrace, M20, M30, T0, delivered)).isFalse();
    }

    @Test
    public void backwardsClockDuringGraceDoesNotReTrust() {
        // The clock is disciplined by GPS/decode sync and can step backwards; a
        // now < deliveredAt must read as still-in-grace, not as grace-expired.
        long delivered = T0 + 1_000;
        assertThat(RigDialTarget.shouldAdoptAsTarget(
                delivered - 60_000, 0L, M20, M30, T0, delivered)).isFalse();
    }

    @Test
    public void theMeasuredDroppedTapNowHolds() {
        // 20:19:40 — operator taps 30m; the connected-gate drops the send.
        long commanded = M30;
        long assertedAt = T0;
        long deliveredAt = 0L;
        // 20:19:4x — healthy poll echoes the still-on-20m rig. Old behaviour adopted it.
        if (RigDialTarget.shouldAdoptAsTarget(T0 + 2_000, 0L, M20,
                commanded, assertedAt, deliveredAt)) {
            commanded = M20;
        }
        // The heartbeat fires: it must still push the operator's 30m, not 20m.
        assertThat(RigDialTarget.dialToCommand(commanded, M20)).isEqualTo(M30);

        // Link recovers; the reassert dispatches the FA for 30m.
        deliveredAt = T0 + 30_000;
        // The rig QSYs and confirms; the caller clears the pending state on equality.
        if (RigDialTarget.shouldAdoptAsTarget(deliveredAt + 2_000, 0L, M30,
                commanded, assertedAt, deliveredAt)) {
            commanded = M30;
        }
        assertThat(RigDialTarget.dialToCommand(commanded, M30)).isEqualTo(M30);
    }

    // ---- dialToCommand ------------------------------------------------------

    @Test
    public void commandsTheChosenDialNotTheObservedOne() {
        // The 19:54:29 line that cost the operator the most: observed had been overwritten
        // with the bogus reading, chosen was still 30m. Chosen wins.
        assertThat(RigDialTarget.dialToCommand(M30, BOGUS)).isEqualTo(M30);
    }

    @Test
    public void fallsBackToObservedWhenNothingChosenYet() {
        // First run, or a config load predating commandedBandHz — behaviour must be
        // exactly as before until something is explicitly selected.
        assertThat(RigDialTarget.dialToCommand(0, M20)).isEqualTo(M20);
    }

    @Test
    public void agreementIsUnchanged() {
        assertThat(RigDialTarget.dialToCommand(M20, M20)).isEqualTo(M20);
    }

    // ---- the sequence that made a band change take a minute ------------------

    @Test
    public void theMeasuredBandChangeNowHolds() {
        // Operator taps 30m.
        long chosen = M30;
        // Rig rejects the set-frequency and reports something nobody asked for.
        long observed = BOGUS;
        if (RigDialTarget.shouldAdoptAsTarget(T0, T0, observed)) {
            chosen = observed;// old behaviour: the bad reading became the target
        }
        // The reassert heartbeat fires.
        assertThat(RigDialTarget.dialToCommand(chosen, observed)).isEqualTo(M30);

        // Once the stream re-synchronises and the rig confirms 30m, nothing changes.
        if (RigDialTarget.shouldAdoptAsTarget(T0, 0L, M30)) {
            chosen = M30;
        }
        assertThat(RigDialTarget.dialToCommand(chosen, M30)).isEqualTo(M30);
    }
}
