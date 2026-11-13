package radio.ks3ckc.ft8af.ui.bandadvisor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.bandadvisor.personal.MIN_REPORTS_FOR_COMPARISON
import radio.ks3ckc.ft8af.bandadvisor.personal.PersonalAnalyticsReport
import radio.ks3ckc.ft8af.bandadvisor.personal.PersonalBandStats

class PersonalPanelLoaderTest {

    private fun stats(
        band: String?,
        reports: Int,
        maxKm: Int = 5000,
    ) = PersonalBandStats(
        band = band,
        reports = reports,
        uniqueReceivers = reports,
        uniqueGridFields = reports,
        countriesReached = 2,
        maxDistanceKm = maxKm,
        medianDistanceKm = maxKm / 2,
        bestSnrDb = -5,
        medianSnrDb = -12,
        directionOctants = 3,
        mostRecentReportEpochSec = 1_000L,
    )

    @Test
    fun `empty report maps to Empty`() {
        val report = PersonalAnalyticsReport(stats(null, 0), emptyMap())
        assertThat(personalPanelFromReport(report) { emptyList() })
            .isEqualTo(PersonalPanelState.Empty)
    }

    @Test
    fun `busiest band drives the baseline comparison`() {
        val report = PersonalAnalyticsReport(
            overall = stats(null, 25),
            perBand = mapOf(
                "40m" to stats("40m", 5, maxKm = 2_000),
                "20m" to stats("20m", 20, maxKm = 6_000),
            ),
        )
        var asked: String? = null
        val panel = personalPanelFromReport(report) { band ->
            asked = band
            listOf(4_000, 5_000, 6_000) // median 5000 -> +20%
        } as PersonalPanelState.Ready
        assertThat(asked).isEqualTo("20m")
        assertThat(panel.vsBaseline!!).isWithin(1e-9).of(0.2)
        assertThat(panel.reports).isEqualTo(25)
    }

    @Test
    fun `small sample suppresses the comparison but keeps the stats`() {
        val report = PersonalAnalyticsReport(
            overall = stats(null, MIN_REPORTS_FOR_COMPARISON - 1),
            perBand = mapOf("20m" to stats("20m", MIN_REPORTS_FOR_COMPARISON - 1)),
        )
        val panel = personalPanelFromReport(report) {
            listOf(4_000, 5_000, 6_000)
        } as PersonalPanelState.Ready
        assertThat(panel.vsBaseline).isNull()
        assertThat(panel.reports).isEqualTo(MIN_REPORTS_FOR_COMPARISON - 1)
    }

    @Test
    fun `insufficient history suppresses the comparison`() {
        val report = PersonalAnalyticsReport(
            overall = stats(null, 25),
            perBand = mapOf("20m" to stats("20m", 25)),
        )
        val panel = personalPanelFromReport(report) { listOf(5_000) } as PersonalPanelState.Ready
        assertThat(panel.vsBaseline).isNull()
    }
}
