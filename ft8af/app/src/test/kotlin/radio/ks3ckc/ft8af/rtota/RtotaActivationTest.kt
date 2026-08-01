package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading the operator's saved plans off `/api/me`, and deciding whether
 * starting a trip now would actually pick one up.
 *
 * The window check mirrors `MATCH_SLACK_HOURS` in the service's
 * lib/activation-match.ts. It is advisory — the server still decides — but the
 * consequence of the app disagreeing is a silent one: a plan marked `delayed`
 * whose privacy doesn't apply publishes a live position that was meant to lag,
 * and nothing on screen would have said so.
 *
 * Robolectric because org.json is an Android stub on the plain JVM classpath.
 */
@RunWith(RobolectricTestRunner::class)
class RtotaActivationTest {
    // 2026-08-01T22:00:00Z
    private val start = 1_785_621_600_000L
    private val hour = 3_600_000L

    private fun plan(
        startMs: Long = start,
        endMs: Long? = start + 8 * hour,
    ) = RtotaActivation("id", "Shakedown", startMs, endMs, null)

    @Test
    fun `parses the plans an api-me body carries, soonest first`() {
        val body =
            """
            {
              "operator": {"callsign": "K1AF"},
              "activations": [
                {"id":"b","title":"DEFCON To Kansas City","startTime":"2026-08-10T11:00:00.000Z",
                 "endTime":"2026-08-11T11:00:00.000Z","detail":"return leg"},
                {"id":"a","title":"Shakedown test drive","startTime":"2026-08-01T22:00:00.000Z",
                 "endTime":"2026-08-02T06:00:00.000Z"}
              ]
            }
            """.trimIndent()

        val plans = parseMyActivations(body)
        assertThat(plans.map { it.title })
            .containsExactly("Shakedown test drive", "DEFCON To Kansas City")
            .inOrder()
        assertThat(plans[0].startTimeMs).isEqualTo(start)
        assertThat(plans[1].detail).isEqualTo("return leg")
    }

    @Test
    fun `an open-ended plan parses with a null end`() {
        val body = """{"activations":[{"id":"a","title":"Open","startTime":"2026-08-01T22:00:00.000Z"}]}"""
        assertThat(parseMyActivations(body).single().endTimeMs).isNull()
    }

    @Test
    fun `rows missing an id, title or start are dropped rather than shown blank`() {
        val body =
            """
            {"activations":[
              {"title":"No id","startTime":"2026-08-01T22:00:00.000Z"},
              {"id":"b","startTime":"2026-08-01T22:00:00.000Z"},
              {"id":"c","title":"No start"},
              {"id":"d","title":"Good","startTime":"2026-08-01T22:00:00.000Z"}
            ]}
            """.trimIndent()
        assertThat(parseMyActivations(body).map { it.title }).containsExactly("Good")
    }

    @Test
    fun `a missing or unusable body yields no plans rather than throwing`() {
        assertThat(parseMyActivations(null)).isEmpty()
        assertThat(parseMyActivations("")).isEmpty()
        assertThat(parseMyActivations("not json")).isEmpty()
        assertThat(parseMyActivations("""{"operator":{}}""")).isEmpty()
    }

    @Test
    fun `iso timestamps parse as UTC, and junk does not`() {
        assertThat(parseIsoUtc("2026-08-01T22:00:00.000Z")).isEqualTo(start)
        assertThat(parseIsoUtc(null)).isNull()
        assertThat(parseIsoUtc("")).isNull()
        // A local-time string would silently place a plan hours away.
        assertThat(parseIsoUtc("2026-08-01 22:00:00")).isNull()
        assertThat(parseIsoUtc("tomorrow")).isNull()
    }

    @Test
    fun `a trip started inside the planned window matches`() {
        assertThat(activationMatchesNow(plan(), start)).isTrue()
        assertThat(activationMatchesNow(plan(), start + 4 * hour)).isTrue()
    }

    @Test
    fun `the twelve-hour slack either side matches, mirroring the server`() {
        // Departures rarely run on time, which is why the server allows this at all.
        assertThat(activationMatchesNow(plan(), start - 11 * hour)).isTrue()
        assertThat(activationMatchesNow(plan(), start + 8 * hour + 11 * hour)).isTrue()
    }

    @Test
    fun `outside the slack does not match`() {
        assertThat(activationMatchesNow(plan(), start - 13 * hour)).isFalse()
        assertThat(activationMatchesNow(plan(), start + 8 * hour + 13 * hour)).isFalse()
    }

    @Test
    fun `an open-ended plan is assumed to span a day, as on the server`() {
        val open = plan(endMs = null)
        assertThat(activationMatchesNow(open, start + 20 * hour)).isTrue()
        // 24 h span + 12 h slack = 36 h; past that it is over.
        assertThat(activationMatchesNow(open, start + 37 * hour)).isFalse()
    }
}
