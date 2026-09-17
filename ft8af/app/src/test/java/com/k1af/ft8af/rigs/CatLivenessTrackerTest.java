package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle tests for the CAT liveness watchdog state (arm / trip / recover). Pure JUnit.
 */
public class CatLivenessTrackerTest {

    private static final long TIMEOUT = CatLiveness.DEFAULT_TIMEOUT_MS;
    private static final long TICK = 3000;

    private static CatLivenessTracker started(long now) {
        CatLivenessTracker t = new CatLivenessTracker(TIMEOUT);
        t.start(now);
        return t;
    }

    // ---- arming -------------------------------------------------------------

    @Test
    public void neverTrips_whenRigNeverAnswered() {
        // The #781 shape: transport open, the app pushes its own dial (which must not
        // arm the tracker — see BaseRigFreqSignalTest), and the rig never answers a
        // frequency read. Long silence -> still no trip, but we keep probing.
        CatLivenessTracker t = started(0);
        for (long now = TICK; now <= 60_000; now += TICK) {
            CatLivenessTracker.Tick tick = t.tick(true, false, now);
            assertThat(tick.probe).isTrue();
            assertThat(tick.event).isEqualTo(CatLivenessTracker.Event.NONE);
        }
        assertThat(t.isTripped()).isFalse();
        assertThat(t.hasSeenResponse()).isFalse();
    }

    @Test
    public void armsOnlyOnResponse() {
        CatLivenessTracker t = started(0);
        assertThat(t.hasSeenResponse()).isFalse();
        assertThat(t.onResponse(1000)).isFalse(); // a plain reply, nothing to recover
        assertThat(t.hasSeenResponse()).isTrue();
    }

    // ---- tripping -----------------------------------------------------------

    @Test
    public void trips_once_afterArmedRigGoesQuiet() {
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        // Quiet for 3 s, 6 s: still inside the window.
        assertThat(t.tick(true, false, 4000).event).isEqualTo(CatLivenessTracker.Event.NONE);
        assertThat(t.tick(true, false, 7000).event).isEqualTo(CatLivenessTracker.Event.NONE);
        // 9 s quiet (> 8 s timeout): trip exactly once...
        CatLivenessTracker.Tick trip = t.tick(true, false, 10_000);
        assertThat(trip.event).isEqualTo(CatLivenessTracker.Event.TRIPPED);
        assertThat(trip.quietMs).isEqualTo(9000);
        assertThat(t.isTripped()).isTrue();
        // ...and not again on every subsequent quiet tick (no toast/log spam), while
        // probing continues so a late reply can heal the link.
        CatLivenessTracker.Tick later = t.tick(true, false, 13_000);
        assertThat(later.event).isEqualTo(CatLivenessTracker.Event.NONE);
        assertThat(later.probe).isTrue();
        assertThat(t.isTripped()).isTrue();
    }

    @Test
    public void noProbeAndNoTrip_whileTransmitting() {
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        CatLivenessTracker.Tick tx = t.tick(true, true, 20_000);
        assertThat(tx.probe).isFalse();
        assertThat(tx.event).isEqualTo(CatLivenessTracker.Event.NONE);
    }

    @Test
    public void rearmsOnTxToRxEdge_soLongOverDoesNotTrip() {
        // PR #450 behaviour preserved through the tracker: a 12.64 s FT8 over is longer
        // than the timeout, but the first RX tick re-arms instead of judging.
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        t.tick(true, true, 4000);   // keyed
        t.tick(true, true, 13_000); // still keyed, lastResponse is now 12 s old
        CatLivenessTracker.Tick edge = t.tick(true, false, 16_000);
        assertThat(edge.event).isEqualTo(CatLivenessTracker.Event.NONE);
        assertThat(edge.probe).isTrue();
        assertThat(edge.quietMs).isEqualTo(0);
        // The window restarts from the edge: quiet past the timeout from THERE trips.
        assertThat(t.tick(true, false, 19_000).event).isEqualTo(CatLivenessTracker.Event.NONE);
        assertThat(t.tick(true, false, 25_000).event).isEqualTo(CatLivenessTracker.Event.TRIPPED);
    }

    @Test
    public void noTrip_whenTransportReportsDisconnected() {
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        assertThat(t.tick(false, false, 30_000).event).isEqualTo(CatLivenessTracker.Event.NONE);
        assertThat(t.tick(false, false, 30_000).probe).isFalse();
    }

    // ---- recovery -----------------------------------------------------------

    @Test
    public void recovers_whenReplyArrivesAfterTrip() {
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        assertThat(t.tick(true, false, 10_000).event).isEqualTo(CatLivenessTracker.Event.TRIPPED);
        // A reply ends the stale period: the caller flips the chip back to CONNECTED.
        assertThat(t.onResponse(11_000)).isTrue();
        assertThat(t.isTripped()).isFalse();
        // Only the FIRST reply after a trip reports a recovery.
        assertThat(t.onResponse(12_000)).isFalse();
        // And the tracker is fully live again: the next tick is a normal in-window tick.
        assertThat(t.tick(true, false, 13_000).event).isEqualTo(CatLivenessTracker.Event.NONE);
    }

    @Test
    public void reTrips_afterRecoveryIfRigGoesQuietAgain() {
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        assertThat(t.tick(true, false, 10_000).event).isEqualTo(CatLivenessTracker.Event.TRIPPED);
        assertThat(t.onResponse(11_000)).isTrue();
        assertThat(t.tick(true, false, 14_000).event).isEqualTo(CatLivenessTracker.Event.NONE);
        assertThat(t.tick(true, false, 20_000).event).isEqualTo(CatLivenessTracker.Event.TRIPPED);
    }

    // ---- stop / restart -----------------------------------------------------

    @Test
    public void stop_clearsArmingAndIgnoresStragglers() {
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        t.tick(true, false, 10_000); // tripped
        t.stop();
        assertThat(t.isRunning()).isFalse();
        assertThat(t.hasSeenResponse()).isFalse(); // USB Diagnostics must not show a stale pass
        assertThat(t.isTripped()).isFalse();
        // A reply after the link is gone must not "recover" anything.
        assertThat(t.onResponse(11_000)).isFalse();
        assertThat(t.hasSeenResponse()).isFalse();
        // And a tick on a stopped tracker is inert.
        CatLivenessTracker.Tick idle = t.tick(true, false, 60_000);
        assertThat(idle.probe).isFalse();
        assertThat(idle.event).isEqualTo(CatLivenessTracker.Event.NONE);
    }

    @Test
    public void start_resetsATrippedTracker() {
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        t.tick(true, false, 10_000); // tripped
        t.start(20_000); // reconnect
        assertThat(t.isTripped()).isFalse();
        assertThat(t.hasSeenResponse()).isFalse();
        // Disarmed again: silence on the new connection is not judged until the rig answers.
        assertThat(t.tick(true, false, 40_000).event).isEqualTo(CatLivenessTracker.Event.NONE);
    }

    // ---- atomic state transitions (Copilot review on PR #818) ---------------

    @Test
    public void transitions_runOnlyOnTheirEvent() {
        List<String> chip = new ArrayList<>();
        CatLivenessTracker t = started(0);
        t.onResponse(1000, () -> true, () -> chip.add("CONNECTED")); // not tripped: no-op
        t.tick(true, false, 4000, () -> chip.add("ERROR"));           // in window: no-op
        assertThat(chip).isEmpty();

        t.tick(true, false, 10_000, () -> chip.add("ERROR"));
        t.tick(true, false, 13_000, () -> chip.add("ERROR"));         // already tripped
        assertThat(chip).containsExactly("ERROR");

        // Tripped, but the transport is gone: the trip clears, the chip is left alone.
        assertThat(t.onResponse(14_000, () -> false, () -> chip.add("CONNECTED"))).isTrue();
        assertThat(t.isTripped()).isFalse();
        assertThat(chip).containsExactly("ERROR");

        t.stop(() -> chip.add("STOPPED"));
        assertThat(chip).containsExactly("ERROR", "STOPPED").inOrder();
    }

    @Test
    public void replyRacingATrip_cannotHealBeforeTheErrorIsWritten() throws Exception {
        // The interleaving Copilot flagged: the tick trips, and a reply lands before the
        // tick has written ERROR. Healing first and then being overwritten by ERROR left
        // the chip red with the tracker untripped, so no later reply could recover it.
        // With the ERROR write inside the tracker's monitor, the reply must wait for it.
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        AtomicReference<String> chip = new AtomicReference<>("CONNECTED");
        Thread[] reply = new Thread[1];

        CatLivenessTracker.Tick tick = t.tick(true, false, 10_000, () -> {
            reply[0] = new Thread(() ->
                    t.onResponse(10_050, () -> true, () -> chip.set("CONNECTED")));
            reply[0].start();
            awaitBlocked(reply[0]); // the reply is parked on the tracker's monitor
            chip.set("ERROR");
        });
        reply[0].join(5000);

        assertThat(tick.event).isEqualTo(CatLivenessTracker.Event.TRIPPED);
        assertThat(chip.get()).isEqualTo("CONNECTED");
        assertThat(t.isTripped()).isFalse();
    }

    @Test
    public void connectorErrorRacingARecovery_errorWins() throws Exception {
        // A connector onRunError while a recovering reply is still applying CONNECTED
        // (its transport flag not yet cleared): the error's stop+ERROR must land after
        // the recovery, never be overwritten by it.
        CatLivenessTracker t = started(0);
        t.onResponse(1000);
        t.tick(true, false, 10_000);
        AtomicReference<String> chip = new AtomicReference<>("ERROR");
        Thread[] error = new Thread[1];

        t.onResponse(11_000, () -> true, () -> {
            error[0] = new Thread(() -> t.stop(() -> chip.set("ERROR")));
            error[0].start();
            awaitBlocked(error[0]);
            chip.set("CONNECTED");
        });
        error[0].join(5000);

        assertThat(chip.get()).isEqualTo("ERROR");
        assertThat(t.isRunning()).isFalse();
        // And a reply straggling in after the error can't heal it.
        assertThat(t.onResponse(12_000, () -> true, () -> chip.set("CONNECTED"))).isFalse();
        assertThat(chip.get()).isEqualTo("ERROR");
    }

    /** Wait until {@code thread} is parked on a monitor (i.e. contending for the tracker). */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.currentTimeMillis() + 5000;
        while (thread.getState() != Thread.State.BLOCKED) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("thread never blocked on the tracker: " + thread.getState());
            }
            Thread.yield();
        }
    }
}
