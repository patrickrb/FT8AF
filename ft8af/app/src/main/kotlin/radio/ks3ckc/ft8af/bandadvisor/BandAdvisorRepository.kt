package radio.ks3ckc.ft8af.bandadvisor

import radio.ks3ckc.ft8af.bandadvisor.model.BandRecommendation
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal

/** Parameters for a recommendation request. */
data class AdvisorRequest(
    /** Raw grid as configured (any precision); normalized to 4 chars on send. */
    val grid: String?,
    /** Only sent when personal analytics are enabled AND the callsign is valid. */
    val callsign: String?,
    val goal: OperatingGoal = OperatingGoal.MAKE_CONTACT,
    val mode: String = "FT8",
    /** Optional region for the TARGET goal (server region enum name). */
    val targetRegion: String? = null,
)

/** Why no recommendation could be produced. */
enum class UnavailableReason {
    /** No valid Maidenhead grid configured — the advisor can't run at all. */
    NO_GRID,

    /** Transport failure (offline, DNS, timeout). */
    OFFLINE,

    /** Server answered with a non-200. */
    HTTP_ERROR,

    /** Server answered 200 but the payload failed validation. */
    INVALID_RESPONSE,

    /** Client-side cooldown suppressed the request and no cache exists. */
    RATE_LIMITED,
}

/** Outcome of a recommendation request. */
sealed interface AdvisorResult {
    /**
     * A recommendation is available. [freshness] tells the UI whether to show
     * it plainly (FRESH), with a stale marker (STALE), or as a last-known
     * value with a strong warning (EXPIRED). Partial data shows up inside
     * [BandRecommendation.sources], not as a separate result type.
     */
    data class Available(
        val recommendation: BandRecommendation,
        val freshness: Freshness,
        val fromCache: Boolean,
    ) : AdvisorResult

    data class Unavailable(
        val reason: UnavailableReason,
        val detail: String? = null,
    ) : AdvisorResult
}

/**
 * Source of Band Advisor recommendations. Implementations must never throw
 * from [recommendation] — every failure mode maps to [AdvisorResult.Unavailable]
 * (or a cached [AdvisorResult.Available]) so a backend outage can never
 * disturb FT8 operation.
 */
interface BandAdvisorRepository {
    /**
     * Current recommendation for [request]. [force] bypasses the client-side
     * refresh cooldown (manual refresh button) but never a server rate-limit
     * back-off. Serves the cached recommendation without touching the network
     * while it is still fresh for the same request.
     */
    suspend fun recommendation(request: AdvisorRequest, force: Boolean = false): AdvisorResult

    /** Last known recommendation (any freshness) without any network. */
    fun cached(): AdvisorResult.Available?

    /** Drop any locally cached recommendation/analytics (privacy control). */
    fun clearCache()
}
