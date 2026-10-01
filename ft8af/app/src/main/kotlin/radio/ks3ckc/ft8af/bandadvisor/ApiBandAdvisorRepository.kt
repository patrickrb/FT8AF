package radio.ks3ckc.ft8af.bandadvisor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.bandadvisor.model.BandRecommendation
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.bandadvisor.model.freshnessOf
import radio.ks3ckc.ft8af.bandadvisor.model.isPlausibleCallsign
import radio.ks3ckc.ft8af.bandadvisor.model.normalizeAdvisorGrid
import radio.ks3ckc.ft8af.bandadvisor.model.parseBandRecommendation
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Persistence for the last valid recommendation JSON (offline/stale reuse). */
interface RecommendationCacheStore {
    fun load(): String?
    fun save(json: String)
    fun clear()
}

/** In-memory store for tests and as a default when persistence is unwanted. */
class InMemoryRecommendationCacheStore : RecommendationCacheStore {
    private var json: String? = null
    override fun load(): String? = json
    override fun save(json: String) {
        this.json = json
    }
    override fun clear() {
        json = null
    }
}

/**
 * Production repository: fetches recommendations from the band-advisor
 * service and caches the last valid payload for stale/offline reuse.
 *
 * Network discipline: a fresh cached recommendation for the same request is
 * served without any request; otherwise attempts are spaced at least
 * [MIN_FETCH_INTERVAL_MS] apart unless the user forces a refresh, and a 429/503
 * answer imposes a [RATE_LIMIT_BACKOFF_MS] back-off that even forced refreshes
 * honor. Every failure path degrades to the cache (marked STALE/EXPIRED) or a
 * typed [AdvisorResult.Unavailable] — never an exception.
 */
class ApiBandAdvisorRepository(
    private val cacheStore: RecommendationCacheStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val baseUrl: String = DEFAULT_BASE_URL,
) : BandAdvisorRepository {

    @Volatile
    private var lastAttemptMs = 0L

    @Volatile
    private var rateLimitedUntilMs = 0L

    override suspend fun recommendation(
        request: AdvisorRequest,
        force: Boolean,
    ): AdvisorResult = withContext(Dispatchers.IO) {
        val grid = normalizeAdvisorGrid(request.grid)
            ?: return@withContext AdvisorResult.Unavailable(UnavailableReason.NO_GRID)
        val now = clock()

        // A still-fresh cached answer to the same question needs no network.
        val cachedRec = cachedRecommendation()
        if (cachedRec != null && !force &&
            freshnessOf(cachedRec, now) == Freshness.FRESH &&
            recommendationMatchesRequest(cachedRec, grid, request)
        ) {
            return@withContext AdvisorResult.Available(cachedRec, Freshness.FRESH, fromCache = true)
        }

        if (now < rateLimitedUntilMs) {
            return@withContext cachedOrUnavailable(
                now,
                grid,
                request,
                UnavailableReason.RATE_LIMITED,
                "server asked us to back off",
            )
        }
        if (!force && lastAttemptMs != 0L && now - lastAttemptMs < MIN_FETCH_INTERVAL_MS) {
            return@withContext cachedOrUnavailable(now, grid, request, UnavailableReason.RATE_LIMITED, "cooldown")
        }
        lastAttemptMs = now

        val url = buildUrl(grid, request)
        when (val outcome = fetch(url)) {
            is FetchOutcome.Body -> {
                val parsed = parseBandRecommendation(outcome.body)
                if (parsed == null) {
                    Log.w(TAG, "invalid recommendation payload")
                    cachedOrUnavailable(now, grid, request, UnavailableReason.INVALID_RESPONSE, null)
                } else {
                    cacheStore.save(outcome.body)
                    AdvisorResult.Available(parsed, freshnessOf(parsed, now), fromCache = false)
                }
            }
            is FetchOutcome.HttpError -> {
                if (outcome.code == 429 || outcome.code == 503) {
                    rateLimitedUntilMs = clock() + RATE_LIMIT_BACKOFF_MS
                }
                cachedOrUnavailable(now, grid, request, UnavailableReason.HTTP_ERROR, "http ${outcome.code}")
            }
            is FetchOutcome.Transport ->
                cachedOrUnavailable(now, grid, request, UnavailableReason.OFFLINE, outcome.message)
        }
    }

    override fun cached(): AdvisorResult.Available? {
        val rec = cachedRecommendation() ?: return null
        return AdvisorResult.Available(rec, freshnessOf(rec, clock()), fromCache = true)
    }

    override fun clearCache() {
        cacheStore.clear()
    }

    private fun cachedRecommendation(): BandRecommendation? =
        cacheStore.load()?.let { parseBandRecommendation(it) }

    /** Cache hit (marked with its real freshness) beats a typed failure. */
    private fun cachedOrUnavailable(
        nowMs: Long,
        grid: String,
        request: AdvisorRequest,
        reason: UnavailableReason,
        detail: String?,
    ): AdvisorResult {
        val rec = cachedRecommendation()?.takeIf { recommendationMatchesRequest(it, grid, request) }
            ?: return AdvisorResult.Unavailable(reason, detail)
        return AdvisorResult.Available(rec, freshnessOf(rec, nowMs), fromCache = true)
    }

    private fun wantsPersonal(request: AdvisorRequest): Boolean =
        isPlausibleCallsign(request.callsign)

    private fun buildUrl(grid: String, request: AdvisorRequest): String {
        val sb = StringBuilder(baseUrl)
            .append("/v1/recommendation?grid=").append(encode(grid))
            .append("&goal=").append(encode(request.goal.wireName))
            .append("&mode=").append(encode(request.mode))
        if (wantsPersonal(request)) {
            sb.append("&callsign=").append(encode(request.callsign!!.trim().uppercase()))
        }
        request.targetRegion?.takeIf { it.isNotBlank() }?.let {
            sb.append("&targetRegion=").append(encode(it))
        }
        return sb.toString()
    }

    private sealed interface FetchOutcome {
        data class Body(val body: String) : FetchOutcome
        data class HttpError(val code: Int) : FetchOutcome
        data class Transport(val message: String?) : FetchOutcome
    }

    private fun fetch(url: String): FetchOutcome {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = IO_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                FetchOutcome.HttpError(code)
            } else {
                FetchOutcome.Body(
                    conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() },
                )
            }
        } catch (e: IOException) {
            FetchOutcome.Transport(e.message)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "BandAdvisorApi"
        const val DEFAULT_BASE_URL = "https://ft8af.app/api/band-advisor"
        const val MIN_FETCH_INTERVAL_MS = 60_000L
        const val RATE_LIMIT_BACKOFF_MS = 15L * 60_000
        private const val IO_TIMEOUT_MS = 10_000

        private fun encode(s: String): String =
            URLEncoder.encode(s, StandardCharsets.UTF_8.name())
    }
}

/** An answer to a different station, goal or target cannot satisfy this request. */
internal fun recommendationMatchesRequest(
    rec: BandRecommendation,
    grid: String,
    request: AdvisorRequest,
): Boolean =
    rec.grid.equals(grid, ignoreCase = true) &&
        rec.goal == request.goal &&
        rec.mode.equals(request.mode, ignoreCase = true) &&
        rec.callsign == request.callsign?.takeIf { isPlausibleCallsign(it) }?.trim()?.uppercase() &&
        rec.targetRegion == request.targetRegion
