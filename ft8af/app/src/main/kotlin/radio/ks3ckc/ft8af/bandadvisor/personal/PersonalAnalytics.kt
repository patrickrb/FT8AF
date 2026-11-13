package radio.ks3ckc.ft8af.bandadvisor.personal

import radio.ks3ckc.ft8af.pskreporter.PskReporterSpot
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure computation of personal PSK Reporter analytics from reception reports
 * where the operator was the SENDER (stations that heard the user). These are
 * reception reports, not QSOs — presentation must never imply a completed
 * contact. No Android or network dependencies; fully unit-testable.
 */

/** Minimum current-sample size before any baseline comparison is shown. */
const val MIN_REPORTS_FOR_COMPARISON = 10

/** Minimum stored history sessions before a personal baseline is meaningful. */
const val MIN_BASELINE_SESSIONS = 3

/** Stats for one band (or the whole sample when band == null). */
data class PersonalBandStats(
    val band: String?,
    val reports: Int,
    val uniqueReceivers: Int,
    val uniqueGridFields: Int,
    /** Distinct countries, only when a resolver was available; 0 otherwise. */
    val countriesReached: Int,
    val maxDistanceKm: Int,
    val medianDistanceKm: Int,
    val bestSnrDb: Int?,
    val medianSnrDb: Int?,
    /** Distinct 45° azimuth octants with at least one report (0..8). */
    val directionOctants: Int,
    val mostRecentReportEpochSec: Long?,
)

data class PersonalAnalyticsReport(
    val overall: PersonalBandStats,
    val perBand: Map<String, PersonalBandStats>,
)

/**
 * Compute analytics from [spots] (each spot = one station that heard us).
 * [myLat]/[myLon] locate the operator for distance/azimuth; [bandOf] maps a
 * frequency to a band label ("20m") or null to drop the spot; [countryResolver]
 * maps a callsign to a country name or null when unknown (injected because the
 * production lookup is DB-backed).
 */
fun computePersonalAnalytics(
    spots: List<PskReporterSpot>,
    myLat: Double,
    myLon: Double,
    bandOf: (Long) -> String?,
    countryResolver: (String) -> String? = { null },
): PersonalAnalyticsReport {
    val byBand = spots.groupBy { bandOf(it.frequencyHz) }
        .filterKeys { it != null }
        .mapKeys { it.key!! }
    val perBand = byBand.mapValues { (band, bandSpots) ->
        statsFor(band, bandSpots, myLat, myLon, countryResolver)
    }
    val overall = statsFor(null, byBand.values.flatten(), myLat, myLon, countryResolver)
    return PersonalAnalyticsReport(overall, perBand)
}

private fun statsFor(
    band: String?,
    spots: List<PskReporterSpot>,
    myLat: Double,
    myLon: Double,
    countryResolver: (String) -> String?,
): PersonalBandStats {
    if (spots.isEmpty()) {
        return PersonalBandStats(band, 0, 0, 0, 0, 0, 0, null, null, 0, null)
    }
    val distances = spots.map { haversineKm(myLat, myLon, it.lat, it.lon) }
    val snrs = spots.map { it.snr }
    val octants = spots.map { azimuthOctant(myLat, myLon, it.lat, it.lon) }.toSet()
    val countries = spots.mapNotNull { countryResolver(it.callsign) }.toSet()
    return PersonalBandStats(
        band = band,
        reports = spots.size,
        uniqueReceivers = spots.map { it.callsign.uppercase() }.toSet().size,
        uniqueGridFields = spots.map { it.grid.take(4).uppercase() }.toSet().size,
        countriesReached = countries.size,
        maxDistanceKm = distances.max().toInt(),
        medianDistanceKm = medianOf(distances).toInt(),
        bestSnrDb = snrs.max(),
        medianSnrDb = medianOf(snrs.map { it.toDouble() }).toInt(),
        directionOctants = octants.size,
        mostRecentReportEpochSec = spots.maxOf { it.flowStartSeconds },
    )
}

/**
 * Signed fraction of [currentMaxKm] vs the operator's own historical median
 * max distance on this band (+0.18 = 18% farther than usual). Returns null —
 * comparison suppressed — when the current sample is below
 * [MIN_REPORTS_FOR_COMPARISON] or history has fewer than
 * [MIN_BASELINE_SESSIONS] sessions: a percentile from three spots is noise,
 * and showing it would be statistically dishonest.
 */
fun baselineComparison(
    currentReports: Int,
    currentMaxKm: Int,
    historicalMaxKm: List<Int>,
): Double? {
    if (currentReports < MIN_REPORTS_FOR_COMPARISON) return null
    if (historicalMaxKm.size < MIN_BASELINE_SESSIONS) return null
    val baseline = medianOf(historicalMaxKm.map { it.toDouble() })
    if (baseline <= 0.0) return null
    return (currentMaxKm - baseline) / baseline
}

internal fun medianOf(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private const val EARTH_RADIUS_KM = 6371.0

/** Great-circle distance in km. */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Initial bearing bucketed into 45° octants, 0=N..7=NW. */
internal fun azimuthOctant(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    var bearing = Math.toDegrees(atan2(y, x))
    if (bearing < 0) bearing += 360.0
    // Same point → bearing is 0/undefined; bucket 0 deterministically.
    if (abs(lat1 - lat2) < 1e-9 && abs(lon1 - lon2) < 1e-9) return 0
    return ((bearing + 22.5) / 45.0).toInt() % 8
}
