package com.k1af.ft8af.timer;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.GeneralVariables;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Covers the pure offset/should-apply/interval logic in {@link NtpClockUpdater} — the NTP
 * counterpart to {@code GpsClockUpdaterTest} — plus the toggle/lifecycle gating. Robolectric
 * is used so the Android-typed class (Handler/Looper) links cleanly; the arithmetic under
 * test never touches a real network, and the lifecycle tests never idle the main looper, so
 * no real NTP sync ever fires.
 */
@RunWith(RobolectricTestRunner.class)
public class NtpClockUpdaterTest {

    // ---- ntpClockOffsetMs: offset to add to System.currentTimeMillis() ----

    @Test
    public void offset_isPositive_whenDeviceClockIsSlow() {
        // Server time 2000ms ahead of the device clock => +2000 to catch up.
        int offset = NtpClockUpdater.ntpClockOffsetMs(10_000L, 8_000L);
        assertThat(offset).isEqualTo(2_000);
    }

    @Test
    public void offset_isNegative_whenDeviceClockIsFast() {
        int offset = NtpClockUpdater.ntpClockOffsetMs(10_000L, 12_500L);
        assertThat(offset).isEqualTo(-2_500);
    }

    @Test
    public void offset_zeroWhenClocksMatch() {
        assertThat(NtpClockUpdater.ntpClockOffsetMs(10_000L, 10_000L)).isEqualTo(0);
    }

    // ---- isOffsetSane: reject bad/implausible replies ----

    @Test
    public void sane_acceptsRealisticOffset() {
        assertThat(NtpClockUpdater.isOffsetSane(1_700_000_000_000L, 2_500)).isTrue();
        assertThat(NtpClockUpdater.isOffsetSane(1_700_000_000_000L, -30_000)).isTrue();
    }

    @Test
    public void sane_rejectsZeroTransmitTime() {
        // A transmit timestamp of 0 means "no time in this reply" — never trust it.
        assertThat(NtpClockUpdater.isOffsetSane(0L, 500)).isFalse();
    }

    @Test
    public void sane_rejectsAbsurdOffset() {
        // Beyond an hour: corrupt/spoofed reply or a badly misconfigured server.
        int tooBig = (int) (NtpClockUpdater.MAX_SANE_OFFSET_MS + 1);
        assertThat(NtpClockUpdater.isOffsetSane(1_700_000_000_000L, tooBig)).isFalse();
        assertThat(NtpClockUpdater.isOffsetSane(1_700_000_000_000L, -tooBig)).isFalse();
    }

    @Test
    public void sane_acceptsExactlyAtBound() {
        int atBound = (int) NtpClockUpdater.MAX_SANE_OFFSET_MS;
        assertThat(NtpClockUpdater.isOffsetSane(1_700_000_000_000L, atBound)).isTrue();
    }

    // ---- clampIntervalMinutes ----

    @Test
    public void interval_clampsBelowMin() {
        assertThat(NtpClockUpdater.clampIntervalMinutes(0)).isEqualTo(NtpClockUpdater.MIN_INTERVAL_MINUTES);
        assertThat(NtpClockUpdater.clampIntervalMinutes(-5)).isEqualTo(NtpClockUpdater.MIN_INTERVAL_MINUTES);
    }

    @Test
    public void interval_clampsAboveMax() {
        assertThat(NtpClockUpdater.clampIntervalMinutes(1000)).isEqualTo(NtpClockUpdater.MAX_INTERVAL_MINUTES);
    }

    @Test
    public void interval_passesThroughInRange() {
        assertThat(NtpClockUpdater.clampIntervalMinutes(5)).isEqualTo(5);
        assertThat(NtpClockUpdater.clampIntervalMinutes(1)).isEqualTo(1);
        assertThat(NtpClockUpdater.clampIntervalMinutes(30)).isEqualTo(30);
    }

    // ---- parseIntervalMinutes (shared with DatabaseOpr config load) ----

    @Test
    public void parseInterval_nullOrBlankOrGarbage_fallsBackToDefault() {
        assertThat(NtpClockUpdater.parseIntervalMinutes(null)).isEqualTo(NtpClockUpdater.DEFAULT_INTERVAL_MINUTES);
        assertThat(NtpClockUpdater.parseIntervalMinutes("")).isEqualTo(NtpClockUpdater.DEFAULT_INTERVAL_MINUTES);
        assertThat(NtpClockUpdater.parseIntervalMinutes("abc")).isEqualTo(NtpClockUpdater.DEFAULT_INTERVAL_MINUTES);
    }

    @Test
    public void parseInterval_parsesAndClamps() {
        assertThat(NtpClockUpdater.parseIntervalMinutes(" 10 ")).isEqualTo(10);
        assertThat(NtpClockUpdater.parseIntervalMinutes("0")).isEqualTo(NtpClockUpdater.MIN_INTERVAL_MINUTES);
        assertThat(NtpClockUpdater.parseIntervalMinutes("999")).isEqualTo(NtpClockUpdater.MAX_INTERVAL_MINUTES);
    }

    // ---- shouldReschedule (start()'s re-tune / no-op decision) ----

    @Test
    public void reschedule_whenNotRunning() {
        assertThat(NtpClockUpdater.shouldReschedule(false, -1, 300_000L)).isTrue();
    }

    @Test
    public void reschedule_falseWhenAlreadyAtRequestedCadence() {
        assertThat(NtpClockUpdater.shouldReschedule(true, 300_000L, 300_000L)).isFalse();
    }

    @Test
    public void reschedule_trueWhenCadenceChanges() {
        assertThat(NtpClockUpdater.shouldReschedule(true, 300_000L, 60_000L)).isTrue();
    }

    // ---- computeAppliedOffset (applySyncResult guard: not-running / insane are dropped) ----

    @Test
    public void appliedOffset_nullWhenNotRunning() {
        // A reply that raced past a disable must not touch the clock.
        Integer r = NtpClockUpdater.computeAppliedOffset(false, 1_700_000_010_000L, 1_700_000_008_000L);
        assertThat(r).isNull();
    }

    @Test
    public void appliedOffset_nullWhenInsane() {
        // serverTransmitMs==0 (no time in the reply) is rejected even while running.
        Integer r = NtpClockUpdater.computeAppliedOffset(true, 0L, 1_700_000_008_000L);
        assertThat(r).isNull();
    }

    @Test
    public void appliedOffset_returnsOffsetWhenRunningAndSane() {
        Integer r = NtpClockUpdater.computeAppliedOffset(true, 1_700_000_010_000L, 1_700_000_008_000L);
        assertThat(r).isEqualTo(2_000);
    }

    // ---- disciplinedUtcMs (last-sync timestamp shown as UTC in settings) ----

    @Test
    public void disciplinedUtc_shiftsSlowClockForward() {
        assertThat(NtpClockUpdater.disciplinedUtcMs(8_000L, 2_000)).isEqualTo(10_000L);
    }

    @Test
    public void disciplinedUtc_shiftsFastClockBack() {
        assertThat(NtpClockUpdater.disciplinedUtcMs(10_000L, -1_500)).isEqualTo(8_500L);
    }

    // ---- Lifecycle wiring: toggle gating, without ever touching the network ----

    @Test
    public void refresh_toggleOff_leavesDisciplineStopped() {
        Context ctx = ApplicationProvider.getApplicationContext();
        GeneralVariables.disciplineClockFromNtp = false;
        NtpClockUpdater.refresh(ctx);
        assertThat(NtpClockUpdater.getInstance(ctx).isRunning()).isFalse();
    }

    @Test
    public void refresh_toggleOn_startsWithoutBlockingOnNetwork() {
        // The first sync is posted to the main Handler (not run inline), and this test never
        // idles the Robolectric main looper, so refresh() must return with the updater marked
        // running but UtcTimer.delay untouched — no real sync has actually fired yet.
        Context ctx = ApplicationProvider.getApplicationContext();
        int delayBefore = UtcTimer.delay;
        GeneralVariables.disciplineClockFromNtp = true;
        try {
            NtpClockUpdater.refresh(ctx);
            assertThat(NtpClockUpdater.getInstance(ctx).isRunning()).isTrue();
            assertThat(UtcTimer.delay).isEqualTo(delayBefore);
        } finally {
            GeneralVariables.disciplineClockFromNtp = false;
            NtpClockUpdater.refresh(ctx);
        }
    }

    @Test
    public void refresh_toggleOffAfterOn_restoresPreNtpBaseline() {
        Context ctx = ApplicationProvider.getApplicationContext();
        int originalDelay = 1234;
        UtcTimer.delay = originalDelay;
        try {
            GeneralVariables.disciplineClockFromNtp = true;
            NtpClockUpdater.refresh(ctx); // captures originalDelay as the pre-NTP baseline
            assertThat(NtpClockUpdater.getInstance(ctx).isRunning()).isTrue();

            GeneralVariables.disciplineClockFromNtp = false;
            NtpClockUpdater.refresh(ctx); // no sync ever ran (looper never idled), so this
            // must restore the captured baseline, not just leave delay untouched by chance.
            assertThat(NtpClockUpdater.getInstance(ctx).isRunning()).isFalse();
            assertThat(UtcTimer.delay).isEqualTo(originalDelay);
        } finally {
            UtcTimer.delay = 0;
        }
    }

    // ---- applySyncResult: UI failure-flag wiring for a rejected-while-running reply ----

    @Test
    public void applySyncResult_rejectedWhileRunning_marksSyncFailed() {
        // A reply with no usable transmit timestamp is rejected by computeAppliedOffset even
        // while running; the Time Sync screen must still be told the attempt failed, or it's
        // left showing stale/"Waiting..." status forever despite sync attempts being rejected.
        Context ctx = ApplicationProvider.getApplicationContext();
        GeneralVariables.disciplineClockFromNtp = true;
        NtpClockUpdater.refresh(ctx);
        try {
            GeneralVariables.mutableNtpClockSyncFailed.setValue(false);
            NtpClockUpdater.getInstance(ctx).applySyncResult(0L);
            shadowOf(Looper.getMainLooper()).idle();
            assertThat(GeneralVariables.mutableNtpClockSyncFailed.getValue()).isTrue();
        } finally {
            GeneralVariables.disciplineClockFromNtp = false;
            NtpClockUpdater.refresh(ctx);
        }
    }

    @Test
    public void applySyncResult_rejectedWhileNotRunning_leavesSyncFailedUntouched() {
        // A reply that races past a disable is silently dropped — it must not report a
        // failure the UI has no running sync to attribute it to.
        Context ctx = ApplicationProvider.getApplicationContext();
        GeneralVariables.disciplineClockFromNtp = false;
        NtpClockUpdater.refresh(ctx);
        GeneralVariables.mutableNtpClockSyncFailed.setValue(false);

        NtpClockUpdater.getInstance(ctx).applySyncResult(0L);
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(GeneralVariables.mutableNtpClockSyncFailed.getValue()).isFalse();
    }

    @Test
    public void applySyncResult_afterStop_leavesRestoredBaseline() {
        // The clock must stay at the baseline stop() restored, not be re-disciplined by a
        // reply that was already in flight when the user turned NTP off.
        Context ctx = ApplicationProvider.getApplicationContext();
        int originalDelay = 1234;
        UtcTimer.delay = originalDelay;
        try {
            GeneralVariables.disciplineClockFromNtp = true;
            NtpClockUpdater.refresh(ctx);
            GeneralVariables.disciplineClockFromNtp = false;
            NtpClockUpdater.refresh(ctx);

            NtpClockUpdater.getInstance(ctx).applySyncResult(System.currentTimeMillis() + 3_000L);

            assertThat(UtcTimer.delay).isEqualTo(originalDelay);
        } finally {
            UtcTimer.delay = 0;
        }
    }

    @Test
    public void applySyncResult_writesClockUnderTheSameLockAsStop() throws Exception {
        // The running check and the clock writes have to be one atomic step against stop(),
        // or a reply arriving mid-stop() passes the check and then overwrites the baseline
        // stop() just restored. Holding the instance monitor here must therefore keep the
        // apply path from touching UtcTimer.delay until the monitor is released.
        Context ctx = ApplicationProvider.getApplicationContext();
        NtpClockUpdater updater = NtpClockUpdater.getInstance(ctx);
        UtcTimer.delay = 0;
        GeneralVariables.disciplineClockFromNtp = true;
        NtpClockUpdater.refresh(ctx);
        try {
            Thread applier = new Thread(
                    () -> updater.applySyncResult(System.currentTimeMillis() + 3_000L));
            synchronized (updater) {
                applier.start();
                awaitBlocked(applier);
                assertThat(UtcTimer.delay).isEqualTo(0);
            }
            applier.join(5_000);
            assertThat(applier.isAlive()).isFalse();
            assertThat(UtcTimer.delay).isGreaterThan(2_000);
        } finally {
            GeneralVariables.disciplineClockFromNtp = false;
            NtpClockUpdater.refresh(ctx);
            UtcTimer.delay = 0;
        }
    }

    /** Spin until {@code t} is parked on a monitor, so the assertion under the lock is meaningful. */
    private static void awaitBlocked(Thread t) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (t.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(t.getState()).isEqualTo(Thread.State.BLOCKED);
    }

    @Test
    public void applySyncResult_acceptedWhileRunning_clearsSyncFailed() {
        Context ctx = ApplicationProvider.getApplicationContext();
        GeneralVariables.disciplineClockFromNtp = true;
        NtpClockUpdater.refresh(ctx);
        try {
            GeneralVariables.mutableNtpClockSyncFailed.setValue(true);
            NtpClockUpdater.getInstance(ctx).applySyncResult(System.currentTimeMillis());
            shadowOf(Looper.getMainLooper()).idle();
            assertThat(GeneralVariables.mutableNtpClockSyncFailed.getValue()).isFalse();
        } finally {
            GeneralVariables.disciplineClockFromNtp = false;
            NtpClockUpdater.refresh(ctx);
        }
    }
}
