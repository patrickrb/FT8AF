package radio.ks3ckc.ft8af.bandadvisor.alerts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PropagationAlertPolicyTest {
    private val now = 1_790_532_000_000L
    private val grid = "EM28"

    private fun snapshot(
        band: String = "10m",
        score: Double = 0.8,
        confidence: Double = 0.8,
        observations: Int = 25,
        activityRatio: Double = 2.0,
        regions: List<String> = listOf("SOUTH_AMERICA"),
        voacap: Boolean = true,
        unusual: Boolean = false,
        personal: Boolean? = null,
        ageMs: Long = 5 * 60_000,
    ) = BandConditionSnapshot(
        band, score, confidence, observations, activityRatio, regions,
        voacap, unusual, personal, ageMs,
    )

    private fun prefs(
        enabled: Boolean = true,
        types: Set<AlertType> = setOf(AlertType.BAND_OPENING),
        quietStart: Int = 0,
        quietEnd: Int = 0,
        bands: Set<String> = emptySet(),
        regions: Set<String> = emptySet(),
    ) = AlertPrefs(enabled, types, quietStart, quietEnd, bands, regions)

    private fun evaluate(
        conditions: List<BandConditionSnapshot>,
        prefs: AlertPrefs = prefs(),
        delivered: Map<String, Long> = emptyMap(),
        localHour: Int = 12,
    ) = evaluateAlerts(grid, conditions, prefs, delivered, now, localHour)

    // ---------------- thresholds ----------------

    @Test
    fun `a well-evidenced opening alerts`() {
        val decisions = evaluate(listOf(snapshot()))
        assertThat(decisions).hasSize(1)
        assertThat(decisions[0].type).isEqualTo(AlertType.BAND_OPENING)
        assertThat(decisions[0].band).isEqualTo("10m")
        assertThat(decisions[0].region).isEqualTo("SOUTH_AMERICA")
        assertThat(decisions[0].conditionsAtMs).isEqualTo(now - 5 * 60_000)
    }

    @Test
    fun `insufficient evidence is silent`() {
        // Each mutation individually kills the alert.
        assertThat(evaluate(listOf(snapshot(observations = MIN_OBSERVATIONS - 1)))).isEmpty()
        assertThat(evaluate(listOf(snapshot(confidence = MIN_CONFIDENCE - 0.01)))).isEmpty()
        assertThat(evaluate(listOf(snapshot(activityRatio = MIN_ACTIVITY_RATIO - 0.1)))).isEmpty()
        assertThat(evaluate(listOf(snapshot(ageMs = MAX_DATA_AGE_MS + 1)))).isEmpty()
        // No VOACAP support AND not flagged unusual -> silent.
        assertThat(evaluate(listOf(snapshot(voacap = false, unusual = false)))).isEmpty()
        // Unusual observed opening without VOACAP support still qualifies.
        assertThat(evaluate(listOf(snapshot(voacap = false, unusual = true)))).hasSize(1)
    }

    @Test
    fun `disabled master or empty types is silent`() {
        assertThat(evaluate(listOf(snapshot()), prefs(enabled = false))).isEmpty()
        assertThat(evaluate(listOf(snapshot()), prefs(types = emptySet()))).isEmpty()
    }

    // ---------------- quiet hours ----------------

    @Test
    fun `quiet hours silence alerts including midnight wrap`() {
        val night = prefs(quietStart = 22, quietEnd = 7)
        assertThat(evaluate(listOf(snapshot()), night, localHour = 23)).isEmpty()
        assertThat(evaluate(listOf(snapshot()), night, localHour = 3)).isEmpty()
        assertThat(evaluate(listOf(snapshot()), night, localHour = 12)).hasSize(1)
    }

    @Test
    fun `isInQuietHours edge cases`() {
        assertThat(isInQuietHours(22, 7, 22)).isTrue()
        assertThat(isInQuietHours(22, 7, 7)).isFalse() // end exclusive
        assertThat(isInQuietHours(9, 17, 9)).isTrue()
        assertThat(isInQuietHours(9, 17, 17)).isFalse()
        assertThat(isInQuietHours(5, 5, 5)).isFalse() // equal = disabled
    }

    // ---------------- dedup + cooldowns ----------------

    @Test
    fun `same opening never alerts twice`() {
        val first = evaluate(listOf(snapshot())).single()
        val delivered = mapOf(first.identity to now - GLOBAL_COOLDOWN_MS - 1)
        // Past global cooldown but the identity matches -> per-band cooldown or
        // dedup keeps it silent. Push the delivery past the band cooldown too:
        val oldDelivery = mapOf(first.identity to now - PER_BAND_COOLDOWN_MS - 1)
        // Identity buckets are 90 min; a delivery 3h+ ago has a different bucket,
        // so build one with the CURRENT bucket to test pure dedup:
        val currentIdentity = alertIdentity(grid, "10m", "SOUTH_AMERICA", now)
        assertThat(
            evaluate(
                listOf(snapshot()),
                delivered = mapOf(currentIdentity to now - PER_BAND_COOLDOWN_MS - 1),
            ),
        ).isEmpty()
        // Sanity: with unrelated history it still fires.
        assertThat(
            evaluate(
                listOf(snapshot()),
                delivered = mapOf("other|20m|-|1" to now - PER_BAND_COOLDOWN_MS - 1),
            ),
        ).hasSize(1)
        // Silence-check for the delivered map built above (kept for clarity).
        assertThat(delivered).isNotEmpty()
        assertThat(oldDelivery).isNotEmpty()
    }

    @Test
    fun `global cooldown suppresses everything`() {
        val delivered = mapOf("x|20m|-|1" to now - GLOBAL_COOLDOWN_MS + 1)
        assertThat(evaluate(listOf(snapshot()), delivered = delivered)).isEmpty()
    }

    @Test
    fun `per-band cooldown suppresses that band only`() {
        val delivered = mapOf("$grid|10m|EUROPE|123" to now - PER_BAND_COOLDOWN_MS + 1)
        assertThat(evaluate(listOf(snapshot(band = "10m")), delivered = delivered)).isEmpty()
        assertThat(evaluate(listOf(snapshot(band = "15m")), delivered = delivered)).hasSize(1)
    }

    @Test
    fun `at most one alert per check - strongest band wins`() {
        val decisions = evaluate(
            listOf(snapshot(band = "10m", score = 0.7), snapshot(band = "15m", score = 0.9)),
        )
        assertThat(decisions).hasSize(1)
        assertThat(decisions[0].band).isEqualTo("15m")
    }

    // ---------------- type routing ----------------

    @Test
    fun `watched region beats other types`() {
        val p = prefs(
            types = setOf(AlertType.BAND_OPENING, AlertType.REGION_REACHABLE),
            regions = setOf("SOUTH_AMERICA"),
        )
        assertThat(evaluate(listOf(snapshot()), p).single().type)
            .isEqualTo(AlertType.REGION_REACHABLE)
    }

    @Test
    fun `watched band type requires the band to be watched`() {
        val p = prefs(types = setOf(AlertType.BAND_ACTIVE), bands = setOf("15m"))
        assertThat(evaluate(listOf(snapshot(band = "10m")), p)).isEmpty()
        assertThat(evaluate(listOf(snapshot(band = "15m")), p).single().type)
            .isEqualTo(AlertType.BAND_ACTIVE)
    }

    @Test
    fun `personal improvement type requires the signal flag`() {
        val p = prefs(types = setOf(AlertType.PERSONAL_IMPROVEMENT))
        assertThat(evaluate(listOf(snapshot(personal = null)), p)).isEmpty()
        assertThat(evaluate(listOf(snapshot(personal = false)), p)).isEmpty()
        assertThat(evaluate(listOf(snapshot(personal = true)), p).single().type)
            .isEqualTo(AlertType.PERSONAL_IMPROVEMENT)
    }

    @Test
    fun `unusual opening type requires the unusual flag`() {
        val p = prefs(types = setOf(AlertType.UNUSUAL_OPENING))
        assertThat(evaluate(listOf(snapshot(unusual = false)), p)).isEmpty()
        assertThat(evaluate(listOf(snapshot(unusual = true)), p).single().type)
            .isEqualTo(AlertType.UNUSUAL_OPENING)
    }

    // ---------------- identity + retention ----------------

    @Test
    fun `identity buckets openings into 90-minute windows`() {
        val a = alertIdentity("EM28", "10m", "EUROPE", now)
        val sameBucket = alertIdentity("EM28", "10m", "EUROPE", now + OPENING_BUCKET_MS - 1 - now % OPENING_BUCKET_MS)
        val nextBucket = alertIdentity("EM28", "10m", "EUROPE", now + OPENING_BUCKET_MS)
        assertThat(a).isEqualTo(sameBucket)
        assertThat(a).isNotEqualTo(nextBucket)
        assertThat(alertIdentity("EM28", "10m", null, now)).contains("|-|")
    }

    @Test
    fun `history pruning drops entries past retention`() {
        val history = mapOf(
            "fresh" to now - 1000,
            "old" to now - DEDUP_RETENTION_MS - 1,
        )
        assertThat(pruneHistory(history, now).keys).containsExactly("fresh")
    }
}
