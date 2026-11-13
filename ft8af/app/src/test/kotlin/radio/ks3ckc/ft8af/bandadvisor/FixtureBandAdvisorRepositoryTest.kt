package radio.ks3ckc.ft8af.bandadvisor

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal

class FixtureBandAdvisorRepositoryTest {
    // 2026-09-27T18:00:00Z — "daytime" branch of the fixture.
    private val dayMs = 1_790_532_000_000L

    // 2026-09-27T04:00:00Z — "night" branch.
    private val nightMs = 1_790_481_600_000L

    private val request = AdvisorRequest(grid = "EM28", callsign = "K1AF")

    @Test
    fun `same inputs produce identical output`() = runBlocking {
        val repo = FixtureBandAdvisorRepository(clock = { dayMs })
        val a = repo.recommendation(request) as AdvisorResult.Available
        val b = repo.recommendation(request) as AdvisorResult.Available
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `recommendation is fresh, complete and passes through the real scorer`() = runBlocking {
        val result = FixtureBandAdvisorRepository(clock = { dayMs })
            .recommendation(request) as AdvisorResult.Available
        val rec = result.recommendation
        assertThat(result.freshness).isEqualTo(Freshness.FRESH)
        assertThat(rec.recommendedBand).isEqualTo("20m") // Daytime winner.
        assertThat(rec.recommendedFrequencyHz).isEqualTo(14_074_000L)
        assertThat(rec.alternatives).hasSize(2)
        assertThat(rec.alternatives.map { it.reason }).doesNotContain("")
        assertThat(rec.scoreComponents).isNotEmpty()
        assertThat(rec.scoreComponents.sumOf { it.contribution }).isWithin(1e-9).of(rec.score)
        assertThat(rec.confidence).isIn(com.google.common.collect.Range.closed(0.0, 1.0))
        assertThat(rec.personalAnalytics!!.enabled).isTrue()
        assertThat(rec.evidence.map { it.type.name }).contains("PERSONAL_PSK_REPORTER")
    }

    @Test
    fun `night hours recommend a low band`() = runBlocking {
        val rec = (
            FixtureBandAdvisorRepository(clock = { nightMs })
                .recommendation(request) as AdvisorResult.Available
            ).recommendation
        assertThat(rec.recommendedBand).isEqualTo("40m")
    }

    @Test
    fun `no callsign disables the personal layer`() = runBlocking {
        val rec = (
            FixtureBandAdvisorRepository(clock = { dayMs })
                .recommendation(request.copy(callsign = null)) as AdvisorResult.Available
            ).recommendation
        assertThat(rec.personalAnalytics).isNull()
        assertThat(rec.callsign).isNull()
        assertThat(rec.sources.personalPskReporterAvailable).isFalse()
        assertThat(rec.sources.isPartial).isTrue()
        assertThat(rec.evidence.map { it.type.name }).doesNotContain("PERSONAL_PSK_REPORTER")
        // Personal component is dropped and remaining weights renormalized.
        assertThat(rec.scoreComponents.map { it.name }).doesNotContain("personalStationPerformance")
        assertThat(rec.scoreComponents.sumOf { it.weight }).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `goal changes the ranking`() = runBlocking {
        val repo = FixtureBandAdvisorRepository(clock = { dayMs })
        val contact = (repo.recommendation(request) as AdvisorResult.Available).recommendation
        val dx = (
            repo.recommendation(request.copy(goal = OperatingGoal.DX)) as AdvisorResult.Available
            ).recommendation
        assertThat(contact.goal).isEqualTo(OperatingGoal.MAKE_CONTACT)
        assertThat(dx.goal).isEqualTo(OperatingGoal.DX)
        // DX weighting must raise the score of the distance-heavy 15m band
        // relative to the contact weighting (visible via alternatives order/score).
        val contact15 = contact.alternatives.firstOrNull { it.band == "15m" }?.score
            ?: if (contact.recommendedBand == "15m") contact.score else null
        val dx15 = dx.alternatives.firstOrNull { it.band == "15m" }?.score
            ?: if (dx.recommendedBand == "15m") dx.score else null
        assertThat(dx15).isNotNull()
        assertThat(contact15).isNotNull()
        assertThat(dx15!!).isGreaterThan(contact15!!)
    }

    @Test
    fun `invalid grid is rejected`() = runBlocking {
        val result = FixtureBandAdvisorRepository(clock = { dayMs })
            .recommendation(request.copy(grid = "bogus"))
        assertThat((result as AdvisorResult.Unavailable).reason)
            .isEqualTo(UnavailableReason.NO_GRID)
    }
}
