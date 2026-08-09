package radio.ks3ckc.ft8af.rota

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Names the road the rover is on, cheaply and without ever blocking the caller.
 *
 * Reverse geocoding is a network round trip on most devices, and fixes arrive
 * about once a second — so this is a *cache with a refresh policy*, not a
 * lookup. [labelFor] answers instantly from the last successful geocode and, at
 * most occasionally, kicks off a background refresh. The policy itself lives in
 * pure functions ([shouldRefreshHighway], [highwayLabelForPoint]) so the
 * interesting decisions are testable without a device.
 *
 * Offline behaviour is the point rather than an afterthought: a failed geocode
 * changes nothing, the previous label keeps applying until it goes stale, and
 * after that points simply carry no highway. Nothing here retries, because the
 * next fix a second later is a better trigger than any backoff.
 */
class HighwayResolver(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Seam for tests; production uses the platform geocoder. */
    private val geocode: (Double, Double) -> String? = { _, _ -> null },
    private val useSystemGeocoder: Boolean = true,
) {
    private companion object {
        const val TAG = "HighwayResolver"

        /**
         * How long an in-flight geocode may stay in flight before it is written off.
         *
         * The callback form has no timeout of its own, and a backend that accepts the
         * request and never answers would leave [inFlight] set forever — silently
         * killing highway resolution for the rest of the trip with no error anywhere.
         */
        const val GEOCODE_TIMEOUT_MS = 120_000L
    }

    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedAtMs = 0L

    @Volatile
    private var cachedLat = 0.0

    @Volatile
    private var cachedLon = 0.0

    /** Last position a refresh was *started* for — throttles independently of success. */
    @Volatile
    private var attemptedAtMs = 0L

    @Volatile
    private var attemptedLat = 0.0

    @Volatile
    private var attemptedLon = 0.0

    /** One geocode in flight at a time; a queue would only ever hold stale positions. */
    private val inFlight = AtomicBoolean(false)

    /**
     * The label for a fix, and a nudge to refresh if the cache has drifted.
     *
     * Returns immediately — the refresh it may start lands in the cache for a
     * later point, never this one. That lag is fine: a highway is tens of miles
     * long and the first mile of it being unlabelled costs nothing.
     */
    fun labelFor(
        lat: Double,
        lon: Double,
        stateCode: String?,
    ): String? {
        val now = clock()
        if (shouldRefreshHighway(attemptedAtMs, attemptedLat, attemptedLon, now, lat, lon)) {
            refresh(lat, lon, stateCode, now)
        }
        return highwayLabelForPoint(cached, cachedAtMs, cachedLat, cachedLon, now, lat, lon)
    }

    /** Drop everything — a new trip must not inherit the last one's road. */
    fun reset() {
        cached = null
        cachedAtMs = 0L
        attemptedAtMs = 0L
    }

    private fun refresh(
        lat: Double,
        lon: Double,
        stateCode: String?,
        now: Long,
    ) {
        if (!inFlight.compareAndSet(false, true)) {
            // Reclaim a request that never called back, so one dropped answer doesn't
            // cost every remaining mile of the trip its road name.
            if (now - attemptedAtMs > GEOCODE_TIMEOUT_MS) {
                Log.d(TAG, "abandoning a geocode that never answered")
                inFlight.set(false)
            }
            return
        }
        attemptedAtMs = now
        attemptedLat = lat
        attemptedLon = lon

        val onResult: (String?) -> Unit = { roadName ->
            try {
                classifyHighway(roadName, stateCode)?.let { label ->
                    cached = label
                    cachedAtMs = clock()
                    cachedLat = lat
                    cachedLon = lon
                }
            } finally {
                inFlight.set(false)
            }
        }

        if (!useSystemGeocoder) {
            // Test seam: resolve synchronously through the injected function.
            onResult(runCatching { geocode(lat, lon) }.getOrNull())
            return
        }
        requestFromSystem(lat, lon, onResult)
    }

    /**
     * Ask the platform geocoder, on whichever API the device offers.
     *
     * API 33 added a callback form precisely because the older one blocks on the
     * network; below that we have to move to a thread ourselves, since this is
     * called from the location callback.
     */
    @Suppress("TooGenericExceptionCaught", "DEPRECATION")
    private fun requestFromSystem(
        lat: Double,
        lon: Double,
        onResult: (String?) -> Unit,
    ) {
        if (!Geocoder.isPresent()) {
            Log.d(TAG, "no geocoder backend on this device")
            onResult(null)
            return
        }
        val geocoder = Geocoder(context, Locale.US)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        onResult(roadNameFrom(addresses))
                    }

                    override fun onError(errorMessage: String?) {
                        // Almost always "no network" — expected on the road.
                        Log.d(TAG, "geocode failed: ${errorMessage ?: "?"}")
                        onResult(null)
                    }
                })
            } else {
                Thread({
                    val name =
                        try {
                            roadNameFrom(geocoder.getFromLocation(lat, lon, 1))
                        } catch (e: Exception) {
                            Log.d(TAG, "geocode failed: ${e.javaClass.simpleName}")
                            null
                        }
                    onResult(name)
                }, "rota-geocode").start()
            }
        } catch (e: Exception) {
            Log.d(TAG, "geocode threw: ${e.javaClass.simpleName}")
            onResult(null)
        }
    }
}

/**
 * Pick the road name out of a geocoder result.
 *
 * `thoroughfare` is the road proper; `featureName` is checked first only when it
 * looks like a route designation, because some backends put "I-70" there and the
 * street address in `thoroughfare`. Anything else in `featureName` is a house
 * number or POI name and would classify as a local road, so it is ignored.
 */
internal fun roadNameFrom(addresses: List<Address>?): String? {
    val address = addresses?.firstOrNull() ?: return null
    val feature = address.featureName?.trim()
    if (!feature.isNullOrEmpty() && classifyHighway(feature) != LOCAL_ROAD_LABEL) return feature
    return address.thoroughfare?.trim()?.takeIf { it.isNotEmpty() } ?: feature?.takeIf { it.isNotEmpty() }
}
