package com.bg7yoz.ft8cn.location;

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
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.google.android.gms.maps.model.LatLng;

/**
 * Subscribes to system location updates while {@link GeneralVariables#autoUpdateGridFromGPS}
 * is enabled, and writes the resulting Maidenhead grid back to GeneralVariables + config.
 *
 * Singleton — call {@link #refresh(Context, MainViewModel)} whenever the toggle changes
 * or when the activity starts.
 */
public class GridLocationUpdater {
    private static final String TAG = "GridLocationUpdater";

    // 5-minute update interval, 1km min distance — keeps battery use light.
    private static final long MIN_TIME_MS = 5 * 60 * 1000L;
    private static final float MIN_DISTANCE_M = 1000f;

    private static GridLocationUpdater instance;

    private final Context appContext;
    private LocationManager locationManager;
    private boolean running = false;
    private LocationListener listener;

    private GridLocationUpdater(Context context) {
        this.appContext = context.getApplicationContext();
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
        if (GeneralVariables.autoUpdateGridFromGPS) {
            u.start(mainViewModel);
        } else {
            u.stop();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void start(final MainViewModel mainViewModel) {
        if (running) return;
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
                applyGridFromLatLng(location.getLatitude(), location.getLongitude(), mainViewModel);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };

        // Use the best available provider; subscribe to both for robustness.
        boolean subscribed = false;
        for (String provider : locationManager.getProviders(true)) {
            try {
                locationManager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M,
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
            listener = null;
            return;
        }

        running = true;

        // Also fire an immediate update from last known location so the grid refreshes promptly.
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                LatLng latLng = MaidenheadGrid.getLocalLocation(appContext);
                if (latLng != null) {
                    applyGridFromLatLng(latLng.latitude, latLng.longitude, mainViewModel);
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
