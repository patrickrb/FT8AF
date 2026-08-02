package radio.ks3ckc.ft8af.rota

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import radio.ks3ckc.ft8af.location.hasLocationPermission

/**
 * Subscribes to GPS while a trip is running and hands every fix to
 * [RotaTripManager], which samples them down into route breadcrumbs.
 *
 * Deliberately *not* built on [com.k1af.ft8af.location.LocationSubscriber]: that
 * base class exists for the two toggle-driven, low-cadence consumers (grid
 * auto-update every 5 minutes, GPS clock), and its lifecycle is tied to those
 * `GeneralVariables` toggles. Trip mode has the opposite shape — high cadence,
 * owned by a foreground service, running only between trip start and trip end —
 * so it subscribes directly and keeps its own lifecycle.
 *
 * The subscription asks for a fix every second: the sampler decides what is worth
 * keeping, and asking for more than we keep costs nothing extra because the GPS
 * chip is already on and fixed while navigating. That wide gap between request
 * cadence and stored cadence is exactly what SmartBeaconing needs — it can only
 * peg a corner it saw happen, and it can only place a QSO where the rover was if
 * a recent fix exists.
 */
class RotaLocationTracker(context: Context) {
    private val appContext = context.applicationContext

    private var locationManager: LocationManager? = null

    @Volatile
    private var running = false

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                RotaTripManager.onLocationFix(location)
            }

            // The three no-op overrides below are abstract on API < 30, so they must
            // stay even though only onLocationChanged carries anything we want.
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }

    fun hasPermission(): Boolean = hasLocationPermission(appContext)

    /** Returns true when at least one provider is feeding us fixes. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            Log.d(TAG, "location permission not granted; tracking not started")
            return false
        }
        val manager =
            locationManager
                ?: (appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                    ?.also { locationManager = it }
                ?: return false

        var subscribed = false
        // GPS first (the accurate one while moving); network as a backstop so a
        // cold start or a tunnel exit still produces something.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!manager.isProviderEnabled(provider)) continue
                manager.requestLocationUpdates(
                    provider,
                    REQUEST_INTERVAL_MS,
                    REQUEST_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
                subscribed = true
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException on $provider: ${e.message}")
            } catch (_: IllegalArgumentException) {
                // Provider absent on this device.
            }
        }
        running = subscribed
        Log.d(TAG, if (subscribed) "tracking started" else "no location provider available")
        return subscribed
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            locationManager?.removeUpdates(listener)
        } catch (_: Exception) {
        }
        running = false
        Log.d(TAG, "tracking stopped")
    }

    companion object {
        private const val TAG = "RotaLocationTracker"

        /** Ask for a fix this often; [SmartBeaconSampler] decides what to keep. */
        private const val REQUEST_INTERVAL_MS = 1_000L
        private const val REQUEST_DISTANCE_M = 0f
    }
}
