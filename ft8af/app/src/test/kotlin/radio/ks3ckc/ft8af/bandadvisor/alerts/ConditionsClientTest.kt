package radio.ks3ckc.ft8af.bandadvisor.alerts

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConditionsClientTest {
    private val now = 1_790_532_000_000L // 2026-09-27T18:00:00Z

    private val valid = """
        {
          "generatedAt": "2026-09-27T17:55:00Z",
          "bands": [
            {"band": "10m", "score": 0.8, "confidence": 0.7, "observationCount": 30,
             "activityRatio": 2.1, "activeRegions": ["SOUTH_AMERICA"],
             "voacapSupported": true, "unusualOpening": false},
            {"band": "20m", "score": 0.6, "confidence": 0.9, "observationCount": 55,
             "activityRatio": 1.1, "activeRegions": [],
             "voacapSupported": true, "unusualOpening": false,
             "personalImprovement": true}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses bands with age from generatedAt`() {
        val conditions = ConditionsClient.parseConditions(valid, now)!!
        assertThat(conditions).hasSize(2)
        val ten = conditions[0]
        assertThat(ten.band).isEqualTo("10m")
        assertThat(ten.activeRegions).containsExactly("SOUTH_AMERICA")
        assertThat(ten.dataAgeMs).isEqualTo(5 * 60_000)
        assertThat(ten.personalImprovement).isNull()
        assertThat(conditions[1].personalImprovement).isTrue()
    }

    @Test
    fun `malformed entries are dropped, empty document is a failure`() {
        val oneBad = valid.replace("\"band\": \"20m\"", "\"band\": \"\"")
        assertThat(ConditionsClient.parseConditions(oneBad, now)!!).hasSize(1)

        assertThat(ConditionsClient.parseConditions("not json", now)).isNull()
        assertThat(ConditionsClient.parseConditions("{}", now)).isNull()
        assertThat(
            ConditionsClient.parseConditions("""{"generatedAt":"2026-09-27T17:55:00Z","bands":[]}""", now),
        ).isNull()
    }

    @Test
    fun `out-of-range values are clamped`() {
        val weird = valid
            .replace("\"score\": 0.8", "\"score\": 7.0")
            .replace("\"observationCount\": 30", "\"observationCount\": -3")
        val conditions = ConditionsClient.parseConditions(weird, now)!!
        assertThat(conditions[0].score).isEqualTo(1.0)
        assertThat(conditions[0].observationCount).isEqualTo(0)
    }
}
