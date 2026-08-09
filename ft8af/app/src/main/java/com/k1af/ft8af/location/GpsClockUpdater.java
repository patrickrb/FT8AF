package com.k1af.ft8af.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.timer.UtcTimer;

/**
 * Disciplines the app clock from GPS satellite time while
 * {@link GeneralVariables#disciplineClockFromGPS} is enabled (issue #373).
 *
 * <p>FT8 decode depends on the TX/RX cycle aligning to true UTC. The app applies a
 * single millisecond offset — {@link UtcTimer#delay} — on top of the device clock;
 * every RX window, TX start and the slot-timer bar reads UTC through it. This class
 * measures the offset between the device clock and GNSS time and writes it there,
 * the same slot {@code NTP "Sync now"} and the manual correction write.
 *
 * <p>Only the {@link LocationManager#GPS_PROVIDER} is used: its {@link Location#getTime()}
 * comes from the satellites, so it is an independent UTC reference. Network/fused
 * providers derive their time from the very system clock we're trying to correct, so
 * they'd report ~0 offset and defeat the purpose.
 *
 * <p>Stock Android won't let an app set the real system clock, so a software-maintained
 * offset applied to FT8 cycle timing is the primary (and only, unrooted) path — same
 * satisfaction, no root required.
 *
 * <p>Singleton — call {@link #refresh(Context)} whenever the toggle/interval changes or
 * the activity starts. The subscription lifecycle is shared with
 * {@link GridLocationUpdater} via {@link LocationSubscriber} (issue #380); this class
 * supplies the clock-specific pieces: GPS-only provider at a configurable, re-tunable
 * cadence, offset computation on each fix, and capture/restore of the pre-GPS baseline.
 */
public class GpsClockUpdater extends LocationSubscriber {
    private static final String TAG = "GpsClockUpdater";

    /**
     * A fix implying a correction larger than this is treated as bad data and ignored
     * (see {@link #isOffsetSane}). Fix age is not checked separately: {@link #gpsUtcNow}
     * folds it into the implied offset, so a stale fix shows up here as a large offset.
     *
     * <p>This bound was ±1 hour, which is far looser than anything FT8 can survive: the
     * offset it admits is written straight into {@link UtcTimer#delay}, which moves the
     * WHOLE cycle grid — every decode window and every transmit key-up. A phone that is
     * even a few seconds out cannot work FT8 at all, so an hour-scale "correction" can
     * only ever be a mock provider, a bogus fix, or a timezone confusion. 60 s still
     * covers a genuinely unsynced clock (no network for days) while refusing the
     * nonsense; past it the operator has a clock problem to fix, not one to paper over.
     */
    static final long MAX_SANE_OFFSET_MS = 60L * 1000L;

    // A step bound (reject a fix that moves the applied offset by more than 500 ms) was
    // added here and REMOVED again after one activation, because it was actively harmful.
    //
    // The reasoning behind it — GPS time does not jump, so a multi-second step is bad
    // data — is sound in isolation and wrong as a rule, because it has no way to
    // distinguish "this fix is bad" from "the baseline is bad". Pairing it with an
    // unconstrained first fix made the first fix of a run permanent: on the 2026-07-31
    // activation a cold fix 2.6 s after startup applied -1331 ms, and the next ELEVEN
    // fixes — spread over an hour and all agreeing on about -2200 ms — were each refused
    // for being ~870 ms away from it. The clock stayed wrong for the whole session:
    // decodes fell to 5.9/cycle against 8.7 immediately after GPS finally got through,
    // and transmit timing scattered (14 of 110 overs keyed up 5 s or more into the slot).
    //
    // One outlier is noise; eleven consecutive fixes agreeing with each other and
    // disagreeing with the baseline are the truth, and the rule could never act on that.
    // Rejecting a correction is not the safe default it looks like — a stale offset is
    // just as capable of putting the grid off the air as a bad fix is, and unlike a bad
    // fix it never self-corrects.
    //
    // If step-limiting is revisited, it must be able to re-baseline: e.g. accept a run of
    // consecutive fixes that agree with each other and disagree with the applied offset.
    // Don't reintroduce a bare step check.

    /** Configurable update-interval bounds (minutes), per issue #373. */
    static final int MIN_INTERVAL_MINUTES = 1;
    static final int MAX_INTERVAL_MINUTES = 30;
    /** Default interval (minutes) when the persisted value is absent or unparseable. */
    static final int DEFAULT_INTERVAL_MINUTES = 5;

    /** Sentinel for {@link #savedDelayBeforeGps}: no pre-GPS offset captured. */
    private static final int NO_SAVED_DELAY = Integer.MIN_VALUE;

    private static GpsClockUpdater instance;

    private long subscribedIntervalMs = -1;
    // The interval chosen by prepareStart() for the in-flight start(); consumed by
    // subscribeProviders() and onSubscribed(). Only touched under the monitor.
    private long pendingIntervalMs = -1;
    // The offset UtcTimer.delay held *before* GPS discipline took it over (an NTP
    // sync, a manual correction, or 0). Captured on the first start() and restored by stop()
    // so disabling GPS returns the clock to its pre-GPS state instead of clobbering it with
    // manualTimeCorrectionMs, which NTP never writes. NO_SAVED_DELAY means "not disciplining".
    private int savedDelayBeforeGps = NO_SAVED_DELAY;

    // Baseline for the step bound (see MAX_OFFSET_STEP_MS): the last offset GPS actually
    // applied, and whether there has been one at all this discipline run. Reset by stop()
    // so a fresh run's first fix is again free to correct real accumulated drift.
    private volatile boolean hasAppliedOffset = false;
    private volatile int lastAppliedOffsetMs = 0;

    private GpsClockUpdater(Context context) {
        super(context);
    }

    public static synchronized GpsClockUpdater getInstance(Context context) {
        if (instance == null) {
            instance = new GpsClockUpdater(context);
        }
        return instance;
    }

    /**
     * Start, stop, or re-tune the updater to match the current toggle and interval.
     * Safe to call repeatedly.
     */
    public static synchronized void refresh(Context context) {
        getInstance(context).refreshSubscription();
    }

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected boolean isEnabled() {
        return GeneralVariables.disciplineClockFromGPS;
    }

    // GPS_PROVIDER requires ACCESS_FINE_LOCATION; a coarse-only grant (Android 12+
    // "approximate location") can't subscribe to it, so treating coarse as sufficient
    // here would just trade this guard for a SecurityException on subscribe.
    @Override
    protected boolean hasRequiredPermission() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "ACCESS_FINE_LOCATION not granted; skipping start");
            return false;
        }
        return true;
    }

    @Override
    protected synchronized boolean prepareStart() {
        long intervalMs = clampIntervalMinutes(GeneralVariables.gpsClockIntervalMinutes) * 60_000L;

        // Already running at the requested cadence — nothing to do.
        if (!shouldResubscribe(isRunning(), subscribedIntervalMs, intervalMs)) return false;

        // Running at a different cadence: drop the old subscription and re-subscribe. Use
        // unsubscribe(), NOT stop(): a re-tune must not disturb UtcTimer.delay (the GPS
        // offset is still valid) or the captured pre-GPS baseline.
        if (isRunning()) unsubscribe();

        pendingIntervalMs = intervalMs;
        return true;
    }

    @SuppressLint("MissingPermission")
    @Override
    protected boolean subscribeProviders(LocationManager manager, LocationListener listener) {
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, pendingIntervalMs, 0f,
                    listener, Looper.getMainLooper());
            return true;
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException subscribing to GPS: " + se.getMessage());
            return false;
        } catch (IllegalArgumentException iae) {
            // No GPS provider on this device — gracefully no-op (issue #373 acceptance).
            Log.d(TAG, "GPS provider unavailable on this device");
            return false;
        }
    }

    @Override
    protected synchronized void onSubscribed() {
        // Capture the pre-GPS offset once, before any fix overwrites UtcTimer.delay, so stop()
        // can hand the clock back exactly as it was. Only on the first start() — a re-tune
        // reaches here with delay already holding a GPS offset, which must not be captured.
        if (savedDelayBeforeGps == NO_SAVED_DELAY) {
            savedDelayBeforeGps = UtcTimer.delay;
        }
        subscribedIntervalMs = pendingIntervalMs;
        Log.d(TAG, "Started GPS clock discipline, interval " + pendingIntervalMs + "ms");
    }

    @Override
    protected synchronized void onUnsubscribed() {
        subscribedIntervalMs = -1;
    }

    @Override
    protected void onFix(Location location) {
        applyFix(location);
    }

    // Discipline immediately from the last known GPS fix so the clock snaps into
    // alignment without waiting a full interval for the next fix.
    @SuppressLint("MissingPermission")
    @Override
    protected void applyLastKnown() {
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) applyFix(last);
        } catch (SecurityException se) {
            Log.e(TAG, "getLastKnownLocation denied: " + se.getMessage());
        }
    }

    /** Disable discipline: unsubscribe and restore the clock to its pre-GPS offset. */
    @Override
    protected synchronized void stop() {
        if (!isRunning() && savedDelayBeforeGps == NO_SAVED_DELAY) return;
        unsubscribe();

        // Return UtcTimer.delay to whatever it was before GPS took over (an NTP sync, a manual
        // correction, or 0) rather than to manualTimeCorrectionMs — NTP writes delay but never
        // manualTimeCorrectionMs, so restoring the latter would silently discard the NTP sync.
        // A new discipline run starts with no baseline, so its first fix may again correct
        // whatever drift accumulated while GPS was off.
        hasAppliedOffset = false;
        lastAppliedOffsetMs = 0;

        if (savedDelayBeforeGps != NO_SAVED_DELAY) {
            UtcTimer.delay = savedDelayBeforeGps;
            Log.d(TAG, "Stopped GPS clock discipline; restored pre-GPS offset "
                    + savedDelayBeforeGps + "ms");
            savedDelayBeforeGps = NO_SAVED_DELAY;
        }
    }

    /** Compute, sanity-check, and apply the offset from a single GPS fix. */
    private void applyFix(Location location) {
        if (location == null) return;
        long fixUtcMs = location.getTime();
        // computeAppliedOffset also re-checks running, so a fix that was already queued on the
        // main looper when the user disabled discipline (stop() flipped running=false) is
        // dropped instead of re-writing the clock after we've handed it back.
        // Sample both clocks EXACTLY once and feed the same readings to the evaluation and
        // to the log. Reading them twice let the logged "REJECTED fix offset=" disagree
        // with the number actually judged against the bounds — which would undermine the
        // diagnostics this logging exists to provide, and is most likely to bite in the
        // very situation being diagnosed (a clock being corrected underneath us).
        final long fixElapsedNanos = location.getElapsedRealtimeNanos();
        final long nowElapsedNanos = SystemClock.elapsedRealtimeNanos();
        final long nowSystemMs = System.currentTimeMillis();
        int rawOffsetMs = gpsClockOffsetMs(fixUtcMs, fixElapsedNanos, nowElapsedNanos, nowSystemMs);
        Integer offsetMs = computeAppliedOffset(
                isRunning(),
                fixUtcMs,
                fixElapsedNanos,
                nowElapsedNanos,
                nowSystemMs);

        if (offsetMs == null) {
            Log.d(TAG, "Ignoring GPS fix (not running or implausible): fixUtc=" + fixUtcMs);
            // debug.log, not just logcat: this path moves the whole FT8 cycle grid, so a
            // rejected fix has to be visible in the same log the on-air symptoms are.
            // (The old code logged only to logcat, which is why the 2026-07-30 activation
            // could not be diagnosed from the pulled debug.log.)
            if (isRunning()) {
                GeneralVariables.fileLog("GpsClock: REJECTED fix offset=" + rawOffsetMs
                        + "ms (prior=" + (hasAppliedOffset ? lastAppliedOffsetMs + "ms" : "none")
                        + ", absMax=" + MAX_SANE_OFFSET_MS
                        + "ms) — clock left at " + UtcTimer.delay + "ms");
            }
            return;
        }

        GeneralVariables.fileLog("GpsClock: applied offset " + offsetMs + "ms (was "
                + UtcTimer.delay + "ms, prior GPS="
                + (hasAppliedOffset ? lastAppliedOffsetMs + "ms" : "none") + ")");
        lastAppliedOffsetMs = offsetMs;
        hasAppliedOffset = true;
        UtcTimer.delay = offsetMs;
        GeneralVariables.gpsClockOffsetMs = offsetMs;
        // The UI renders this as "... UTC", so post the disciplined time, not the raw
        // (possibly-wrong) system clock we just computed a correction for.
        // Same single sampling as the evaluation above, so the displayed last-sync instant
        // is the one this offset was actually computed against.
        GeneralVariables.mutableGpsClockSync.postValue(disciplinedUtcMs(nowSystemMs, offsetMs));
        Log.d(TAG, "GPS clock discipline applied offset " + offsetMs + "ms");
    }

    // =====================================================================
    // Pure, testable decision/geometry logic (no LocationManager needed).
    // =====================================================================

    /**
     * True UTC "now" implied by a GPS fix, aged forward by how long ago the fix was
     * taken. {@code getTime()} is the UTC instant of the fix; {@code elapsedRealtimeNanos}
     * timestamps it on the monotonic clock, which (unlike the wall clock) can't be
     * yanked by the very correction we're computing — so their difference is a clean
     * measure of the fix's age.
     */
    static long gpsUtcNow(long fixUtcMs, long fixElapsedRealtimeNanos, long nowElapsedRealtimeNanos) {
        long ageMs = (nowElapsedRealtimeNanos - fixElapsedRealtimeNanos) / 1_000_000L;
        if (ageMs < 0) ageMs = 0; // fix stamped in the future — clock quirk; treat as fresh
        return fixUtcMs + ageMs;
    }

    /**
     * Offset to add to {@link System#currentTimeMillis()} so the app clock matches GPS.
     * Positive means the device clock is slow (behind GPS) and needs shifting forward.
     */
    static int gpsClockOffsetMs(long fixUtcMs, long fixElapsedRealtimeNanos,
                                long nowElapsedRealtimeNanos, long nowSystemMs) {
        long offset = gpsUtcNow(fixUtcMs, fixElapsedRealtimeNanos, nowElapsedRealtimeNanos) - nowSystemMs;
        if (offset > Integer.MAX_VALUE) offset = Integer.MAX_VALUE;
        if (offset < Integer.MIN_VALUE) offset = Integer.MIN_VALUE;
        return (int) offset;
    }

    /**
     * Whether a fix should be trusted to discipline the clock: it must carry a real UTC
     * timestamp ({@code getTime() > 0}) and imply a correction within {@link #MAX_SANE_OFFSET_MS}.
     */
    static boolean isOffsetSane(long fixUtcMs, int offsetMs) {
        if (fixUtcMs <= 0) return false;
        return Math.abs((long) offsetMs) <= MAX_SANE_OFFSET_MS;
    }

    /** Coerce a configured update interval into the allowed range (minutes). */
    public static int clampIntervalMinutes(int minutes) {
        if (minutes < MIN_INTERVAL_MINUTES) return MIN_INTERVAL_MINUTES;
        return Math.min(minutes, MAX_INTERVAL_MINUTES);
    }

    /**
     * Parse a persisted interval string into a valid interval (minutes): the default when it
     * is null/blank/non-numeric, otherwise the parsed value clamped into range. Shared with
     * {@code DatabaseOpr}'s config load so the fallback and clamp are covered by one test.
     */
    public static int parseIntervalMinutes(String raw) {
        if (raw == null) return DEFAULT_INTERVAL_MINUTES;
        int minutes;
        try {
            minutes = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL_MINUTES;
        }
        return clampIntervalMinutes(minutes);
    }

    /**
     * The corrected UTC instant for a system-clock reading: what the disciplined clock
     * says "now" is. Used for the last-sync timestamp shown (as UTC) in settings.
     */
    static long disciplinedUtcMs(long nowSystemMs, int offsetMs) {
        return nowSystemMs + offsetMs;
    }

    /**
     * Whether {@code start()} needs to (re)subscribe: yes when not currently running, or when
     * running at a cadence different from the one requested. A no-op when already subscribed at
     * the requested interval, so repeated refreshes don't churn the location subscription.
     */
    static boolean shouldResubscribe(boolean running, long subscribedIntervalMs, long requestedIntervalMs) {
        return !running || subscribedIntervalMs != requestedIntervalMs;
    }

    /**
     * The offset to apply for a fix, or {@code null} to ignore it. Ignored when discipline is
     * no longer running (a fix that raced past a disable) or when the implied correction is
     * implausible ({@link #isOffsetSane}). Kept pure so both guard branches are unit-tested
     * without a {@link LocationManager}.
     */
    static Integer computeAppliedOffset(boolean running, long fixUtcMs, long fixElapsedRealtimeNanos,
                                        long nowElapsedRealtimeNanos, long nowSystemMs) {
        if (!running) return null;
        int offsetMs = gpsClockOffsetMs(fixUtcMs, fixElapsedRealtimeNanos, nowElapsedRealtimeNanos, nowSystemMs);
        if (!isOffsetSane(fixUtcMs, offsetMs)) return null;
        return offsetMs;
    }
}
