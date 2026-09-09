package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.icom.IComPacketTypes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * What the {@link IcomRig} constructor schedules and what
 * {@link IcomRig#onDisconnecting()} cancels, verified through the
 * {@link IcomRig.PollScheduler} seam with no real {@code Timer} and no
 * sleeping. {@link IcomRigReadFreqPollTest} covers the tick body; this class
 * covers the fact that the constructor actually wires that body up as the
 * dial poll with the documented delay and period, fixed-delay, so removing
 * the {@code startReadFreqTimer()} call or scheduling the wrong task could not
 * leave the suite green (Copilot review on #789).
 *
 * <p>Robolectric because the scheduled task is the real
 * {@code runReadFreqTick()}, which reads {@code GeneralVariables}.
 */
@RunWith(RobolectricTestRunner.class)
public class IcomRigPollSchedulingTest {

    private static final class Scheduled {
        final String kind;
        final Runnable task;
        final long delayMs;
        final long periodMs;
        boolean cancelled;

        Scheduled(String kind, Runnable task, long delayMs, long periodMs) {
            this.kind = kind;
            this.task = task;
            this.delayMs = delayMs;
            this.periodMs = periodMs;
        }
    }

    private static final class RecordingScheduler implements IcomRig.PollScheduler {
        final List<Scheduled> scheduled = new ArrayList<>();

        @Override
        public IcomRig.Cancellable scheduleFixedDelay(Runnable task, long delayMs, long periodMs) {
            Scheduled s = new Scheduled("fixed-delay", task, delayMs, periodMs);
            scheduled.add(s);
            return () -> s.cancelled = true;
        }

        @Override
        public IcomRig.Cancellable scheduleFixedRate(Runnable task, long delayMs, long periodMs) {
            Scheduled s = new Scheduled("fixed-rate", task, delayMs, periodMs);
            scheduled.add(s);
            return () -> s.cancelled = true;
        }
    }

    private static final class ConnectedConnector extends BaseRigConnector {
        ConnectedConnector() {
            super(0);
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    private Scheduled dialPoll(RecordingScheduler s) {
        for (Scheduled x : s.scheduled) {
            if (x.kind.equals("fixed-delay")) return x;
        }
        throw new AssertionError("no fixed-delay poll scheduled: " + s.scheduled.size());
    }

    private Scheduled meterPoll(RecordingScheduler s) {
        for (Scheduled x : s.scheduled) {
            if (x.kind.equals("fixed-rate")) return x;
        }
        throw new AssertionError("no fixed-rate poll scheduled");
    }

    @Test
    public void constructor_schedulesTheDialPollFixedDelayWithTheDocumentedTiming() {
        RecordingScheduler s = new RecordingScheduler();
        new IcomRig(0xA4, true, s);

        Scheduled dial = dialPoll(s);
        assertThat(dial.delayMs).isEqualTo(IcomRig.READ_FREQ_START_DELAY_MS);
        assertThat(dial.periodMs).isEqualTo(IcomRig.READ_FREQ_PERIOD_MS);
        assertThat(s.scheduled).hasSize(2); // dial poll + meter poll, nothing else
    }

    @Test
    public void constructor_schedulesTheMeterPollAtFixedRate() {
        RecordingScheduler s = new RecordingScheduler();
        new IcomRig(0xA4, true, s);

        Scheduled meter = meterPoll(s);
        assertThat(meter.delayMs).isEqualTo(0);
        assertThat(meter.periodMs).isEqualTo(IComPacketTypes.METER_TIMER_PERIOD_MS);
    }

    @Test
    public void theScheduledDialTaskIsTheTick() {
        // Running the scheduled task with the link up takes the settle window,
        // which only the tick body does — so the constructor wired the right
        // Runnable, not an empty one.
        RecordingScheduler s = new RecordingScheduler();
        IcomRig rig = new IcomRig(0xA4, true, s);
        rig.setConnector(new ConnectedConnector());
        assertThat(rig.connectedSinceMs()).isEqualTo(0L);

        dialPoll(s).task.run();

        assertThat(rig.connectedSinceMs()).isGreaterThan(0L);
    }

    @Test
    public void onDisconnecting_cancelsBothPolls_andIsIdempotent() {
        RecordingScheduler s = new RecordingScheduler();
        IcomRig rig = new IcomRig(0xA4, true, s);

        rig.onDisconnecting();
        rig.onDisconnecting();

        assertThat(dialPoll(s).cancelled).isTrue();
        assertThat(meterPoll(s).cancelled).isTrue();
        assertThat(rig.connectedSinceMs()).isEqualTo(0L);
    }
}
