package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The resume handshake: the dedupe key the app computes, and the parse of what
 * `GET /api/trips/:id/sync-state` answers.
 *
 * The key format is a contract with someone else's code — `dedupeKey()` in the
 * service's lib/qso.ts. These tests are the only thing that catches the two
 * halves drifting apart, and the direction of the risk is asymmetric: a key that
 * fails to match merely re-sends a contact the server dedupes anyway, while a
 * key that matches the wrong thing drops a QSO that never arrived.
 *
 * Robolectric because org.json is an Android stub on the plain JVM classpath.
 */
@RunWith(RobolectricTestRunner::class)
class RtotaSyncStateTest {
    private val ts = 1_753_970_709_000L // 2025-07-31T14:05:09Z

    @Test
    fun `dedupe key matches the server's callsign-band-mode-minute format`() {
        val qso = TripQso(callsign = "K1ABC", timestampMs = ts, band = "20M", mode = "FT8")
        // Seconds are deliberately absent: the server slices the ISO instant to the
        // minute so a live-streamed QSO and its end-of-trip ADIF twin collide.
        assertThat(qso.dedupeKey()).isEqualTo("K1ABC|20M|FT8|2025-07-31T14:05")
    }

    @Test
    fun `dedupe key uppercases and trims exactly like the server`() {
        val qso = TripQso(callsign = " k1abc ", timestampMs = ts, band = "20m", mode = "ft8")
        assertThat(qso.dedupeKey()).isEqualTo("K1ABC|20M|FT8|2025-07-31T14:05")
    }

    @Test
    fun `missing band or mode become empty fields, not dropped ones`() {
        // The server joins four fields unconditionally; an app that omitted them
        // would produce a three-field key that never matches anything.
        val qso = TripQso(callsign = "K1ABC", timestampMs = ts, band = null, mode = null)
        assertThat(qso.dedupeKey()).isEqualTo("K1ABC|||2025-07-31T14:05")
    }

    @Test
    fun `two contacts in the same minute on different bands are distinct`() {
        val a = TripQso(callsign = "K1ABC", timestampMs = ts, band = "20M", mode = "FT8")
        val b = TripQso(callsign = "K1ABC", timestampMs = ts, band = "40M", mode = "FT8")
        assertThat(a.dedupeKey()).isNotEqualTo(b.dedupeKey())
    }

    @Test
    fun `parses a full sync-state answer`() {
        val body =
            """
            {
              "tripId": "trip-123",
              "status": "active",
              "points": {"count": 412, "firstAt": null, "lastAt": null, "lastRevision": 412},
              "qsos": {
                "count": 2,
                "lastAt": "2025-07-31T14:05:09.000Z",
                "lastRevision": 2,
                "dedupeKeys": ["K1ABC|20M|FT8|2025-07-31T14:05", "W9XYZ|40M|FT8|2025-07-31T13:58"],
                "truncated": false
              },
              "serverTime": "2025-07-31T14:06:00.000Z"
            }
            """.trimIndent()

        val sync = parseSyncState(body)
        assertThat(sync).isNotNull()
        assertThat(sync!!.tripId).isEqualTo("trip-123")
        assertThat(sync.status).isEqualTo("active")
        assertThat(sync.pointCount).isEqualTo(412)
        assertThat(sync.qsoCount).isEqualTo(2)
        assertThat(sync.truncated).isFalse()
        assertThat(sync.qsoDedupeKeys).containsExactly(
            "K1ABC|20M|FT8|2025-07-31T14:05",
            "W9XYZ|40M|FT8|2025-07-31T13:58",
        )
    }

    @Test
    fun `a truncated key list is reported so the caller does not over-trust it`() {
        val body = """{"tripId":"t","status":"active","qsos":{"count":900,"dedupeKeys":[],"truncated":true}}"""
        assertThat(parseSyncState(body)!!.truncated).isTrue()
    }

    @Test
    fun `a body with no trip id or no body at all yields null`() {
        // The flush loop treats null as "handshake unavailable" and re-sends
        // everything, which is correct but must not be mistaken for "server has none".
        assertThat(parseSyncState(null)).isNull()
        assertThat(parseSyncState("")).isNull()
        assertThat(parseSyncState("not json")).isNull()
        assertThat(parseSyncState("""{"status":"active"}""")).isNull()
    }

    @Test
    fun `missing sections default to zero rather than throwing`() {
        val sync = parseSyncState("""{"tripId":"t","status":"active"}""")
        assertThat(sync).isNotNull()
        assertThat(sync!!.pointCount).isEqualTo(0)
        assertThat(sync.qsoCount).isEqualTo(0)
        assertThat(sync.qsoDedupeKeys).isEmpty()
    }
}
