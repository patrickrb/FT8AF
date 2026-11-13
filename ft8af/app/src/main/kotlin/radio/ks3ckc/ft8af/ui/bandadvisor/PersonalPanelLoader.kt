package radio.ks3ckc.ft8af.ui.bandadvisor

import android.content.Context
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import com.k1af.ft8af.rigs.BaseRigOperation
import radio.ks3ckc.ft8af.bandadvisor.model.isPlausibleCallsign
import radio.ks3ckc.ft8af.bandadvisor.model.normalizeAdvisorGrid
import radio.ks3ckc.ft8af.bandadvisor.personal.MIN_REPORTS_FOR_COMPARISON
import radio.ks3ckc.ft8af.bandadvisor.personal.PersonalAnalyticsReport
import radio.ks3ckc.ft8af.bandadvisor.personal.PersonalAnalyticsStore
import radio.ks3ckc.ft8af.bandadvisor.personal.baselineComparison
import radio.ks3ckc.ft8af.bandadvisor.personal.computePersonalAnalytics
import radio.ks3ckc.ft8af.pskreporter.PskReporterClient

/** How far back the personal query looks. PSK Reporter reports arrive with a
 * few minutes of delay, so a short window would miss recent transmissions. */
internal const val PERSONAL_LOOKBACK_SECONDS = 30 * 60

/** Map panel state from a locally computed report + baseline history. Pure. */
internal fun personalPanelFromReport(
    report: PersonalAnalyticsReport,
    historyForBand: (String) -> List<Int>,
): PersonalPanelState {
    val overall = report.overall
    if (overall.reports == 0) return PersonalPanelState.Empty
    // Baseline compares the busiest band of this sample against its own history.
    val bestBand = report.perBand.maxByOrNull { it.value.reports }
    val vsBaseline = bestBand?.let { (band, stats) ->
        baselineComparison(stats.reports, stats.maxDistanceKm, historyForBand(band))
    }
    return PersonalPanelState.Ready(
        reports = overall.reports,
        uniqueReceivers = overall.uniqueReceivers,
        uniqueGridFields = overall.uniqueGridFields,
        countriesReached = overall.countriesReached,
        maxDistanceKm = overall.maxDistanceKm,
        medianDistanceKm = overall.medianDistanceKm,
        bestSnrDb = overall.bestSnrDb,
        medianSnrDb = overall.medianSnrDb,
        directionOctants = overall.directionOctants,
        mostRecentReportEpochSec = overall.mostRecentReportEpochSec,
        vsBaseline = vsBaseline,
    )
}

/**
 * Fetch reports where the operator was the sender ("who heard me") and reduce
 * them to the personal panel. Uses the existing [PskReporterClient] — which
 * enforces PSK Reporter's 5-minute etiquette and 429 back-off — so opening
 * the sheet repeatedly can never hammer the service; a cooldown-suppressed
 * fetch keeps the previous panel via the null return.
 *
 * Returns null when nothing new could be fetched (caller keeps prior state),
 * or a concrete state otherwise.
 */
internal suspend fun loadPersonalPanel(
    context: Context,
    callsign: String?,
    grid: String?,
): PersonalPanelState? {
    if (!isPlausibleCallsign(callsign)) return PersonalPanelState.Error
    val myGrid = normalizeAdvisorGrid(grid) ?: return PersonalPanelState.Error
    val myLocation = try {
        MaidenheadGrid.gridToLatLng(myGrid)
    } catch (_: Exception) {
        null
    } ?: return PersonalPanelState.Error

    val spots = PskReporterClient.fetchSpotsForMe(
        call = callsign!!.trim().uppercase(),
        secondsBack = PERSONAL_LOOKBACK_SECONDS,
        byReceiver = false, // Reports where WE transmitted: who heard me.
    ) ?: return null // Cooldown or transport failure — keep whatever is shown.

    val report = computePersonalAnalytics(
        spots = spots,
        myLat = myLocation.latitude,
        myLon = myLocation.longitude,
        bandOf = { freq -> BaseRigOperation.getMeterFromFreq(freq)?.takeIf { it.isNotBlank() } },
    )
    // Compare against history BEFORE recording this sample — otherwise the
    // current session would be part of its own baseline.
    val panel = personalPanelFromReport(report) { band ->
        PersonalAnalyticsStore.historyFor(context, band)
    }
    // Feed the local baseline with sufficiently large samples only, so a
    // single stray spot can't drag the operator's norm.
    for ((band, stats) in report.perBand) {
        if (stats.reports >= MIN_REPORTS_FOR_COMPARISON) {
            PersonalAnalyticsStore.record(context, band, stats.maxDistanceKm)
        }
    }
    return panel
}
