package com.k1af.ft8af.location;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.timer.UtcTimer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Covers the pure offset/should-apply/interval logic extracted from
 * {@link GpsClockUpdater} for GPS clock discipline (issue #373), plus the
 * toggle/permission gating the class wires into {@link LocationSubscriber}
 * (issue #380). Robolectric is used so the Android-typed class links cleanly;
 * the arithmetic under test never touches a {@code LocationManager}.
 */
@RunWith(RobolectricTestRunner.class)
public class GpsClockUpdaterTest {

    private static final long MS = 1_000_000L; // ns per ms

    // ---- gpsUtcNow: age the fix forward on the monotonic clock ----

    @Test
    public void gpsUtcNow_agesFixByElapsedRealtimeDelta() {
        // Fix taken at UTC=1000ms, stamped at elapsed=5000ms; "now" is elapsed=5250ms.
        // The fix is 250ms old, so true UTC now = 1000 + 250 = 1250.
        long utcNow = GpsClockUpdater.gpsUtcNow(1000L, 5000L * MS, 5250L * MS);
        assertThat(utcNow).isEqualTo(1250L);
    }

    @Test
    public void gpsUtcNow_freshFixIsNotAged() {
        assertThat(GpsClockUpdater.gpsUtcNow(1000L, 5000L * MS, 5000L * MS)).isEqualTo(1000L);
    }

    @Test
    public void gpsUtcNow_negativeAgeClampedToZero() {
        // A fix stamped "in the future" (clock quirk) must not push UTC backwards.
        assertThat(GpsClockUpdater.gpsUtcNow(1000L, 5000L * MS, 4000L * MS)).isEqualTo(1000L);
    }

    // ---- gpsClockOffsetMs: offset to add to System.currentTimeMillis() ----

    @Test
    public void offset_isPositive_whenDeviceClockIsSlow() {
        // GPS UTC now = 10_000 (fresh fix); device clock reads 8_000 => +2_000 to catch up.
        int offset = GpsClockUpdater.gpsClockOffsetMs(10_000L, 5000L * MS, 5000L * MS, 8_000L);
        assertThat(offset).isEqualTo(2_000);
    }

    @Test
    public void offset_isNegative_whenDeviceClockIsFast() {
        int offset = GpsClockUpdater.gpsClockOffsetMs(10_000L, 5000L * MS, 5000L * MS, 12_500L);
        assertThat(offset).isEqualTo(-2_500);
    }

    @Test
    public void offset_accountsForFixAge() {
        // Fix UTC=10_000 taken 300ms ago => true now 10_300; device reads 10_000 => +300.
        int offset = GpsClockUpdater.gpsClockOffsetMs(10_000L, 5000L * MS, 5300L * MS, 10_000L);
        assertThat(offset).isEqualTo(300);
    }

    // ---- isOffsetSane: reject bad fixes ----

    @Test
    public void sane_acceptsRealisticOffset() {
        assertThat(GpsClockUpdater.isOffsetSane(1_700_000_000_000L, 2_500)).isTrue();
        assertThat(GpsClockUpdater.isOffsetSane(1_700_000_000_000L, -30_000)).isTrue();
    }

    @Test
    public void sane_rejectsZeroFixTime() {
        // getTime()==0 means "no time in this fix" — never trust it.
        assertThat(GpsClockUpdater.isOffsetSane(0L, 500)).isFalse();
    }

    @Test
    public void sane_rejectsAbsurdOffset() {
        // Beyond an hour: mock provider / timezone confusion / bogus fix.
        int tooBig = (int) (GpsClockUpdater.MAX_SANE_OFFSET_MS + 1);
        assertThat(GpsClockUpdater.isOffsetSane(1_700_000_000_000L, tooBig)).isFalse();
        assertThat(GpsClockUpdater.isOffsetSane(1_700_000_000_000L, -tooBig)).isFalse();
    }

    @Test
    public void sane_acceptsExactlyAtBound() {
        int atBound = (int) GpsClockUpdater.MAX_SANE_OFFSET_MS;
        assertThat(GpsClockUpdater.isOffsetSane(1_700_000_000_000L, atBound)).isTrue();
    }

    // ---- clampIntervalMinutes ----

    @Test
    public void interval_clampsBelowMin() {
        assertThat(GpsClockUpdater.clampIntervalMinutes(0)).isEqualTo(GpsClockUpdater.MIN_INTERVAL_MINUTES);
        assertThat(GpsClockUpdater.clampIntervalMinutes(-5)).isEqualTo(GpsClockUpdater.MIN_INTERVAL_MINUTES);
    }

    @Test
    public void interval_clampsAboveMax() {
        assertThat(GpsClockUpdater.clampIntervalMinutes(1000)).isEqualTo(GpsClockUpdater.MAX_INTERVAL_MINUTES);
    }

    @Test
    public void interval_passesThroughInRange() {
        assertThat(GpsClockUpdater.clampIntervalMinutes(5)).isEqualTo(5);
        assertThat(GpsClockUpdater.clampIntervalMinutes(1)).isEqualTo(1);
        assertThat(GpsClockUpdater.clampIntervalMinutes(30)).isEqualTo(30);
    }

    // ---- parseIntervalMinutes (shared with DatabaseOpr config load) ----

    @Test
    public void parseInterval_nullOrBlankOrGarbage_fallsBackToDefault() {
        assertThat(GpsClockUpdater.parseIntervalMinutes(null)).isEqualTo(GpsClockUpdater.DEFAULT_INTERVAL_MINUTES);
        assertThat(GpsClockUpdater.parseIntervalMinutes("")).isEqualTo(GpsClockUpdater.DEFAULT_INTERVAL_MINUTES);
        assertThat(GpsClockUpdater.parseIntervalMinutes("abc")).isEqualTo(GpsClockUpdater.DEFAULT_INTERVAL_MINUTES);
    }

    @Test
    public void parseInterval_parsesAndClamps() {
        assertThat(GpsClockUpdater.parseIntervalMinutes(" 10 ")).isEqualTo(10);
        assertThat(GpsClockUpdater.parseIntervalMinutes("0")).isEqualTo(GpsClockUpdater.MIN_INTERVAL_MINUTES);
        assertThat(GpsClockUpdater.parseIntervalMinutes("999")).isEqualTo(GpsClockUpdater.MAX_INTERVAL_MINUTES);
    }

    // ---- shouldResubscribe (start()'s re-tune / no-op decision) ----

    @Test
    public void resubscribe_whenNotRunning() {
        // Enabling from a stopped state always subscribes, regardless of the stale interval.
        assertThat(GpsClockUpdater.shouldResubscribe(false, -1, 300_000L)).isTrue();
    }

    @Test
    public void resubscribe_falseWhenAlreadyAtRequestedCadence() {
        // Repeated refresh at the same interval must not churn the subscription.
        assertThat(GpsClockUpdater.shouldResubscribe(true, 300_000L, 300_000L)).isFalse();
    }

    @Test
    public void resubscribe_trueWhenCadenceChanges() {
        assertThat(GpsClockUpdater.shouldResubscribe(true, 300_000L, 60_000L)).isTrue();
    }

    // ---- computeAppliedOffset (applyFix guard: not-running / insane are dropped) ----

    @Test
    public void appliedOffset_nullWhenNotRunning() {
        // A fix that raced past a disable (running flipped false) must not touch the clock.
        Integer r = GpsClockUpdater.computeAppliedOffset(false, 10_000L, 5000L * MS, 5000L * MS, 8_000L);
        assertThat(r).isNull();
    }

    @Test
    public void appliedOffset_nullWhenInsane() {
        // fixUtc==0 (no time in the fix) is rejected even while running.
        Integer r = GpsClockUpdater.computeAppliedOffset(true, 0L, 5000L * MS, 5000L * MS, 8_000L);
        assertThat(r).isNull();
    }

    @Test
    public void appliedOffset_returnsOffsetWhenRunningAndSane() {
        // GPS UTC now = 10_000 (fresh fix), device reads 8_000 => +2_000.
        Integer r = GpsClockUpdater.computeAppliedOffset(true, 10_000L, 5000L * MS, 5000L * MS, 8_000L);
        assertThat(r).isEqualTo(2_000);
    }

    // ---- disciplinedUtcMs (last-sync timestamp shown as UTC in settings) ----

    @Test
    public void disciplinedUtc_shiftsSlowClockForward() {
        // Device 2s behind GPS: the displayed sync instant must be the corrected time.
        assertThat(GpsClockUpdater.disciplinedUtcMs(8_000L, 2_000)).isEqualTo(10_000L);
    }

    @Test
    public void disciplinedUtc_shiftsFastClockBack() {
        assertThat(GpsClockUpdater.disciplinedUtcMs(10_000L, -1_500)).isEqualTo(8_500L);
    }

    // ---- LocationSubscriber wiring (issue #380): toggle + permission gates ----

    @Test
    public void refresh_toggleOff_leavesDisciplineStopped() {
        Context ctx = ApplicationProvider.getApplicationContext();
        GeneralVariables.disciplineClockFromGPS = false;
        GpsClockUpdater.refresh(ctx);
        assertThat(GpsClockUpdater.getInstance(ctx).isRunning()).isFalse();
    }

    @Test
    public void refresh_toggleOn_withoutFinePermission_doesNotStartOrTouchClock() {
        // Robolectric grants no runtime permissions by default, so the FINE-only
        // check must veto the start — and a vetoed start must not capture/alter
        // the pre-GPS clock offset.
        Context ctx = ApplicationProvider.getApplicationContext();
        int delayBefore = UtcTimer.delay;
        GeneralVariables.disciplineClockFromGPS = true;
        try {
            GpsClockUpdater.refresh(ctx);
            assertThat(GpsClockUpdater.getInstance(ctx).isRunning()).isFalse();
            assertThat(UtcTimer.delay).isEqualTo(delayBefore);
        } finally {
            GeneralVariables.disciplineClockFromGPS = false;
        }
    }
}
