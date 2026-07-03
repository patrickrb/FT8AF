package com.k1af.ft8af.location;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the shared location-subscription lifecycle extracted into
 * {@link LocationSubscriber} (issue #380) through a fake subclass, so every
 * template decision — toggle dispatch, prepare-gate, permission gate, subscribe
 * failure, fix dispatch, last-known bootstrap, re-tune, teardown — is exercised
 * without a real provider. Robolectric supplies the Context/LocationManager and
 * the paused main looper used for the bootstrap post.
 */
@RunWith(RobolectricTestRunner.class)
public class LocationSubscriberTest {

    /** Minimal concrete subscriber with every hook controllable/observable. */
    private static class FakeSubscriber extends LocationSubscriber {
        boolean enabled = false;
        boolean permissionGranted = true;
        boolean prepareResult = true;
        boolean subscribeResult = true;
        boolean unsubscribeOnPrepare = false; // models GpsClockUpdater's re-tune

        int prepareCalls = 0;
        int permissionChecks = 0;
        int subscribeCalls = 0;
        int onSubscribedCalls = 0;
        int onUnsubscribedCalls = 0;
        int lastKnownCalls = 0;
        final List<Location> fixes = new ArrayList<>();
        LocationListener capturedListener;

        FakeSubscriber(Context context) {
            super(context);
        }

        @Override
        protected String tag() {
            return "FakeSubscriber";
        }

        @Override
        protected boolean isEnabled() {
            return enabled;
        }

        @Override
        protected boolean hasRequiredPermission() {
            permissionChecks++;
            return permissionGranted;
        }

        @Override
        protected synchronized boolean prepareStart() {
            prepareCalls++;
            if (unsubscribeOnPrepare && isRunning()) unsubscribe();
            return prepareResult;
        }

        @Override
        protected boolean subscribeProviders(LocationManager manager, LocationListener listener) {
            subscribeCalls++;
            capturedListener = listener;
            return subscribeResult;
        }

        @Override
        protected void onSubscribed() {
            onSubscribedCalls++;
        }

        @Override
        protected void onUnsubscribed() {
            onUnsubscribedCalls++;
        }

        @Override
        protected void onFix(Location location) {
            fixes.add(location);
        }

        @Override
        protected void applyLastKnown() {
            lastKnownCalls++;
        }
    }

    private FakeSubscriber subscriber;

    @Before
    public void setUp() {
        subscriber = new FakeSubscriber(ApplicationProvider.getApplicationContext());
    }

    @Test
    public void refresh_enabledToggle_subscribesAndRuns() {
        subscriber.enabled = true;
        subscriber.refreshSubscription();

        assertThat(subscriber.isRunning()).isTrue();
        assertThat(subscriber.subscribeCalls).isEqualTo(1);
        assertThat(subscriber.onSubscribedCalls).isEqualTo(1);
    }

    @Test
    public void refresh_disabledToggle_neverStarted_isNoOp() {
        subscriber.enabled = false;
        subscriber.refreshSubscription();

        // stop() on a never-started subscriber must not tear anything down.
        assertThat(subscriber.isRunning()).isFalse();
        assertThat(subscriber.prepareCalls).isEqualTo(0);
        assertThat(subscriber.onUnsubscribedCalls).isEqualTo(0);
    }

    @Test
    public void refresh_toggleFlippedOff_stopsAndUnsubscribes() {
        subscriber.enabled = true;
        subscriber.refreshSubscription();
        subscriber.enabled = false;
        subscriber.refreshSubscription();

        assertThat(subscriber.isRunning()).isFalse();
        assertThat(subscriber.onUnsubscribedCalls).isEqualTo(1);
    }

    @Test
    public void start_prepareStartFalse_skipsPermissionAndSubscribe() {
        // e.g. already running at the requested cadence: the whole start is a no-op.
        subscriber.enabled = true;
        subscriber.prepareResult = false;
        subscriber.refreshSubscription();

        assertThat(subscriber.isRunning()).isFalse();
        assertThat(subscriber.permissionChecks).isEqualTo(0);
        assertThat(subscriber.subscribeCalls).isEqualTo(0);
    }

    @Test
    public void start_permissionDenied_doesNotSubscribe() {
        subscriber.enabled = true;
        subscriber.permissionGranted = false;
        subscriber.refreshSubscription();

        assertThat(subscriber.isRunning()).isFalse();
        assertThat(subscriber.subscribeCalls).isEqualTo(0);
        assertThat(subscriber.onSubscribedCalls).isEqualTo(0);
    }

    @Test
    public void start_subscribeFails_staysStopped_andSkipsBootstrap() {
        subscriber.enabled = true;
        subscriber.subscribeResult = false;
        subscriber.refreshSubscription();
        shadowOf(Looper.getMainLooper()).idle();

        assertThat(subscriber.isRunning()).isFalse();
        assertThat(subscriber.onSubscribedCalls).isEqualTo(0);
        // The last-known bootstrap must only be posted after a successful subscribe.
        assertThat(subscriber.lastKnownCalls).isEqualTo(0);
    }

    @Test
    public void start_postsLastKnownBootstrap_toMainLooper() {
        subscriber.enabled = true;
        subscriber.refreshSubscription();

        // Posted, not run inline: nothing until the main looper drains.
        assertThat(subscriber.lastKnownCalls).isEqualTo(0);
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(subscriber.lastKnownCalls).isEqualTo(1);
    }

    @Test
    public void liveFix_isDispatchedToOnFix() {
        subscriber.enabled = true;
        subscriber.refreshSubscription();

        Location fix = new Location(LocationManager.GPS_PROVIDER);
        subscriber.capturedListener.onLocationChanged(fix);

        assertThat(subscriber.fixes).containsExactly(fix);
    }

    @Test
    public void refresh_whileRunning_prepareFalse_doesNotResubscribe() {
        // Models GridLocationUpdater / an at-cadence GpsClockUpdater: repeated refreshes
        // while running must not churn the subscription.
        subscriber.enabled = true;
        subscriber.refreshSubscription();
        subscriber.prepareResult = false;
        subscriber.refreshSubscription();

        assertThat(subscriber.isRunning()).isTrue();
        assertThat(subscriber.subscribeCalls).isEqualTo(1);
    }

    @Test
    public void refresh_whileRunning_retuneViaPrepare_resubscribes() {
        // Models GpsClockUpdater's interval change: prepareStart() unsubscribes the stale
        // cadence and lets the start proceed to a fresh subscribe.
        subscriber.enabled = true;
        subscriber.refreshSubscription();
        subscriber.unsubscribeOnPrepare = true;
        subscriber.refreshSubscription();

        assertThat(subscriber.isRunning()).isTrue();
        assertThat(subscriber.subscribeCalls).isEqualTo(2);
        assertThat(subscriber.onUnsubscribedCalls).isEqualTo(1);
        assertThat(subscriber.onSubscribedCalls).isEqualTo(2);
    }

    @Test
    public void stop_dropsListenerBeforeOnUnsubscribed_soLateFixesStillDispatchButGuarded() {
        // After a stop, the base has cleared running; a fix that raced past the disable is
        // still delivered to onFix (the base doesn't filter), so subclasses must gate on
        // isRunning() — exactly what GpsClockUpdater.computeAppliedOffset does.
        subscriber.enabled = true;
        subscriber.refreshSubscription();
        LocationListener listener = subscriber.capturedListener;

        subscriber.enabled = false;
        subscriber.refreshSubscription();
        assertThat(subscriber.isRunning()).isFalse();

        listener.onLocationChanged(new Location(LocationManager.GPS_PROVIDER));
        assertThat(subscriber.fixes).hasSize(1);
        assertThat(subscriber.isRunning()).isFalse();
    }
}
