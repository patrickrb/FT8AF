package radio.ks3ckc.ft8af.ui.bandadvisor

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.FT8Common
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import org.junit.Test
import radio.ks3ckc.ft8af.bandadvisor.AdvisorRequest
import radio.ks3ckc.ft8af.bandadvisor.AdvisorResult
import radio.ks3ckc.ft8af.bandadvisor.UnavailableReason
import radio.ks3ckc.ft8af.bandadvisor.buildFixtureRecommendation
import radio.ks3ckc.ft8af.bandadvisor.model.AdvisorTargetRegion
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal
import radio.ks3ckc.ft8af.bandadvisor.model.PersonalAnalyticsSummary

class BandAdvisorStateTest {

    // ---------------- uiStateOf ----------------

    @Test
    fun `available result maps to Ready`() {
        val rec = buildFixtureRecommendation(
            "EM28",
            AdvisorRequest(grid = "EM28", callsign = "K1AF"),
            nowMs = 1_790_532_000_000L,
        )
        val state = uiStateOf(AdvisorResult.Available(rec, Freshness.STALE, fromCache = true))
        assertThat(state).isInstanceOf(BandAdvisorUiState.Ready::class.java)
        val ready = state as BandAdvisorUiState.Ready
        assertThat(ready.freshness).isEqualTo(Freshness.STALE)
        assertThat(ready.fromCache).isTrue()
    }

    @Test
    fun `NO_GRID maps to its own state, others to Unavailable`() {
        assertThat(uiStateOf(AdvisorResult.Unavailable(UnavailableReason.NO_GRID)))
            .isEqualTo(BandAdvisorUiState.NoGrid)
        val offline = uiStateOf(AdvisorResult.Unavailable(UnavailableReason.OFFLINE))
        assertThat((offline as BandAdvisorUiState.Unavailable).reason)
            .isEqualTo(UnavailableReason.OFFLINE)
    }

    // ---------------- advisorRequestFrom ----------------

    @Test
    fun `callsign rides along only when flag on and plausible`() {
        val withPersonal = advisorRequestFrom("EM28", "k1af", true, OperatingGoal.DX, "FT8")
        assertThat(withPersonal.callsign).isEqualTo("k1af")

        val flagOff = advisorRequestFrom("EM28", "k1af", false, OperatingGoal.DX, "FT8")
        assertThat(flagOff.callsign).isNull()

        val garbage = advisorRequestFrom("EM28", "NOCALL", true, OperatingGoal.DX, "FT8")
        assertThat(garbage.callsign).isNull()
    }

    // ---------------- advisorTuneIndexFor ----------------

    private fun band(freq: Long, wave: String, mode: Int = FT8Common.FT8_MODE) =
        OperationBand.Band(freq, wave).also { it.mode = mode }

    @Test
    fun `finds the exact dial for the active mode`() {
        val bands = listOf(
            band(7_074_000, "40m"),
            band(14_074_000, "20m"),
            band(14_080_000, "20m", mode = 1), // FT4 dial on the same wavelength
        )
        assertThat(advisorTuneIndexFor(bands, 14_074_000, FT8Common.FT8_MODE)).isEqualTo(1)
        assertThat(advisorTuneIndexFor(bands, 14_080_000, 1)).isEqualTo(2)
    }

    @Test
    fun `unknown frequency or wrong mode returns null - never invents dials`() {
        val bands = listOf(band(14_074_000, "20m"))
        assertThat(advisorTuneIndexFor(bands, 14_075_000, FT8Common.FT8_MODE)).isNull()
        assertThat(advisorTuneIndexFor(bands, 14_074_000, 1)).isNull()
        assertThat(advisorTuneIndexFor(emptyList(), 14_074_000, FT8Common.FT8_MODE)).isNull()
    }

    // ---------------- labels / formatting ----------------

    @Test
    fun `rating buckets`() {
        assertThat(ratingLabelRes(0.9)).isEqualTo(R.string.band_advisor_rating_excellent)
        assertThat(ratingLabelRes(0.75)).isEqualTo(R.string.band_advisor_rating_excellent)
        assertThat(ratingLabelRes(0.6)).isEqualTo(R.string.band_advisor_rating_good)
        assertThat(ratingLabelRes(0.4)).isEqualTo(R.string.band_advisor_rating_fair)
        assertThat(ratingLabelRes(0.1)).isEqualTo(R.string.band_advisor_rating_poor)
    }

    @Test
    fun `age minutes clamps negative clock skew`() {
        assertThat(ageMinutes(1_000_000, 1_000_000 + 3 * 60_000)).isEqualTo(3)
        assertThat(ageMinutes(1_000_000, 1_000_000)).isEqualTo(0)
        assertThat(ageMinutes(2_000_000, 1_000_000)).isEqualTo(0)
    }

    @Test
    fun `goal labels cover every goal`() {
        val labels = OperatingGoal.entries.map { goalLabelRes(it) }
        assertThat(labels.toSet()).hasSize(OperatingGoal.entries.size)
    }

    // ---------------- personalPanelFromSummary ----------------

    @Test
    fun `server personal summary maps to panel`() {
        val panel = personalPanelFromSummary(
            PersonalAnalyticsSummary(
                enabled = true,
                reportsReceived = 23,
                uniqueReceivers = 18,
                countriesReached = 7,
                maximumDistanceKm = 8420,
                medianSnrDb = -11,
                bestSnrDb = -3,
                performanceComparedToBaseline = 0.18,
            ),
        )
        val ready = panel as PersonalPanelState.Ready
        assertThat(ready.reports).isEqualTo(23)
        assertThat(ready.vsBaseline).isWithin(1e-9).of(0.18)
    }

    @Test
    fun `disabled or absent summary hides the panel, zero reports is Empty`() {
        assertThat(personalPanelFromSummary(null)).isNull()
        assertThat(personalPanelFromSummary(PersonalAnalyticsSummary(enabled = false)))
            .isNull()
        assertThat(
            personalPanelFromSummary(PersonalAnalyticsSummary(enabled = true, reportsReceived = 0)),
        ).isEqualTo(PersonalPanelState.Empty)
    }

    @Test
    fun `target requests include selected region and other goals omit it`() {
        for (region in AdvisorTargetRegion.entries) {
            val request = advisorRequestFrom("EM28", null, false, OperatingGoal.TARGET, "FT8", region)
            assertThat(request.targetRegion).isEqualTo(region.name)
        }
        assertThat(advisorRequestFrom("EM28", null, false, OperatingGoal.TARGET, "FT8").targetRegion)
            .isEqualTo("EUROPE")
        assertThat(advisorRequestFrom("EM28", null, false, OperatingGoal.DX, "FT8").targetRegion)
            .isNull()
        assertThat(AdvisorTargetRegion.entries.map { targetRegionLabelRes(it) }.toSet())
            .hasSize(AdvisorTargetRegion.entries.size)
    }
}
