package radio.ks3ckc.ft8af.bandadvisor.scoring

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal

class BandScorerTest {

    private fun fullConditions(
        band: String = "20m",
        freq: Long = 14_074_000,
        rel: Double = 0.8,
        nearby: Double = 0.7,
        activity: Double = 0.6,
        distance: Double = 0.5,
        trend: Double = 0.5,
        personal: Double? = 0.6,
        diversity: Double = 0.5,
        targetRel: Double = 0.7,
        targetPaths: Double = 0.6,
    ) = BandConditions(
        band = band,
        frequencyHz = freq,
        voacapReliability = rel,
        nearbyObservedSuccess = nearby,
        normalizedLiveActivity = activity,
        distancePotential = distance,
        activityTrend = trend,
        personalPerformance = personal,
        directionalDiversity = diversity,
        targetReliability = targetRel,
        targetObservedPaths = targetPaths,
    )

    @Test
    fun `base weights sum to one for every goal`() {
        for (goal in OperatingGoal.entries) {
            assertThat(weightsFor(goal).values.sum()).isWithin(1e-9).of(1.0)
        }
    }

    @Test
    fun `full-input score matches the documented weighted sum`() {
        val scored = scoreBand(fullConditions(), OperatingGoal.MAKE_CONTACT)
        val expected = 0.30 * 0.8 + 0.25 * 0.7 + 0.15 * 0.6 + 0.10 * 0.5 + 0.10 * 0.5 + 0.10 * 0.6
        assertThat(scored.score).isWithin(1e-9).of(expected)
        assertThat(scored.components).hasSize(6)
        // Components explain the score exactly.
        assertThat(scored.components.sumOf { it.contribution }).isWithin(1e-9).of(scored.score)
        assertThat(scored.components.sumOf { it.weight }).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `missing personal component renormalizes remaining weights`() {
        val without = scoreBand(
            fullConditions(personal = null),
            OperatingGoal.MAKE_CONTACT,
        )
        // Weights renormalize to sum 1 across the five remaining components.
        assertThat(without.components.sumOf { it.weight }).isWithin(1e-9).of(1.0)
        assertThat(without.components.map { it.name })
            .doesNotContain(ComponentNames.PERSONAL_PERFORMANCE)
        val expected = (0.30 * 0.8 + 0.25 * 0.7 + 0.15 * 0.6 + 0.10 * 0.5 + 0.10 * 0.5) / 0.90
        assertThat(without.score).isWithin(1e-9).of(expected)
    }

    @Test
    fun `all inputs missing scores zero with no components`() {
        val scored = scoreBand(
            BandConditions(band = "20m", frequencyHz = 14_074_000),
            OperatingGoal.DX,
        )
        assertThat(scored.score).isEqualTo(0.0)
        assertThat(scored.components).isEmpty()
    }

    @Test
    fun `goal weighting changes the winner`() {
        // Reliable-but-short paths vs long-but-thin paths.
        val regional = fullConditions(
            band = "40m", freq = 7_074_000,
            rel = 0.9, nearby = 0.9, activity = 0.8, distance = 0.2, diversity = 0.3,
        )
        val dxBand = fullConditions(
            band = "10m", freq = 28_074_000,
            rel = 0.6, nearby = 0.4, activity = 0.4, distance = 0.95, diversity = 0.9,
        )
        val contactRanking = rankBands(listOf(regional, dxBand), OperatingGoal.MAKE_CONTACT)
        val dxRanking = rankBands(listOf(regional, dxBand), OperatingGoal.DX)
        assertThat(contactRanking.first().conditions.band).isEqualTo("40m")
        assertThat(dxRanking.first().conditions.band).isEqualTo("10m")
    }

    @Test
    fun `values are clamped to the unit interval`() {
        val scored = scoreBand(
            fullConditions(rel = 1.5, nearby = -0.3),
            OperatingGoal.MAKE_CONTACT,
        )
        val relComponent = scored.components.first { it.name == ComponentNames.VOACAP_RELIABILITY }
        val nearbyComponent = scored.components.first { it.name == ComponentNames.NEARBY_OBSERVED_SUCCESS }
        assertThat(relComponent.value).isEqualTo(1.0)
        assertThat(nearbyComponent.value).isEqualTo(0.0)
    }

    @Test
    fun `ranking is deterministic with frequency tie-break`() {
        val a = fullConditions(band = "20m", freq = 14_074_000)
        val b = fullConditions(band = "17m", freq = 18_100_000)
        val ranked = rankBands(listOf(b, a), OperatingGoal.MAKE_CONTACT)
        assertThat(ranked.map { it.conditions.band }).containsExactly("20m", "17m").inOrder()
        // Same result regardless of input order.
        assertThat(rankBands(listOf(a, b), OperatingGoal.MAKE_CONTACT).map { it.conditions.band })
            .containsExactly("20m", "17m").inOrder()
    }

    // ---------------- confidence ----------------

    @Test
    fun `confidence grows with observations and saturates`() {
        fun conf(obs: Int) = confidenceFor(
            ConfidenceInputs(
                observationCount = obs,
                dataAgeMs = 0,
                sourceAvailability = 1.0,
                modelAgreement = 1.0,
                personalSampleSize = 0,
                stationProfileCompleteness = 1.0,
            ),
        )
        assertThat(conf(0)).isLessThan(conf(25))
        assertThat(conf(25)).isLessThan(conf(50))
        assertThat(conf(50)).isEqualTo(conf(500)) // saturation
    }

    @Test
    fun `confidence decays with data age`() {
        fun conf(ageMs: Long) = confidenceFor(
            ConfidenceInputs(50, ageMs, 1.0, 1.0, 0, 1.0),
        )
        assertThat(conf(0)).isGreaterThan(conf(DATA_AGE_ZERO_MS / 2))
        assertThat(conf(DATA_AGE_ZERO_MS / 2)).isGreaterThan(conf(DATA_AGE_ZERO_MS))
        assertThat(conf(DATA_AGE_ZERO_MS)).isEqualTo(conf(DATA_AGE_ZERO_MS * 10))
    }

    @Test
    fun `disagreement between model and observations lowers confidence`() {
        fun conf(agreement: Double?) = confidenceFor(
            ConfidenceInputs(50, 0, 1.0, agreement, 0, 1.0),
        )
        assertThat(conf(1.0)).isGreaterThan(conf(0.0))
        // Unmeasurable agreement is neutral, between the extremes.
        assertThat(conf(null)).isGreaterThan(conf(0.0))
        assertThat(conf(null)).isLessThan(conf(1.0))
    }

    @Test
    fun `fewer sources means less confidence and result stays in unit range`() {
        fun conf(sources: Double) = confidenceFor(
            ConfidenceInputs(50, 0, sources, 1.0, 20, 1.0),
        )
        assertThat(conf(1.0)).isGreaterThan(conf(1.0 / 3.0))
        assertThat(conf(1.0)).isAtMost(1.0)
        assertThat(
            confidenceFor(ConfidenceInputs(0, Long.MAX_VALUE / 2, 0.0, 0.0, 0, 0.0)),
        ).isAtLeast(0.0)
    }
}
