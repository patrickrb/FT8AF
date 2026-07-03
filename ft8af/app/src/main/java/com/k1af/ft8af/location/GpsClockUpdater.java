package com.k1af.ft8af.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
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
 * the activity starts. Mirrors {@link GridLocationUpdater}.
 */
public class GpsClockUpdater {
    private static final String TAG = "GpsClockUpdater";

    /**
     * A fix older than this (or one implying a correction larger than this) is treated
     * as bad data and ignored. GPS UTC and the device clock are never legitimately an
     * hour apart; a value that big means a mock provider, a bogus fix, or a timezone
     * confusion, none of which should be allowed to yank the transmit timing.
     */
    static final long MAX_SANE_OFFSET_MS = 60L * 60L * 1000L;

    /** Configurable update-interval bounds (minutes), per issue #373. */
    static final int MIN_INTERVAL_MINUTES = 1;
    static final int MAX_INTERVAL_MINUTES = 30;

    private static GpsClockUpdater instance;

    private final Context appContext;
    private LocationManager locationManager;
    private boolean running = false;
    private long subscribedIntervalMs = -1;
    private LocationListener listener;

    private GpsClockUpdater(Context context) {
        this.appContext = context.getApplicationContext();
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
        GpsClockUpdater u = getInstance(context);
        if (GeneralVariables.disciplineClockFromGPS) {
            u.start();
        } else {
            u.stop();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void start() {
        long intervalMs = clampIntervalMinutes(GeneralVariables.gpsClockIntervalMinutes) * 60_000L;

        // Already running at the requested cadence — nothing to do.
        if (running && intervalMs == subscribedIntervalMs) return;

        // Running at a different cadence: tear the old subscription down and re-subscribe.
        if (running) stop();

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Location permission not granted; skipping start");
            return;
        }
        if (locationManager == null) {
            locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        }
        if (locationManager == null) {
            Log.e(TAG, "No LocationManager available");
            return;
        }

        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                applyFix(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f,
                    listener, Looper.getMainLooper());
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException subscribing to GPS: " + se.getMessage());
            listener = null;
            return;
        } catch (IllegalArgumentException iae) {
            // No GPS provider on this device — gracefully no-op (issue #373 acceptance).
            Log.d(TAG, "GPS provider unavailable on this device");
            listener = null;
            return;
        }

        running = true;
        subscribedIntervalMs = intervalMs;
        Log.d(TAG, "Started GPS clock discipline, interval " + intervalMs + "ms");

        // Discipline immediately from the last known GPS fix so the clock snaps into
        // alignment without waiting a full interval for the next fix.
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (last != null) applyFix(last);
                } catch (SecurityException se) {
                    Log.e(TAG, "getLastKnownLocation denied: " + se.getMessage());
                }
            }
        });
    }

    private synchronized void stop() {
        if (!running) return;
        if (locationManager != null && listener != null) {
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception e) {
                Log.e(TAG, "removeUpdates failed: " + e.getMessage());
            }
        }
        listener = null;
        running = false;
        subscribedIntervalMs = -1;

        // Hand the clock back to the persisted manual correction so disabling GPS
        // doesn't strand the last GPS offset in place until the next relaunch.
        UtcTimer.delay = GeneralVariables.manualTimeCorrectionMs;
        Log.d(TAG, "Stopped GPS clock discipline; restored manual offset "
                + GeneralVariables.manualTimeCorrectionMs + "ms");
    }

    /** Compute, sanity-check, and apply the offset from a single GPS fix. */
    private void applyFix(Location location) {
        if (location == null) return;
        long fixUtcMs = location.getTime();
        int offsetMs = gpsClockOffsetMs(
                fixUtcMs,
                location.getElapsedRealtimeNanos(),
                SystemClock.elapsedRealtimeNanos(),
                System.currentTimeMillis());

        if (!isOffsetSane(fixUtcMs, offsetMs)) {
            Log.d(TAG, "Ignoring GPS fix: implausible offset " + offsetMs + "ms (fixUtc=" + fixUtcMs + ")");
            return;
        }

        UtcTimer.delay = offsetMs;
        GeneralVariables.gpsClockOffsetMs = offsetMs;
        GeneralVariables.gpsClockLastSyncSystemMs = System.currentTimeMillis();
        GeneralVariables.mutableGpsClockSync.postValue(GeneralVariables.gpsClockLastSyncSystemMs);
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
}
