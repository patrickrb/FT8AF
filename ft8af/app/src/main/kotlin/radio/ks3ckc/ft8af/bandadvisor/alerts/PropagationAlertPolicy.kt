package radio.ks3ckc.ft8af.bandadvisor.alerts

/**
 * Pure decision logic for propagation alerts: which observed band conditions
 * deserve a notification, given the user's preferences and the delivery
 * history. No Android dependencies — fully unit-tested.
 *
 * The bar is deliberately high: an alert must mean something. Openings need
 * real evidence (observation count, above-baseline activity, confidence,
 * freshness) and are deduplicated + cooled down aggressively.
 */

/** One band's current conditions from the /v1/conditions endpoint. */
data class BandConditionSnapshot(
    val band: String,
    val score: Double,
    val confidence: Double,
    val observationCount: Int,
    /** Live activity relative to this band-hour's baseline (1.0 = typical). */
    val activityRatio: Double,
    /** Regions currently reachable from near the operator's grid. */
    val activeRegions: List<String>,
    /** VOACAP agrees with the observed opening. */
    val voacapSupported: Boolean,
    /** Observed conditions substantially exceed the VOACAP baseline. */
    val unusualOpening: Boolean,
    /** Personal performance above own baseline (null = unknown/off). */
    val personalImprovement: Boolean?,
    /** Age of the underlying observations, ms. */
    val dataAgeMs: Long,
)

/** A decision to notify, carrying everything the notification needs. */
data class AlertDecision(
    val type: AlertType,
    val band: String,
    val region: String?,
    val identity: String,
    /** Timestamp of the conditions the alert is based on (shown to the user). */
    val conditionsAtMs: Long,
)

// Evidence thresholds (documented in docs/band-advisor.md).
internal const val MIN_OBSERVATIONS = 10
internal const val MIN_CONFIDENCE = 0.6
internal const val MIN_ACTIVITY_RATIO = 1.5
internal const val MAX_DATA_AGE_MS = 20L * 60 * 1000

/** One opening = one alert: identity buckets openings into 90-min windows. */
internal const val OPENING_BUCKET_MS = 90L * 60 * 1000

/** Per-identity re-alert suppression window (also the history retention). */
internal const val DEDUP_RETENTION_MS = 48L * 60 * 60 * 1000

/** Minimum gap between any two alerts for the same band. */
internal const val PER_BAND_COOLDOWN_MS = 3L * 60 * 60 * 1000

/** Global minimum gap between any two alert notifications. */
internal const val GLOBAL_COOLDOWN_MS = 30L * 60 * 1000

/**
 * Stable identity for one opening: grid + band + target region + the 90-min
 * bucket the opening started in. Persisted after delivery so the same opening
 * never notifies twice, even across process restarts.
 */
fun alertIdentity(grid: String, band: String, region: String?, nowMs: Long): String {
    val bucket = nowMs / OPENING_BUCKET_MS
    return "$grid|$band|${region ?: "-"}|$bucket"
}

/**
 * True while the local hour is inside the quiet window. A window wraps
 * midnight when start > end (22 → 7); start == end disables quiet hours.
 */
fun isInQuietHours(startHour: Int, endHour: Int, localHour: Int): Boolean {
    if (startHour == endHour) return false
    return if (startHour < endHour) {
        localHour in startHour until endHour
    } else {
        localHour >= startHour || localHour < endHour
    }
}

/** Does this snapshot carry enough evidence to count as a real opening? */
internal fun hasOpeningEvidence(c: BandConditionSnapshot): Boolean =
    c.observationCount >= MIN_OBSERVATIONS &&
        c.confidence >= MIN_CONFIDENCE &&
        c.dataAgeMs in 0..MAX_DATA_AGE_MS &&
        c.activityRatio >= MIN_ACTIVITY_RATIO &&
        // Either the model agrees, or it's explicitly flagged as an unusual
        // observed opening (which the notification labels as such).
        (c.voacapSupported || c.unusualOpening)

/**
 * Evaluate all band conditions against preferences and history. Returns the
 * alerts to deliver now, at most one per band, already deduplicated and
 * cooldown-filtered; [deliveredAt] maps identity → delivery epoch ms.
 */
fun evaluateAlerts(
    grid: String,
    conditions: List<BandConditionSnapshot>,
    prefs: AlertPrefs,
    deliveredAt: Map<String, Long>,
    nowMs: Long,
    localHour: Int,
): List<AlertDecision> {
    if (!prefs.enabled || prefs.enabledTypes.isEmpty()) return emptyList()
    if (isInQuietHours(prefs.quietStartHour, prefs.quietEndHour, localHour)) return emptyList()

    val lastGlobal = deliveredAt.values.maxOrNull() ?: 0L
    if (lastGlobal != 0L && nowMs - lastGlobal < GLOBAL_COOLDOWN_MS) return emptyList()

    val decisions = mutableListOf<AlertDecision>()
    for (condition in conditions) {
        if (!hasOpeningEvidence(condition)) continue

        // Per-band cooldown, across all identities for this band.
        val bandRecent = deliveredAt.any { (identity, at) ->
            identity.split('|').getOrNull(1) == condition.band &&
                nowMs - at < PER_BAND_COOLDOWN_MS
        }
        if (bandRecent) continue

        val decision = decisionFor(grid, condition, prefs, nowMs) ?: continue
        // Dedup: the same opening (identity) never fires twice.
        if (deliveredAt.containsKey(decision.identity)) continue
        decisions.add(decision)
    }
    // One notification per check keeps the channel meaningful; the strongest
    // (highest-score) opening wins, the rest will still qualify next check if
    // they persist.
    return decisions.sortedByDescending { d ->
        conditions.first { it.band == d.band }.score
    }.take(1)
}

/** The highest-priority enabled alert type this condition satisfies. */
private fun decisionFor(
    grid: String,
    c: BandConditionSnapshot,
    prefs: AlertPrefs,
    nowMs: Long,
): AlertDecision? {
    val conditionsAt = nowMs - c.dataAgeMs

    fun decision(type: AlertType, region: String? = null) = AlertDecision(
        type = type,
        band = c.band,
        region = region,
        identity = alertIdentity(grid, c.band, region, nowMs),
        conditionsAtMs = conditionsAt,
    )

    // Region reachability is most specific, then watched-band, then the rest.
    if (AlertType.REGION_REACHABLE in prefs.enabledTypes) {
        val hit = prefs.watchedRegions.firstOrNull { it in c.activeRegions }
        if (hit != null) return decision(AlertType.REGION_REACHABLE, hit)
    }
    if (AlertType.BAND_ACTIVE in prefs.enabledTypes &&
        prefs.watchedBands.isNotEmpty() && c.band in prefs.watchedBands
    ) {
        return decision(AlertType.BAND_ACTIVE)
    }
    if (AlertType.UNUSUAL_OPENING in prefs.enabledTypes && c.unusualOpening) {
        return decision(AlertType.UNUSUAL_OPENING)
    }
    if (AlertType.PERSONAL_IMPROVEMENT in prefs.enabledTypes && c.personalImprovement == true) {
        return decision(AlertType.PERSONAL_IMPROVEMENT)
    }
    if (AlertType.BAND_OPENING in prefs.enabledTypes) {
        return decision(AlertType.BAND_OPENING, c.activeRegions.firstOrNull())
    }
    return null
}

/** Drop history entries past retention so the store stays bounded. Pure. */
fun pruneHistory(deliveredAt: Map<String, Long>, nowMs: Long): Map<String, Long> =
    deliveredAt.filterValues { nowMs - it < DEDUP_RETENTION_MS }
