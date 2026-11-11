package com.k1af.ft8af.handoff;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-JVM tests for {@link RigHandoffController} and {@link RigHandoffGate}: the whole
 * handoff state machine, driven with a fake rig, transport and clock.
 */
public class RigHandoffControllerTest {

    private static final String SELF = "radio.ks3ckc.sstvaf";
    private static final String FT8AF = "radio.ks3ckc.ft8af";
    private static final String RTTYAF = "radio.ks3ckc.rttyaf";
    private static final long NOW = 5_000_000L;

    /** Everything the controller did, in order. */
    private final List<String> events = new ArrayList<>();
    private boolean transmitting;
    private final FakeScheduler scheduler = new FakeScheduler();
    private RigHandoffController controller;

    private static final class Pending {
        final Runnable task;
        final long delayMs;

        Pending(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }
    }

    private final class FakeScheduler implements RigHandoffController.Scheduler {
        final List<Pending> pending = new ArrayList<>();

        @Override
        public void postDelayed(Runnable task, long delayMs) {
            pending.add(new Pending(task, delayMs));
        }

        @Override
        public void cancel(Runnable task) {
            pending.removeIf(p -> p.task == task);
        }

        void runAll() {
            List<Pending> due = new ArrayList<>(pending);
            pending.clear();
            for (Pending p : due) p.task.run();
        }
    }

    @Before
    public void setUp() {
        RigHandoffGate.clear();
        controller = new RigHandoffController(SELF,
                new RigHandoffController.Rig() {
                    @Override
                    public boolean isTransmitting() {
                        return transmitting;
                    }

                    @Override
                    public void stopTransmit() {
                        events.add("stopTransmit");
                        transmitting = false;
                    }

                    @Override
                    public void release() {
                        events.add("release");
                    }

                    @Override
                    public void reclaim() {
                        events.add("reclaim");
                    }
                },
                new RigHandoffController.Transport() {
                    @Override
                    public void sendTakeover() {
                        events.add("sendTakeover");
                    }

                    @Override
                    public void sendReleased(String toPackage) {
                        events.add("sendReleased:" + toPackage);
                    }
                },
                scheduler,
                new RigHandoffController.Listener() {
                    @Override
                    public void onYielded(String toPackage) {
                        events.add("yielded:" + toPackage);
                    }

                    @Override
                    public void onRegained(String fromPackage) {
                        events.add("regained:" + fromPackage);
                    }
                });
    }

    @After
    public void tearDown() {
        RigHandoffGate.clear(); // the gate is process-wide; don't leak into other tests
    }

    // ---- being asked for the rig ------------------------------------------------

    @Test
    public void takeover_whileIdle_releasesAtOnceAndConfirms() {
        assertThat(controller.onTakeoverRequested(FT8AF, null, NOW, NOW)).isTrue();

        assertThat(events).containsExactly("release", "sendReleased:" + FT8AF,
                "yielded:" + FT8AF).inOrder();
        assertThat(RigHandoffGate.isYielded()).isTrue();
        assertThat(RigHandoffGate.yieldedTo()).isEqualTo(FT8AF);
        assertThat(scheduler.pending).isEmpty();
    }

    @Test
    public void takeover_whileTransmitting_unkeysFirstThenReleasesAfterGrace() {
        transmitting = true;

        controller.onTakeoverRequested(FT8AF, null, NOW, NOW);

        // TX is stopped and the gate is shut immediately, but the port stays open
        // until the PTT-off has had time to reach the rig.
        assertThat(events).containsExactly("stopTransmit");
        assertThat(RigHandoffGate.isYielded()).isTrue();
        assertThat(scheduler.pending).hasSize(1);
        assertThat(scheduler.pending.get(0).delayMs)
                .isEqualTo(RigHandoffController.UNKEY_GRACE_MS);

        scheduler.runAll();
        assertThat(events).containsExactly("stopTransmit", "release",
                "sendReleased:" + FT8AF, "yielded:" + FT8AF).inOrder();
    }

    @Test
    public void takeover_setsGateBeforeReleasing() {
        // release() rebuilds the audio input; if the gate were still open at that
        // point the rebuild would re-open the rig's USB audio it is meant to drop.
        final boolean[] gateAtRelease = new boolean[1];
        RigHandoffController c = new RigHandoffController(SELF,
                new RigHandoffController.Rig() {
                    @Override
                    public boolean isTransmitting() {
                        return false;
                    }

                    @Override
                    public void stopTransmit() {}

                    @Override
                    public void release() {
                        gateAtRelease[0] = RigHandoffGate.isYielded();
                    }

                    @Override
                    public void reclaim() {}
                },
                new RigHandoffController.Transport() {
                    @Override
                    public void sendTakeover() {}

                    @Override
                    public void sendReleased(String toPackage) {}
                },
                scheduler,
                new RigHandoffController.Listener() {
                    @Override
                    public void onYielded(String toPackage) {}

                    @Override
                    public void onRegained(String fromPackage) {}
                });

        c.onTakeoverRequested(FT8AF, null, NOW, NOW);

        assertThat(gateAtRelease[0]).isTrue();
    }

    @Test
    public void takeover_whenAlreadyYielded_doesNotReleaseOrConfirmAgain() {
        controller.onTakeoverRequested(FT8AF, null, NOW, NOW);
        events.clear();

        // The claimant foregrounds again (it sends TAKEOVER on every onStart).
        assertThat(controller.onTakeoverRequested(FT8AF, null, NOW, NOW)).isTrue();
        assertThat(events).isEmpty();

        // A different sibling now in front: remember who has it, still nothing to do.
        assertThat(controller.onTakeoverRequested(RTTYAF, null, NOW, NOW)).isTrue();
        assertThat(events).isEmpty();
        assertThat(RigHandoffGate.yieldedTo()).isEqualTo(RTTYAF);
    }

    @Test
    public void takeover_ignoredFromSelfStrangerForgedOrStale() {
        long stale = NOW - RigHandoffProtocol.MAX_MESSAGE_AGE_MS - 1;

        assertThat(controller.onTakeoverRequested(SELF, null, NOW, NOW)).isFalse();
        assertThat(controller.onTakeoverRequested("com.evil.app", null, NOW, NOW)).isFalse();
        assertThat(controller.onTakeoverRequested(FT8AF, "com.evil.app", NOW, NOW)).isFalse();
        assertThat(controller.onTakeoverRequested(FT8AF, null, stale, NOW)).isFalse();

        assertThat(events).isEmpty();
        assertThat(RigHandoffGate.isYielded()).isFalse();
    }

    // ---- coming to the foreground ----------------------------------------------

    @Test
    public void foregrounded_neverYielded_asksSiblingsButDoesNotReconnect() {
        controller.onForegrounded(NOW - 1);

        // Nothing was lent out, so the app's normal connect paths are left alone.
        assertThat(events).containsExactly("sendTakeover");
        assertThat(scheduler.pending).isEmpty();
    }

    @Test
    public void foregrounded_afterYield_opensGateAndReclaimsAfterDelay() {
        controller.onTakeoverRequested(FT8AF, null, NOW, NOW);
        events.clear();

        controller.onForegrounded(NOW - 1);

        assertThat(RigHandoffGate.isYielded()).isFalse();
        assertThat(events).containsExactly("sendTakeover", "regained:null").inOrder();
        assertThat(scheduler.pending).hasSize(1);
        assertThat(scheduler.pending.get(0).delayMs)
                .isEqualTo(RigHandoffController.RECLAIM_DELAY_MS);

        scheduler.runAll();
        assertThat(events).containsExactly("sendTakeover", "regained:null", "reclaim")
                .inOrder();
    }

    @Test
    public void released_reclaimsImmediatelyAndCancelsTheDelayedReclaim() {
        controller.onTakeoverRequested(FT8AF, null, NOW, NOW);
        controller.onForegrounded(NOW - 1);
        events.clear();

        assertThat(controller.onReleased(FT8AF, null, NOW, NOW)).isTrue();

        assertThat(events).containsExactly("regained:" + FT8AF, "reclaim").inOrder();
        // The timed fallback must not reconnect a second time.
        assertThat(scheduler.pending).isEmpty();
    }

    @Test
    public void released_withoutPriorYield_stillReclaims() {
        // Fresh start while a sibling held the rig: its RELEASED is our cue to connect.
        controller.onForegrounded(NOW - 1);
        events.clear();

        controller.onReleased(FT8AF, null, NOW, NOW);

        assertThat(events).containsExactly("regained:" + FT8AF, "reclaim").inOrder();
    }

    @Test
    public void released_ignoredWhileWeAreYielded() {
        // A late RELEASED must not reconnect a rig we have since handed on.
        controller.onTakeoverRequested(RTTYAF, null, NOW, NOW);
        events.clear();

        assertThat(controller.onReleased(FT8AF, null, NOW, NOW)).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    public void released_ignoredFromStrangerOrStale() {
        long stale = NOW - RigHandoffProtocol.MAX_MESSAGE_AGE_MS - 1;

        assertThat(controller.onReleased("com.evil.app", null, NOW, NOW)).isFalse();
        assertThat(controller.onReleased(FT8AF, null, stale, NOW)).isFalse();
        assertThat(events).isEmpty();
    }

    // ---- races -----------------------------------------------------------------

    @Test
    public void foregroundedDuringUnkeyGrace_keepsTheRig() {
        transmitting = true;
        controller.onTakeoverRequested(FT8AF, null, NOW, NOW);
        events.clear();

        // Operator flips straight back before the grace timer fires.
        controller.onForegrounded(NOW - 1);
        scheduler.runAll();

        // The pending release was cancelled: no release, no RELEASED sent.
        assertThat(events).doesNotContain("release");
        assertThat(events).doesNotContain("sendReleased:" + FT8AF);
        assertThat(RigHandoffGate.isYielded()).isFalse();
    }

    @Test
    public void takeoverDuringReclaimDelay_cancelsTheReclaim() {
        controller.onTakeoverRequested(FT8AF, null, NOW, NOW);
        controller.onForegrounded(NOW - 1); // reclaim now pending
        events.clear();

        // The sibling comes back in front before our delayed reclaim ran.
        controller.onTakeoverRequested(FT8AF, null, NOW, NOW);
        scheduler.runAll();

        assertThat(events).doesNotContain("reclaim");
        assertThat(RigHandoffGate.isYielded()).isTrue();
    }

    @Test
    public void takeoverSentBeforeOurOwnForegrounding_isIgnored() {
        // Sibling claimed at T, then the operator opened us at T+10. Its request,
        // delivered late (frozen process, busy main thread), has been superseded.
        controller.onForegrounded(NOW + 10);
        events.clear();

        assertThat(controller.onTakeoverRequested(FT8AF, null, NOW, NOW + 20)).isFalse();

        assertThat(events).isEmpty();
        assertThat(RigHandoffGate.isYielded()).isFalse();
    }

    @Test
    public void takeoverSentAfterOurForegrounding_isHonoured() {
        controller.onForegrounded(NOW);
        events.clear();

        assertThat(controller.onTakeoverRequested(FT8AF, null, NOW + 10, NOW + 20)).isTrue();
        assertThat(RigHandoffGate.isYielded()).isTrue();
    }

    // ---- the gate --------------------------------------------------------------

    @Test
    public void gate_usbAudioOpenNeedsConfiguredDeviceAndNoLoan() {
        // -1 = "direct USB" input selection; vendor id identifies the adapter.
        assertThat(RigHandoffGate.mayOpenUsbAudioInput(-1, 0x0D8C)).isTrue();
        assertThat(RigHandoffGate.mayOpenUsbAudioInput(0, 0x0D8C)).isFalse();
        assertThat(RigHandoffGate.mayOpenUsbAudioInput(-1, 0)).isFalse();

        RigHandoffGate.yieldTo(FT8AF);
        assertThat(RigHandoffGate.mayOpenUsbAudioInput(-1, 0x0D8C)).isFalse();

        RigHandoffGate.clear();
        assertThat(RigHandoffGate.mayOpenUsbAudioInput(-1, 0x0D8C)).isTrue();
    }
}
