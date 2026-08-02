package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading the operator's announced trips off `GET /api/me`.
 *
 * This parser fails *quietly*: a shape it doesn't recognise comes back as an
 * empty list, which the picker renders as "no upcoming trips" rather than an
 * error. That already happened once — announcements moved from a
 * `scheduled_activations` table into `trips` with a status, and the response
 * key went `activations` -> `plannedTrips` with `title` -> `name` — so the key
 * and field names are pinned here deliberately.
 *
 * Robolectric because org.json is an Android stub on the plain JVM classpath.
 */
@RunWith(RobolectricTestRunner::class)
class RotaPlannedTripTest {
    // 2026-08-01T22:00:00Z
    private val start = 1_785_621_600_000L

    @Test
    fun `parses the announced trips an api-me body carries, soonest first`() {
        val body =
            """
            {
              "operator": {"callsign": "K1AF"},
              "plannedTrips": [
                {"id":"b","name":"DEFCON To Kansas City","startTime":"2026-08-10T11:00:00.000Z",
                 "endTime":"2026-08-11T11:00:00.000Z","detail":"return leg"},
                {"id":"a","name":"Shakedown test drive","startTime":"2026-08-01T22:00:00.000Z",
                 "endTime":"2026-08-02T06:00:00.000Z"}
              ]
            }
            """.trimIndent()

        val plans = parseMyPlannedTrips(body)
        assertThat(plans.map { it.name })
            .containsExactly("Shakedown test drive", "DEFCON To Kansas City")
            .inOrder()
        assertThat(plans[0].id).isEqualTo("a")
        assertThat(plans[0].startTimeMs).isEqualTo(start)
        assertThat(plans[1].detail).isEqualTo("return leg")
    }

    @Test
    fun `the pre-merge activations shape yields nothing, not blank rows`() {
        // The body the service used to send. Asserting empty is not endorsing it —
        // it documents that this exact mismatch produces a picker that looks
        // merely empty, which is why the keys above are pinned.
        val legacy =
            """
            {"activations":[{"id":"a","title":"Shakedown","startTime":"2026-08-01T22:00:00.000Z"}]}
            """.trimIndent()
        assertThat(parseMyPlannedTrips(legacy)).isEmpty()
    }

    @Test
    fun `an id is kept, because starting the plan is what promotes that row`() {
        // The id is the whole point of picking: Start posts to
        // /api/trips/<id>/start. A parser that dropped it would silently create a
        // duplicate trip beside the announcement instead.
        val body = """{"plannedTrips":[{"id":"plan-7","name":"Open","startTime":"2026-08-01T22:00:00.000Z"}]}"""
        assertThat(parseMyPlannedTrips(body).single().id).isEqualTo("plan-7")
    }

    @Test
    fun `an open-ended plan parses with a null end`() {
        val body = """{"plannedTrips":[{"id":"a","name":"Open","startTime":"2026-08-01T22:00:00.000Z"}]}"""
        assertThat(parseMyPlannedTrips(body).single().endTimeMs).isNull()
    }

    @Test
    fun `rows missing an id, name or start are dropped rather than shown blank`() {
        val body =
            """
            {"plannedTrips":[
              {"name":"No id","startTime":"2026-08-01T22:00:00.000Z"},
              {"id":"b","startTime":"2026-08-01T22:00:00.000Z"},
              {"id":"c","name":"No start"},
              {"id":"d","name":"Good","startTime":"2026-08-01T22:00:00.000Z"}
            ]}
            """.trimIndent()
        assertThat(parseMyPlannedTrips(body).map { it.name }).containsExactly("Good")
    }

    @Test
    fun `a missing or unusable body yields no plans rather than throwing`() {
        assertThat(parseMyPlannedTrips(null)).isEmpty()
        assertThat(parseMyPlannedTrips("")).isEmpty()
        assertThat(parseMyPlannedTrips("not json")).isEmpty()
        assertThat(parseMyPlannedTrips("""{"operator":{}}""")).isEmpty()
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
}
