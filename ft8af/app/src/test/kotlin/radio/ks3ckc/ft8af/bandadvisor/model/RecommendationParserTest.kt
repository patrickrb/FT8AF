package radio.ks3ckc.ft8af.bandadvisor.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for org.json. Fixture mirrors the server contract exactly. */
@RunWith(RobolectricTestRunner::class)
class RecommendationParserTest {

    private val contract = """
        {
          "generatedAt": "2026-09-27T12:00:00Z",
          "validUntil": "2026-09-27T12:15:00Z",
          "grid": "EM28",
          "callsign": "K1AF",
          "mode": "FT8",
          "goal": "DX",
          "recommendedBand": "20m",
          "recommendedFrequencyHz": 14074000,
          "score": 0.91,
          "confidence": 0.87,
          "summary": "Best combination of live activity and long-distance reliability.",
          "destinations": ["Europe", "South America"],
          "evidence": [
            {"type": "VOACAP", "message": "Reliable paths into Europe are predicted for the current hour."},
            {"type": "REGIONAL_PSK_REPORTER", "message": "Nearby stations have reached 14 European countries in the last 15 minutes."},
            {"type": "PERSONAL_PSK_REPORTER", "message": "Your station has recently performed above its normal 20-meter range."}
          ],
          "scoreComponents": [
            {"name": "voacapReliability", "weight": 0.3, "value": 0.8, "contribution": 0.24}
          ],
          "personalAnalytics": {
            "enabled": true,
            "reportsReceived": 23,
            "uniqueReceivers": 18,
            "countriesReached": 7,
            "maximumDistanceKm": 8420,
            "medianSnrDb": -11,
            "bestSnrDb": -3,
            "performanceComparedToBaseline": 0.18
          },
          "alternatives": [
            {"band": "15m", "frequencyHz": 21074000, "score": 0.82, "reason": "Greater maximum distance, but fewer observed paths."},
            {"band": "40m", "frequencyHz": 7074000, "score": 0.74, "reason": "Better for dependable regional contacts."}
          ],
          "sources": {
            "voacapAvailable": true,
            "regionalPskReporterAvailable": true,
            "personalPskReporterAvailable": true
          }
        }
    """.trimIndent()

    @Test
    fun `parses the full contract example`() {
        val rec = parseBandRecommendation(contract)!!
        assertThat(rec.grid).isEqualTo("EM28")
        assertThat(rec.callsign).isEqualTo("K1AF")
        assertThat(rec.goal).isEqualTo(OperatingGoal.DX)
        assertThat(rec.recommendedBand).isEqualTo("20m")
        assertThat(rec.recommendedFrequencyHz).isEqualTo(14_074_000L)
        assertThat(rec.score).isWithin(1e-9).of(0.91)
        assertThat(rec.confidence).isWithin(1e-9).of(0.87)
        assertThat(rec.destinations).containsExactly("Europe", "South America").inOrder()
        assertThat(rec.evidence).hasSize(3)
        assertThat(rec.evidence[0].type).isEqualTo(EvidenceType.VOACAP)
        assertThat(rec.evidence[1].type).isEqualTo(EvidenceType.REGIONAL_PSK_REPORTER)
        assertThat(rec.scoreComponents).hasSize(1)
        assertThat(rec.scoreComponents[0].contribution).isWithin(1e-9).of(0.24)
        assertThat(rec.personalAnalytics!!.reportsReceived).isEqualTo(23)
        assertThat(rec.personalAnalytics!!.performanceComparedToBaseline).isWithin(1e-9).of(0.18)
        assertThat(rec.alternatives).hasSize(2)
        assertThat(rec.alternatives[0].frequencyHz).isEqualTo(21_074_000L)
        assertThat(rec.sources.voacapAvailable).isTrue()
        assertThat(rec.sources.isPartial).isFalse()
        assertThat(rec.validUntilMs - rec.generatedAtMs).isEqualTo(15L * 60 * 1000)
    }

    @Test
    fun `personal analytics and callsign are optional`() {
        val anonymous = contract
            .replace("\"callsign\": \"K1AF\",", "")
            .replace(Regex("\"personalAnalytics\":\\s*\\{[^}]*\\},"), "")
        val rec = parseBandRecommendation(anonymous)!!
        assertThat(rec.callsign).isNull()
        assertThat(rec.personalAnalytics).isNull()
    }

    @Test
    fun `unknown goal falls back to MAKE_CONTACT and unknown evidence type to OTHER`() {
        val mutated = contract
            .replace("\"goal\": \"DX\"", "\"goal\": \"SOMETHING_NEW\"")
            .replace("\"type\": \"VOACAP\"", "\"type\": \"FUTURE_SOURCE\"")
        val rec = parseBandRecommendation(mutated)!!
        assertThat(rec.goal).isEqualTo(OperatingGoal.MAKE_CONTACT)
        assertThat(rec.evidence[0].type).isEqualTo(EvidenceType.OTHER)
    }

    @Test
    fun `rejects invalid documents`() {
        assertThat(parseBandRecommendation("not json")).isNull()
        assertThat(parseBandRecommendation("{}")).isNull()
        // Score out of range.
        assertThat(parseBandRecommendation(contract.replace("\"score\": 0.91", "\"score\": 1.5"))).isNull()
        // Bogus frequency must never reach the tuning path.
        assertThat(
            parseBandRecommendation(
                contract.replace("\"recommendedFrequencyHz\": 14074000", "\"recommendedFrequencyHz\": 12"),
            ),
        ).isNull()
        // validUntil before generatedAt.
        assertThat(
            parseBandRecommendation(
                contract.replace("2026-09-27T12:15:00Z", "2026-09-27T11:00:00Z"),
            ),
        ).isNull()
        // Invalid grid.
        assertThat(parseBandRecommendation(contract.replace("\"EM28\"", "\"XYZ\""))).isNull()
    }

    @Test
    fun `partial sources are surfaced`() {
        val partial = contract.replace("\"voacapAvailable\": true", "\"voacapAvailable\": false")
        val rec = parseBandRecommendation(partial)!!
        assertThat(rec.sources.voacapAvailable).isFalse()
        assertThat(rec.sources.isPartial).isTrue()
        assertThat(rec.sources.anyAvailable).isTrue()
    }

    @Test
    fun `malformed list entries are dropped not fatal`() {
        val mutated = contract.replace(
            "{\"band\": \"15m\", \"frequencyHz\": 21074000, \"score\": 0.82, \"reason\": \"Greater maximum distance, but fewer observed paths.\"}",
            "{\"band\": \"\", \"frequencyHz\": -1, \"score\": 0.82, \"reason\": \"bad\"}",
        )
        val rec = parseBandRecommendation(mutated)!!
        assertThat(rec.alternatives).hasSize(1)
        assertThat(rec.alternatives[0].band).isEqualTo("40m")
    }

    @Test
    fun `freshness transitions FRESH STALE EXPIRED`() {
        val rec = parseBandRecommendation(contract)!!
        assertThat(freshnessOf(rec, rec.validUntilMs - 1)).isEqualTo(Freshness.FRESH)
        assertThat(freshnessOf(rec, rec.validUntilMs)).isEqualTo(Freshness.STALE)
        assertThat(freshnessOf(rec, rec.validUntilMs + STALE_GRACE_MS - 1)).isEqualTo(Freshness.STALE)
        assertThat(freshnessOf(rec, rec.validUntilMs + STALE_GRACE_MS)).isEqualTo(Freshness.EXPIRED)
    }
}
