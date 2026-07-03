package com.k1af.ft8af.location;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Shared location-subscription lifecycle for {@link GridLocationUpdater} and
 * {@link GpsClockUpdater} (issue #380).
 *
 * <p>Owns the half that the two updaters used to duplicate: the toggle-driven
 * start/stop dispatch, the permission gate, the lazy {@link LocationManager}
 * lookup with null-guard, the four-method {@link LocationListener} where only
 * {@code onLocationChanged} matters, the subscribe/teardown bookkeeping, and the
 * "apply immediately from the last-known location via {@code Handler.post}"
 * bootstrap. Subclasses fill in template hooks for what genuinely differs:
 * provider selection and cadence ({@link #subscribeProviders}), the
 * enabled-toggle predicate ({@link #isEnabled}), the permission requirement
 * ({@link #hasRequiredPermission}), what to do with a fix ({@link #onFix}), and
 * the last-known-fix bootstrap ({@link #applyLastKnown}).
 *
 * <p>All lifecycle transitions are serialized on {@code this}; hooks are invoked
 * with the monitor held unless noted otherwise. {@code running} is volatile so a
 * subclass may consult {@link #isRunning()} from a main-looper fix callback
 * without the monitor and still observe a concurrent {@link #stop()} (see
 * {@code GpsClockUpdater#applyFix}).
 */
abstract class LocationSubscriber {

    protected final Context appContext;
    /** Lazily looked up on first successful start; kept for teardown and last-known reads. */
    protected LocationManager locationManager;
    // Volatile: written under the monitor, but read without it from main-looper fix
    // callbacks (GpsClockUpdater drops a fix that raced past a disable).
    private volatile boolean running = false;
    private LocationListener listener;

    protected LocationSubscriber(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // =====================================================================
    // Template hooks — the parts that differ between the two updaters.
    // =====================================================================

    /** Log tag for the shared lifecycle messages. */
    protected abstract String tag();

    /** The {@code GeneralVariables} toggle gating this subscriber. */
    protected abstract boolean isEnabled();

    /**
     * Whether the granted location permissions are sufficient to subscribe.
     * Implementations log their own reason and return {@code false} on denial.
     */
    protected abstract boolean hasRequiredPermission();

    /**
     * Decide whether this {@link #start()} call should proceed toward a (re)subscribe.
     * Runs first, before the permission and manager checks, with the monitor held.
     * Return {@code false} to make the start a no-op (e.g. already running at the
     * requested cadence). Implementations that support re-tuning drop the stale
     * subscription here (via {@link #unsubscribe()}) before returning {@code true}.
     */
    protected abstract boolean prepareStart();

    /**
     * Subscribe {@code listener} to this subscriber's provider(s) at its cadence.
     * Implementations own their {@code SecurityException} / {@code IllegalArgumentException}
     * handling and logging. Return {@code true} if at least one provider subscribed;
     * on {@code false} the base clears the listener and abandons the start.
     */
    protected abstract boolean subscribeProviders(LocationManager manager, LocationListener listener);

    /**
     * Called once after a successful subscribe, with the monitor held, before the
     * last-known bootstrap is posted. Default no-op.
     */
    protected void onSubscribed() {
    }

    /**
     * Called at the end of every {@link #unsubscribe()}, with the monitor held,
     * after the listener is removed and {@code running} cleared. Default no-op.
     */
    protected void onUnsubscribed() {
    }

    /** Handle a live location fix (invoked on the main looper, without the monitor). */
    protected abstract void onFix(Location location);

    /**
     * Apply an immediate update from the last-known location so the subscriber takes
     * effect promptly instead of waiting a full update interval. Posted to the main
     * looper right after a successful subscribe; runs without the monitor.
     */
    protected abstract void applyLastKnown();

    // =====================================================================
    // Shared lifecycle.
    // =====================================================================

    protected final boolean isRunning() {
        return running;
    }

    /**
     * Start or stop this subscriber based on the current toggle state.
     * Safe to call repeatedly.
     */
    final synchronized void refreshSubscription() {
        if (isEnabled()) {
            start();
        } else {
            stop();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void start() {
        if (!prepareStart()) return;
        if (!hasRequiredPermission()) return;
        if (locationManager == null) {
            locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        }
        if (locationManager == null) {
            Log.e(tag(), "No LocationManager available");
            return;
        }

        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                onFix(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };

        if (!subscribeProviders(locationManager, listener)) {
            listener = null;
            return;
        }

        onSubscribed();
        running = true;

        // Fire an immediate update from the last-known location so the effect (grid
        // refresh / clock snap) doesn't have to wait for the first live fix.
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                applyLastKnown();
            }
        });
    }

    /**
     * Stop this subscriber. The base drops the subscription only if running; a subclass
     * may override to add teardown side effects (and must still call {@link #unsubscribe()}).
     */
    protected synchronized void stop() {
        if (!running) return;
        unsubscribe();
    }

    /**
     * Drop the location subscription and clear {@code running}, without any
     * subclass-specific teardown side effects. Used by {@link #stop()} and by
     * re-tunes in {@link #prepareStart()} implementations.
     */
    protected final synchronized void unsubscribe() {
        if (locationManager != null && listener != null) {
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception e) {
                Log.e(tag(), "removeUpdates failed: " + e.getMessage());
            }
        }
        listener = null;
        running = false;
        onUnsubscribed();
    }
}
