package radio.ks3ckc.ft8af.bandadvisor

import radio.ks3ckc.ft8af.bandadvisor.model.BandAlternative
import radio.ks3ckc.ft8af.bandadvisor.model.BandRecommendation
import radio.ks3ckc.ft8af.bandadvisor.model.Evidence
import radio.ks3ckc.ft8af.bandadvisor.model.EvidenceType
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal
import radio.ks3ckc.ft8af.bandadvisor.model.PersonalAnalyticsSummary
import radio.ks3ckc.ft8af.bandadvisor.model.SourceAvailability
import radio.ks3ckc.ft8af.bandadvisor.model.freshnessOf
import radio.ks3ckc.ft8af.bandadvisor.model.isPlausibleCallsign
import radio.ks3ckc.ft8af.bandadvisor.model.normalizeAdvisorGrid
import radio.ks3ckc.ft8af.bandadvisor.scoring.BandConditions
import radio.ks3ckc.ft8af.bandadvisor.scoring.ConfidenceInputs
import radio.ks3ckc.ft8af.bandadvisor.scoring.confidenceFor
import radio.ks3ckc.ft8af.bandadvisor.scoring.rankBands
import java.util.Calendar
import java.util.TimeZone

/**
 * Deterministic fixture-backed repository for offline UI development, tests,
 * and the debug "use fixture data" toggle. Conditions vary only with the UTC
 * hour (so the demo feels alive across a day) and the requested goal — same
 * inputs, same output, always.
 *
 * NEVER wired up in release builds: [BandAdvisor.repository] only selects this
 * class when BuildConfig.DEBUG is true and the developer toggle is on, so
 * fabricated recommendations cannot ship as live data.
 */
class FixtureBandAdvisorRepository(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BandAdvisorRepository {

    override suspend fun recommendation(request: AdvisorRequest, force: Boolean): AdvisorResult {
        val grid = normalizeAdvisorGrid(request.grid)
            ?: return AdvisorResult.Unavailable(UnavailableReason.NO_GRID)
        val rec = buildFixtureRecommendation(grid, request, clock())
        return AdvisorResult.Available(rec, freshnessOf(rec, clock()), fromCache = false)
    }

    override fun cached(): AdvisorResult.Available? = null

    override fun clearCache() = Unit
}

/**
 * Build the fixture recommendation through the real scoring engine, so the
 * fixture path exercises exactly the code the tests pin down.
 */
internal fun buildFixtureRecommendation(
    grid: String,
    request: AdvisorRequest,
    nowMs: Long,
): BandRecommendation {
    val utcHour = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        .apply { timeInMillis = nowMs }
        .get(Calendar.HOUR_OF_DAY)
    val daytime = utcHour in 13..22 // Rough mid-US afternoon in UTC.
    val personal = isPlausibleCallsign(request.callsign)

    // Handcrafted-but-plausible conditions: high bands live by day, low by night.
    fun cond(
        band: String,
        freqHz: Long,
        rel: Double,
        nearby: Double,
        activity: Double,
        distance: Double,
        trend: Double,
        diversity: Double,
        obs: Int,
        destinations: List<String>,
    ) = BandConditions(
        band = band,
        frequencyHz = freqHz,
        voacapReliability = rel,
        nearbyObservedSuccess = nearby,
        normalizedLiveActivity = activity,
        distancePotential = distance,
        activityTrend = trend,
        personalPerformance = if (personal) 0.68 else null,
        directionalDiversity = diversity,
        targetReliability = rel * 0.9,
        targetObservedPaths = nearby * 0.8,
        observationCount = obs,
        destinations = destinations,
    )

    val bands = if (daytime) {
        listOf(
            cond("40m", 7_074_000, 0.55, 0.50, 0.55, 0.35, 0.45, 0.40, 42, listOf("North America")),
            cond("20m", 14_074_000, 0.86, 0.82, 0.74, 0.70, 0.60, 0.80, 63, listOf("Europe", "South America")),
            cond("15m", 21_074_000, 0.72, 0.61, 0.58, 0.85, 0.68, 0.65, 24, listOf("South America", "Africa")),
            cond("10m", 28_074_000, 0.44, 0.30, 0.35, 0.90, 0.72, 0.35, 8, listOf("South America")),
        )
    } else {
        listOf(
            cond("80m", 3_573_000, 0.68, 0.60, 0.62, 0.25, 0.50, 0.35, 30, listOf("North America")),
            cond("40m", 7_074_000, 0.84, 0.78, 0.72, 0.55, 0.58, 0.70, 55, listOf("Europe", "North America")),
            cond("30m", 10_136_000, 0.74, 0.62, 0.55, 0.60, 0.52, 0.60, 26, listOf("Europe", "Asia")),
            cond("20m", 14_074_000, 0.52, 0.40, 0.38, 0.65, 0.35, 0.45, 12, listOf("Oceania")),
        )
    }

    val ranked = rankBands(bands, request.goal)
    val winner = ranked.first()
    val alternatives = ranked.drop(1).take(2).map { alt ->
        BandAlternative(
            band = alt.conditions.band,
            frequencyHz = alt.conditions.frequencyHz,
            score = alt.score,
            reason = alternativeReason(winner, alt),
        )
    }
    val confidence = confidenceFor(
        ConfidenceInputs(
            observationCount = winner.conditions.observationCount,
            dataAgeMs = 3 * 60_000,
            sourceAvailability = if (personal) 1.0 else 2.0 / 3.0,
            modelAgreement = winner.conditions.voacapReliability?.let { rel ->
                winner.conditions.nearbyObservedSuccess?.let { obs -> 1.0 - kotlin.math.abs(rel - obs) }
            },
            personalSampleSize = if (personal) 23 else 0,
            stationProfileCompleteness = 0.7,
        ),
    )

    val evidence = buildList {
        add(
            Evidence(
                EvidenceType.VOACAP,
                "Predicted reliability into ${winner.conditions.destinations.joinToString(" and ")} " +
                    "is ${(100 * (winner.conditions.voacapReliability ?: 0.0)).toInt()}% this hour.",
            ),
        )
        add(
            Evidence(
                EvidenceType.REGIONAL_PSK_REPORTER,
                "Stations near $grid completed ${winner.conditions.observationCount} reception paths " +
                    "on ${winner.conditions.band} in the last 15 minutes.",
            ),
        )
        if (personal) {
            add(
                Evidence(
                    EvidenceType.PERSONAL_PSK_REPORTER,
                    "Your recent ${winner.conditions.band} reports reach farther than your usual range.",
                ),
            )
        }
    }

    return BandRecommendation(
        targetRegion = request.targetRegion,
        generatedAtMs = nowMs,
        validUntilMs = nowMs + 15 * 60_000,
        grid = grid,
        callsign = request.callsign?.trim()?.uppercase().takeIf { personal },
        mode = request.mode,
        goal = request.goal,
        recommendedBand = winner.conditions.band,
        recommendedFrequencyHz = winner.conditions.frequencyHz,
        score = winner.score,
        confidence = confidence,
        summary = summaryFor(request.goal, winner.conditions.band),
        destinations = winner.conditions.destinations,
        evidence = evidence,
        scoreComponents = winner.components,
        personalAnalytics = if (personal) {
            PersonalAnalyticsSummary(
                enabled = true,
                reportsReceived = 23,
                uniqueReceivers = 18,
                countriesReached = 7,
                maximumDistanceKm = 8420,
                medianSnrDb = -11,
                bestSnrDb = -3,
                performanceComparedToBaseline = 0.18,
            )
        } else {
            null
        },
        alternatives = alternatives,
        sources = SourceAvailability(
            voacapAvailable = true,
            regionalPskReporterAvailable = true,
            personalPskReporterAvailable = personal,
        ),
    )
}

private fun summaryFor(goal: OperatingGoal, band: String): String = when (goal) {
    OperatingGoal.MAKE_CONTACT -> "$band has the best mix of activity and reliable paths right now."
    OperatingGoal.DX -> "$band offers the strongest long-distance paths right now."
    OperatingGoal.TARGET -> "$band is your best route to the selected region right now."
    OperatingGoal.POTA -> "$band gives the most dependable coverage for an activation."
}

private fun alternativeReason(
    winner: radio.ks3ckc.ft8af.bandadvisor.scoring.ScoredBand,
    alt: radio.ks3ckc.ft8af.bandadvisor.scoring.ScoredBand,
): String {
    val w = winner.conditions
    val a = alt.conditions
    return when {
        (a.distancePotential ?: 0.0) > (w.distancePotential ?: 0.0) ->
            "Greater maximum distance, but fewer observed paths."
        (a.nearbyObservedSuccess ?: 0.0) > (w.nearbyObservedSuccess ?: 0.0) ->
            "Better for dependable regional contacts, less DX reach."
        a.observationCount < w.observationCount ->
            "Less live activity observed nearby right now."
        else ->
            "Slightly weaker predicted reliability this hour."
    }
}
