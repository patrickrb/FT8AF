package radio.ks3ckc.ft8af.bandadvisor.model

/**
 * Backend-neutral domain models for a Band Advisor recommendation. Plain
 * Kotlin — no Retrofit/Gson/Moshi/UI dependencies — mirroring the versioned
 * JSON contract served by the band-advisor service (see
 * server/band-advisor/README.md and docs/band-advisor.md).
 */

/** What the operator is trying to do right now. Default is MAKE_CONTACT. */
enum class OperatingGoal(val wireName: String) {
    MAKE_CONTACT("MAKE_CONTACT"),
    DX("DX"),
    TARGET("TARGET"),
    POTA("POTA"),
    ;

    companion object {
        fun fromWireName(raw: String?): OperatingGoal =
            entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) } ?: MAKE_CONTACT
    }
}

/** Where one piece of supporting evidence came from. */
enum class EvidenceType(val wireName: String) {
    VOACAP("VOACAP"),
    REGIONAL_PSK_REPORTER("REGIONAL_PSK_REPORTER"),
    PERSONAL_PSK_REPORTER("PERSONAL_PSK_REPORTER"),
    OTHER("OTHER"),
    ;

    companion object {
        fun fromWireName(raw: String?): EvidenceType =
            entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) } ?: OTHER
    }
}

/** One human-readable reason supporting the recommendation. */
data class Evidence(
    val type: EvidenceType,
    val message: String,
)

/** One weighted component of the score, for explainability ("why 20m won"). */
data class ScoreComponent(
    val name: String,
    val weight: Double,
    val value: Double,
    val contribution: Double,
)

/**
 * Callsign-specific PSK Reporter analytics. These are RECEPTION REPORTS —
 * stations that heard the operator — never completed QSOs, and the UI must
 * present them as such. [performanceComparedToBaseline] is a signed fraction
 * vs the operator's own historical norm (+0.18 = 18% above normal), or null
 * when the sample is too small for an honest comparison.
 */
data class PersonalAnalyticsSummary(
    val enabled: Boolean,
    val reportsReceived: Int = 0,
    val uniqueReceivers: Int = 0,
    val countriesReached: Int = 0,
    val maximumDistanceKm: Int = 0,
    val medianSnrDb: Int? = null,
    val bestSnrDb: Int? = null,
    val performanceComparedToBaseline: Double? = null,
)

/** A runner-up band with the reason it ranked below the recommendation. */
data class BandAlternative(
    val band: String,
    val frequencyHz: Long,
    val score: Double,
    val reason: String,
)

/** Which upstream data sources contributed. All false = fully degraded. */
data class SourceAvailability(
    val voacapAvailable: Boolean,
    val regionalPskReporterAvailable: Boolean,
    val personalPskReporterAvailable: Boolean,
) {
    val anyAvailable: Boolean
        get() = voacapAvailable || regionalPskReporterAvailable || personalPskReporterAvailable

    /** Partial = some but not all sources contributed. */
    val isPartial: Boolean
        get() = anyAvailable &&
            !(voacapAvailable && regionalPskReporterAvailable && personalPskReporterAvailable)
}

/** A complete Band Advisor recommendation. */
data class BandRecommendation(
    val generatedAtMs: Long,
    val validUntilMs: Long,
    val grid: String,
    val callsign: String?,
    val mode: String,
    val goal: OperatingGoal,
    val recommendedBand: String,
    val recommendedFrequencyHz: Long,
    val score: Double,
    val confidence: Double,
    val summary: String,
    val destinations: List<String>,
    val evidence: List<Evidence>,
    val scoreComponents: List<ScoreComponent>,
    val personalAnalytics: PersonalAnalyticsSummary?,
    val alternatives: List<BandAlternative>,
    val sources: SourceAvailability,
)

/** How current a recommendation is relative to its own validity window. */
enum class Freshness {
    /** Inside the validUntil window. */
    FRESH,

    /** Past validUntil but recent enough to still show (marked stale). */
    STALE,

    /** Too old to be useful; show only with a strong warning or not at all. */
    EXPIRED,
}

/** How long past validUntil a recommendation may still be shown as STALE. */
const val STALE_GRACE_MS: Long = 45L * 60 * 1000

fun freshnessOf(recommendation: BandRecommendation, nowMs: Long): Freshness = when {
    nowMs < recommendation.validUntilMs -> Freshness.FRESH
    nowMs < recommendation.validUntilMs + STALE_GRACE_MS -> Freshness.STALE
    else -> Freshness.EXPIRED
}
