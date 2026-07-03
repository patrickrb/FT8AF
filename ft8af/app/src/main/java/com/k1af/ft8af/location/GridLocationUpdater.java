package com.k1af.ft8af.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.google.android.gms.maps.model.LatLng;

/**
 * Subscribes to system location updates while {@link GeneralVariables#autoUpdateGridFromGPS}
 * is enabled, and writes the resulting Maidenhead grid back to GeneralVariables + config.
 *
 * Singleton — call {@link #refresh(Context, MainViewModel)} whenever the toggle changes
 * or when the activity starts. The subscription lifecycle lives in
 * {@link LocationSubscriber} (issue #380); this class supplies the grid-specific pieces:
 * all enabled providers at a fixed 5-minute cadence, and lat/lon → Maidenhead grid on
 * each fix.
 */
public class GridLocationUpdater extends LocationSubscriber {
    private static final String TAG = "GridLocationUpdater";

    // 5-minute update interval, 1km min distance — keeps battery use light.
    private static final long MIN_TIME_MS = 5 * 60 * 1000L;
    private static final float MIN_DISTANCE_M = 1000f;

    private static GridLocationUpdater instance;

    // The view model whose databaseOpr receives grid writes. refresh() records the caller's
    // view model in requestedViewModel; prepareStart() latches it into mainViewModel only
    // when a start actually proceeds. This mirrors the pre-refactor behavior, where the
    // anonymous listener captured the view model at subscribe time — a refresh() while
    // already running kept writing through the originally captured view model.
    private MainViewModel requestedViewModel;
    private MainViewModel mainViewModel;

    private GridLocationUpdater(Context context) {
        super(context);
    }

    public static synchronized GridLocationUpdater getInstance(Context context) {
        if (instance == null) {
            instance = new GridLocationUpdater(context);
        }
        return instance;
    }

    /**
     * Start or stop the updater based on the current toggle state.
     * Safe to call repeatedly.
     */
    public static synchronized void refresh(Context context, MainViewModel mainViewModel) {
        GridLocationUpdater u = getInstance(context);
        u.setRequestedViewModel(mainViewModel);
        u.refreshSubscription();
    }

    private synchronized void setRequestedViewModel(MainViewModel viewModel) {
        this.requestedViewModel = viewModel;
    }

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected boolean isEnabled() {
        return GeneralVariables.autoUpdateGridFromGPS;
    }

    @Override
    protected boolean hasRequiredPermission() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Location permission not granted; skipping start");
            return false;
        }
        return true;
    }

    @Override
    protected synchronized boolean prepareStart() {
        if (isRunning()) return false;
        mainViewModel = requestedViewModel;
        return true;
    }

    // Use the best available provider; subscribe to all enabled ones for robustness.
    @SuppressLint("MissingPermission")
    @Override
    protected boolean subscribeProviders(LocationManager manager, LocationListener listener) {
        boolean subscribed = false;
        for (String provider : manager.getProviders(true)) {
            try {
                manager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M,
                        listener, Looper.getMainLooper());
                subscribed = true;
            } catch (SecurityException se) {
                Log.e(TAG, "SecurityException subscribing to " + provider + ": " + se.getMessage());
            } catch (IllegalArgumentException iae) {
                // provider may not exist on this device
            }
        }
        if (!subscribed) {
            Log.d(TAG, "No providers subscribed");
        }
        return subscribed;
    }

    @Override
    protected void onFix(Location location) {
        applyGridFromLatLng(location.getLatitude(), location.getLongitude(), mainViewModel);
    }

    // Immediate update from the last known location so the grid refreshes promptly.
    @Override
    protected void applyLastKnown() {
        LatLng latLng = MaidenheadGrid.getLocalLocation(appContext);
        if (latLng != null) {
            applyGridFromLatLng(latLng.latitude, latLng.longitude, mainViewModel);
        }
    }

    private void applyGridFromLatLng(double lat, double lon, MainViewModel mainViewModel) {
        String grid = gridUpdateFor(lat, lon, GeneralVariables.getMyMaidenheadGrid());
        if (grid == null) return;
        GeneralVariables.setMyMaidenheadGrid(grid);
        if (mainViewModel != null && mainViewModel.databaseOpr != null) {
            mainViewModel.databaseOpr.writeConfig("grid", grid, null);
        }
        Log.d(TAG, "Updated grid from GPS: " + grid);
    }

    /**
     * Decide the grid to write for a GPS fix: the Maidenhead grid at
     * (lat, lon) if it is valid and differs from {@code currentGrid},
     * otherwise {@code null} (meaning "no change, don't write").
     *
     * Extracted from {@link #applyGridFromLatLng} so the update-decision
     * logic can be unit-tested without a {@link LocationManager}.
     */
    static String gridUpdateFor(double lat, double lon, String currentGrid) {
        String grid = MaidenheadGrid.getGridSquare(new LatLng(lat, lon));
        if (grid == null || grid.isEmpty()) return null;
        if (grid.equals(currentGrid)) return null;
        return grid;
    }
}
