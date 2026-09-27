package radio.ks3ckc.ft8af.bandadvisor.scoring

import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal
import radio.ks3ckc.ft8af.bandadvisor.model.ScoreComponent

/**
 * Deterministic, explainable band scoring — the client-side reference
 * implementation of the model the band-advisor service runs. Production
 * recommendations are scored server-side; this Kotlin copy drives the fixture
 * repository (offline UI development) and pins the model's behavior with unit
 * tests. Any change here must be mirrored in server/band-advisor/app/scoring.py.
 *
 * There is NO machine learning or generative model anywhere in this path:
 * a recommendation is a weighted sum of named 0..1 inputs, and the weights
 * and per-component contributions are returned so the UI can show exactly
 * why a band won.
 */

/**
 * Normalized (0..1) condition inputs for one band. Null = that signal is
 * unavailable and its weight is redistributed across the rest.
 *
 * Conventions: [normalizedLiveActivity] is activity relative to that band's
 * typical level for this UTC hour (0.5 = typical) — never raw spot volume,
 * so a perpetually busy band can't win on volume alone. [activityTrend] and
 * [personalPerformance] are centered at 0.5 (= flat / at own baseline).
 */
data class BandConditions(
    val band: String,
    val frequencyHz: Long,
    val voacapReliability: Double? = null,
    val nearbyObservedSuccess: Double? = null,
    val normalizedLiveActivity: Double? = null,
    val distancePotential: Double? = null,
    val activityTrend: Double? = null,
    val personalPerformance: Double? = null,
    val directionalDiversity: Double? = null,
    val targetReliability: Double? = null,
    val targetObservedPaths: Double? = null,
    val observationCount: Int = 0,
    val destinations: List<String> = emptyList(),
)

/** A band with its final score and the components that produced it. */
data class ScoredBand(
    val conditions: BandConditions,
    val score: Double,
    val components: List<ScoreComponent>,
)

/** Wire names shared with the server contract's scoreComponents entries. */
internal object ComponentNames {
    const val VOACAP_RELIABILITY = "voacapReliability"
    const val NEARBY_OBSERVED_SUCCESS = "nearbyObservedSuccess"
    const val NORMALIZED_LIVE_ACTIVITY = "normalizedLiveActivity"
    const val DISTANCE_POTENTIAL = "distancePotential"
    const val ACTIVITY_TREND = "activityTrend"
    const val PERSONAL_PERFORMANCE = "personalStationPerformance"
    const val DIRECTIONAL_DIVERSITY = "directionalDiversity"
    const val TARGET_RELIABILITY = "targetReliability"
    const val TARGET_OBSERVED_PATHS = "targetObservedPaths"
}

/** Base weights per goal. Each map sums to 1.0 when every input is present. */
internal fun weightsFor(goal: OperatingGoal): Map<String, Double> = when (goal) {
    OperatingGoal.MAKE_CONTACT -> mapOf(
        ComponentNames.VOACAP_RELIABILITY to 0.30,
        ComponentNames.NEARBY_OBSERVED_SUCCESS to 0.25,
        ComponentNames.NORMALIZED_LIVE_ACTIVITY to 0.15,
        ComponentNames.DISTANCE_POTENTIAL to 0.10,
        ComponentNames.ACTIVITY_TREND to 0.10,
        ComponentNames.PERSONAL_PERFORMANCE to 0.10,
    )
    OperatingGoal.DX -> mapOf(
        ComponentNames.DISTANCE_POTENTIAL to 0.25,
        ComponentNames.VOACAP_RELIABILITY to 0.25,
        ComponentNames.DIRECTIONAL_DIVERSITY to 0.15,
        ComponentNames.NEARBY_OBSERVED_SUCCESS to 0.15,
        ComponentNames.NORMALIZED_LIVE_ACTIVITY to 0.10,
        ComponentNames.PERSONAL_PERFORMANCE to 0.10,
    )
    OperatingGoal.TARGET -> mapOf(
        ComponentNames.TARGET_RELIABILITY to 0.40,
        ComponentNames.TARGET_OBSERVED_PATHS to 0.30,
        ComponentNames.NORMALIZED_LIVE_ACTIVITY to 0.10,
        ComponentNames.VOACAP_RELIABILITY to 0.10,
        ComponentNames.PERSONAL_PERFORMANCE to 0.10,
    )
    OperatingGoal.POTA -> mapOf(
        ComponentNames.VOACAP_RELIABILITY to 0.35,
        ComponentNames.NEARBY_OBSERVED_SUCCESS to 0.25,
        ComponentNames.NORMALIZED_LIVE_ACTIVITY to 0.20,
        ComponentNames.ACTIVITY_TREND to 0.10,
        ComponentNames.PERSONAL_PERFORMANCE to 0.10,
    )
}

private fun valueFor(conditions: BandConditions, componentName: String): Double? =
    when (componentName) {
        ComponentNames.VOACAP_RELIABILITY -> conditions.voacapReliability
        ComponentNames.NEARBY_OBSERVED_SUCCESS -> conditions.nearbyObservedSuccess
        ComponentNames.NORMALIZED_LIVE_ACTIVITY -> conditions.normalizedLiveActivity
        ComponentNames.DISTANCE_POTENTIAL -> conditions.distancePotential
        ComponentNames.ACTIVITY_TREND -> conditions.activityTrend
        ComponentNames.PERSONAL_PERFORMANCE -> conditions.personalPerformance
        ComponentNames.DIRECTIONAL_DIVERSITY -> conditions.directionalDiversity
        ComponentNames.TARGET_RELIABILITY -> conditions.targetReliability
        ComponentNames.TARGET_OBSERVED_PATHS -> conditions.targetObservedPaths
        else -> null
    }

/**
 * Score one band for a goal. Missing (null) inputs are dropped and the
 * remaining weights renormalized to sum 1, so a band with only VOACAP data
 * competes on the same 0..1 scale as a fully-observed one — confidence, not
 * score, is where thin data shows up. Returns score 0 with no components when
 * every input is missing.
 */
fun scoreBand(conditions: BandConditions, goal: OperatingGoal): ScoredBand {
    val weights = weightsFor(goal)
    val present = weights.mapNotNull { (name, weight) ->
        valueFor(conditions, name)?.let { value -> Triple(name, weight, value.coerceIn(0.0, 1.0)) }
    }
    val totalWeight = present.sumOf { it.second }
    if (totalWeight <= 0.0) {
        return ScoredBand(conditions, 0.0, emptyList())
    }
    val components = present.map { (name, weight, value) ->
        val normalizedWeight = weight / totalWeight
        ScoreComponent(
            name = name,
            weight = normalizedWeight,
            value = value,
            contribution = normalizedWeight * value,
        )
    }
    return ScoredBand(conditions, components.sumOf { it.contribution }, components)
}

/** Score all bands and rank best-first (deterministic tie-break by frequency). */
fun rankBands(bands: List<BandConditions>, goal: OperatingGoal): List<ScoredBand> =
    bands.map { scoreBand(it, goal) }
        .sortedWith(compareByDescending<ScoredBand> { it.score }.thenBy { it.conditions.frequencyHz })

// ---------------------------------------------------------------------------
// Confidence — deliberately separate from score. Score says "how good is this
// band"; confidence says "how much should you trust that claim".
// ---------------------------------------------------------------------------

data class ConfidenceInputs(
    /** Live observations backing the winning band. */
    val observationCount: Int,
    /** Age of the newest source data, ms. */
    val dataAgeMs: Long,
    /** Fraction of the three sources that responded (0..1). */
    val sourceAvailability: Double,
    /** Both present → 1 - |voacap - observed|; null when either is missing. */
    val modelAgreement: Double?,
    /** Personal reports in the sample (0 when the feature is off). */
    val personalSampleSize: Int,
    /** 0..1: has the user set power/antenna/grid precision etc. */
    val stationProfileCompleteness: Double,
)

/** Observation counts at/above this no longer increase confidence. */
internal const val OBSERVATIONS_SATURATION = 50

/** Data older than this contributes zero freshness confidence. */
internal const val DATA_AGE_ZERO_MS = 60L * 60 * 1000

/**
 * Deterministic confidence in 0..1. A weighted mean of saturating factors:
 * observation volume (0.30), freshness (0.25), source availability (0.20),
 * VOACAP-vs-observed agreement (0.15, neutral 0.5 when unmeasurable), and
 * station-profile completeness (0.10). Personal sample size adds up to a
 * +0.05 bonus rather than a core factor, since most users start without one.
 */
fun confidenceFor(inputs: ConfidenceInputs): Double {
    val observations =
        (inputs.observationCount.coerceAtLeast(0).toDouble() / OBSERVATIONS_SATURATION)
            .coerceAtMost(1.0)
    val freshness =
        (1.0 - inputs.dataAgeMs.coerceAtLeast(0).toDouble() / DATA_AGE_ZERO_MS).coerceIn(0.0, 1.0)
    val agreement = inputs.modelAgreement?.coerceIn(0.0, 1.0) ?: 0.5
    val core = 0.30 * observations +
        0.25 * freshness +
        0.20 * inputs.sourceAvailability.coerceIn(0.0, 1.0) +
        0.15 * agreement +
        0.10 * inputs.stationProfileCompleteness.coerceIn(0.0, 1.0)
    val personalBonus = 0.05 * (inputs.personalSampleSize.coerceAtLeast(0).toDouble() / 20.0)
        .coerceAtMost(1.0)
    return (core + personalBonus).coerceIn(0.0, 1.0)
}
