package radio.ks3ckc.ft8af.pota

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.pota.model.PotaLocation
import radio.ks3ckc.ft8af.pota.model.PotaPark
import radio.ks3ckc.ft8af.pota.model.PotaParkWithDistance
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides park discovery for the park picker: recent activations and nearby parks.
 * Caches API responses in memory with a 30-minute TTL to avoid hammering pota.app.
 *
 * The caches are read and written from `Dispatchers.IO` and can be touched
 * concurrently by multiple coroutines (e.g. overlapping sheet opens), so they use
 * thread-safe containers (`ConcurrentHashMap` / `@Volatile`). A benign race may
 * still trigger a duplicate API fetch, but never a corrupted map or crash.
 */
object PotaParkRepository {

    private const val CACHE_TTL_MS = 30 * 60 * 1_000L
    private const val NEARBY_LIMIT = 50

    // POTA's /locations endpoint ships wrong center coordinates for a chunk of
    // foreign regions (e.g. ZA-FS, RU-VL, RO-OT all report centers in Kansas),
    // so the nearest-by-center ranking is noisy: a handful of mislocated regions
    // out-rank the user's real region. We therefore pull parks from a generous
    // set of candidate regions — enough that the user's true region(s) are always
    // included despite the bad rows — and rely on the parks' own (correct)
    // coordinates to produce the final ordering. See the parkpicker logic tests.
    private const val NEARBY_LOCATION_CANDIDATES = 12

    // Final safety net: even after broadening the candidates, a mislocated
    // region can still contribute parks. Those parks carry correct coordinates,
    // so they sort to the bottom — but drop anything implausibly far so a
    // continent-away park never surfaces under "Nearby" for a sparse area.
    private const val NEARBY_MAX_DISTANCE_KM = 1_000.0

    @Volatile
    private var cachedLocations: List<PotaLocation>? = null

    @Volatile
    private var locationsTimestamp = 0L

    private val parkCache = ConcurrentHashMap<String, List<PotaPark>>()
    private val parkCacheTimestamp = ConcurrentHashMap<String, Long>()

    private val lookupCache = ConcurrentHashMap<String, PotaPark>()

    /**
     * Return the [NEARBY_LIMIT] closest parks to [userLat]/[userLng], sorted by
     * distance ascending. Fetches parks for the [NEARBY_LOCATION_CANDIDATES]
     * closest POTA location codes (cached) — a wide net to absorb POTA's
     * mislocated region centers — then ranks by each park's own coordinates and
     * drops anything beyond [NEARBY_MAX_DISTANCE_KM].
     */
    suspend fun nearbyParks(userLat: Double, userLng: Double): List<PotaParkWithDistance> =
        withContext(Dispatchers.IO) {
            val locations = getLocations() ?: return@withContext emptyList()
            val closest =
                findNearestLocationCodes(userLat, userLng, locations, NEARBY_LOCATION_CANDIDATES)
            // Fetch the candidate park lists concurrently; one slow region
            // shouldn't serialize the whole sheet open.
            val allParks = coroutineScope {
                closest.map { code -> async { getParksForLocation(code) } }
                    .awaitAll()
                    .filterNotNull()
                    .flatten()
            }
            sortParksByDistance(allParks, userLat, userLng, NEARBY_LIMIT, NEARBY_MAX_DISTANCE_KM)
        }

    /**
     * Return park details for recent park references from activation history.
     * Names are resolved via the park lookup endpoint (cached).
     */
    suspend fun recentParksWithDetails(): List<PotaPark> = withContext(Dispatchers.IO) {
        val refs = PotaActivationDao.recentParkRefs()
        refs.mapNotNull { ref -> lookupParkCached(ref) }
    }

    /** Fetch a park by reference with in-memory caching. */
    private suspend fun lookupParkCached(reference: String): PotaPark? {
        lookupCache[reference]?.let { return it }
        val park = PotaClient.lookupPark(reference) ?: return null
        lookupCache[reference] = park
        return park
    }

    private suspend fun getLocations(): List<PotaLocation>? {
        val now = System.currentTimeMillis()
        if (cachedLocations != null && now - locationsTimestamp < CACHE_TTL_MS) {
            return cachedLocations
        }
        val result = PotaClient.getLocations() ?: return cachedLocations
        cachedLocations = result
        locationsTimestamp = now
        return result
    }

    private suspend fun getParksForLocation(code: String): List<PotaPark>? {
        val now = System.currentTimeMillis()
        val cached = parkCache[code]
        val ts = parkCacheTimestamp[code] ?: 0L
        if (cached != null && now - ts < CACHE_TTL_MS) return cached
        val result = PotaClient.getParksForLocation(code) ?: return cached
        parkCache[code] = result
        parkCacheTimestamp[code] = now
        return result
    }

    /**
     * Get a [LatLng] from the device GPS, falling back to the user's configured
     * Maidenhead grid. Returns null only if both are unavailable.
     */
    fun getUserLocation(context: Context): LatLng? {
        return MaidenheadGrid.getLocalLocation(context)
            ?: com.k1af.ft8af.GeneralVariables.getMyMaidenhead4Grid()
                ?.let { MaidenheadGrid.gridToLatLng(it) }
    }
}

// ---------------------------------------------------------------------------
// Pure helper functions — extracted for unit testing
// ---------------------------------------------------------------------------

/**
 * Pick the [count] POTA location codes whose center is nearest to ([userLat], [userLng]).
 * Returns the `locationDesc` field (the location code like "US-PA") for each.
 */
internal fun findNearestLocationCodes(
    userLat: Double,
    userLng: Double,
    locations: List<PotaLocation>,
    count: Int,
): List<String> {
    val userPos = LatLng(userLat, userLng)
    return locations
        .map { it to MaidenheadGrid.getDist(userPos, LatLng(it.latitude, it.longitude)) }
        .sortedBy { it.second }
        .take(count)
        .map { it.first.locationDesc }
}

/**
 * Compute the distance from ([userLat], [userLng]) to each park, sort ascending,
 * and return the closest [limit] results. Parks farther than [maxDistanceKm] are
 * dropped (defaults to no cap) so mislocated-region parks can't masquerade as
 * nearby.
 */
internal fun sortParksByDistance(
    parks: List<PotaPark>,
    userLat: Double,
    userLng: Double,
    limit: Int,
    maxDistanceKm: Double = Double.MAX_VALUE,
): List<PotaParkWithDistance> {
    val userPos = LatLng(userLat, userLng)
    return parks
        .filter { it.latitude != 0.0 || it.longitude != 0.0 }
        .map { park ->
            PotaParkWithDistance(
                park = park,
                distanceKm = MaidenheadGrid.getDist(userPos, LatLng(park.latitude, park.longitude)),
            )
        }
        .filter { it.distanceKm <= maxDistanceKm }
        .sortedBy { it.distanceKm }
        .take(limit)
}

/**
 * Split comma-separated park reference strings and deduplicate, preserving
 * first-seen (most-recent) order.
 */
internal fun deduplicateRefs(rawRefs: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    for (raw in rawRefs) {
        for (part in raw.split(",")) {
            val ref = part.trim()
            if (ref.isNotEmpty()) seen.add(ref)
        }
    }
    return seen.toList()
}
