package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading the operator's still-`active` trips off `GET /api/me` — the "continue"
 * half of the trip picker.
 *
 * Exists for the reinstall recovery (2026-08-04): the in-flight trip id lives in
 * Keystore-encrypted prefs, so a reinstall came up with the trip still running
 * server-side (716 mi / 138 QSOs) and no way to rejoin it. Active rows live under
 * `recentTrips`, which MIXES `active` and `completed` rows distinguished by a
 * `status` field — the shape was verified against the live server that day and is
 * pinned here the same way [RotaPlannedTripTest] pins `plannedTrips`.
 *
 * Robolectric because org.json is an Android stub on the plain JVM classpath.
 */
@RunWith(RobolectricTestRunner::class)
class RotaActiveTripTest {
    /** Abbreviated from the live 2026-08-04 `/api/me` response. */
    private val liveBody =
        """
        {
          "operator": {"callsign": "K1AF"},
          "recentTrips": [
            {"id":"2d6df3e4","name":"Kansas City to DEF CON!!","status":"active",
             "privacy":"delayed","startTime":"2026-08-04T14:42:14.227Z","endTime":null,
             "totalDistanceMiles":716.55,"qsoCount":138,"statesVisited":["CO","KS","NE"]},
            {"id":"dac0d839","name":"K1AF 2026-08-03","status":"completed",
             "privacy":"public","startTime":"2026-08-03T18:59:52.583Z",
             "endTime":"2026-08-03T21:12:31.391Z","totalDistanceMiles":32.65,"qsoCount":12}
          ],
          "plannedTrips": [
            {"id":"60204fc7","name":"DEFCON To Kansas City","status":"planned",
             "startTime":"2026-08-10T11:00:00.000Z"}
          ]
        }
        """.trimIndent()

    @Test
    fun `only active rows survive - completed and planned do not`() {
        val active = parseMyActiveTrips(liveBody)
        assertThat(active.map { it.id }).containsExactly("2d6df3e4")
        assertThat(active[0].name).isEqualTo("Kansas City to DEF CON!!")
        assertThat(active[0].qsoCount).isEqualTo(138)
        assertThat(active[0].totalDistanceMiles).isWithin(0.01).of(716.55)
    }

    @Test
    fun `most recently started first`() {
        val body =
            """
            {"recentTrips":[
              {"id":"old","name":"Old drive","status":"active","startTime":"2026-08-01T10:00:00.000Z"},
              {"id":"new","name":"New drive","status":"active","startTime":"2026-08-04T14:42:14.227Z"}
            ]}
            """.trimIndent()
        assertThat(parseMyActiveTrips(body).map { it.id })
            .containsExactly("new", "old")
            .inOrder()
    }

    @Test
    fun `rows missing id, name or startTime are dropped, not shown blank`() {
        val body =
            """
            {"recentTrips":[
              {"name":"No id","status":"active","startTime":"2026-08-04T14:42:14.227Z"},
              {"id":"x","status":"active","startTime":"2026-08-04T14:42:14.227Z"},
              {"id":"y","name":"Bad clock","status":"active","startTime":"yesterday-ish"},
              {"id":"ok","name":"Good","status":"active","startTime":"2026-08-04T14:42:14.227Z"}
            ]}
            """.trimIndent()
        assertThat(parseMyActiveTrips(body).map { it.id }).containsExactly("ok")
    }

    @Test
    fun `progress fields are optional`() {
        // A trip started seconds ago may not carry counts yet.
        val body =
            """
            {"recentTrips":[
              {"id":"a","name":"Fresh","status":"active","startTime":"2026-08-04T14:42:14.227Z"}
            ]}
            """.trimIndent()
        val trip = parseMyActiveTrips(body).single()
        assertThat(trip.qsoCount).isEqualTo(0)
        assertThat(trip.totalDistanceMiles).isEqualTo(0.0)
    }

    @Test
    fun `a shape mismatch parses as nothing to continue, not an error`() {
        assertThat(parseMyActiveTrips(null)).isEmpty()
        assertThat(parseMyActiveTrips("")).isEmpty()
        assertThat(parseMyActiveTrips("not json")).isEmpty()
        assertThat(parseMyActiveTrips("""{"recentTrips":{}}""")).isEmpty()
        assertThat(parseMyActiveTrips("""{"plannedTrips":[]}""")).isEmpty()
    }
}
