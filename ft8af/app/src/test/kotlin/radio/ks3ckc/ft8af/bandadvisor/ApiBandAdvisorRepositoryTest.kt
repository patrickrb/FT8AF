package radio.ks3ckc.ft8af.bandadvisor

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class ApiBandAdvisorRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var cache: InMemoryRecommendationCacheStore
    private var now = 0L

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cache = InMemoryRecommendationCacheStore()
        now = iso("2026-09-27T12:01:00Z")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun iso(s: String): Long {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.parse(s)!!.time
    }

    private fun isoOf(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun body(
        generatedAt: String = "2026-09-27T12:00:00Z",
        validUntil: String = "2026-09-27T12:15:00Z",
        goal: String = "MAKE_CONTACT",
        callsign: String? = null,
    ): String {
        val callsignLine = callsign?.let { "\"callsign\": \"$it\"," } ?: ""
        return """
            {
              "generatedAt": "$generatedAt", "validUntil": "$validUntil",
              "grid": "EM28", $callsignLine "mode": "FT8", "goal": "$goal",
              "recommendedBand": "20m", "recommendedFrequencyHz": 14074000,
              "score": 0.9, "confidence": 0.8, "summary": "s",
              "destinations": [], "evidence": [], "scoreComponents": [],
              "alternatives": [],
              "sources": {"voacapAvailable": true, "regionalPskReporterAvailable": true,
                          "personalPskReporterAvailable": false}
            }
        """.trimIndent()
    }

    private fun repo() = ApiBandAdvisorRepository(
        cacheStore = cache,
        clock = { now },
        baseUrl = server.url("/api/band-advisor").toString().removeSuffix("/"),
    )

    private val request = AdvisorRequest(grid = "EM28ax", callsign = null)

    @Test
    fun `fetches parses and caches a recommendation`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        val result = repo().recommendation(request) as AdvisorResult.Available
        assertThat(result.recommendation.recommendedBand).isEqualTo("20m")
        assertThat(result.freshness).isEqualTo(Freshness.FRESH)
        assertThat(result.fromCache).isFalse()
        assertThat(cache.load()).isNotNull()
        // Grid was normalized to 4 chars in the query.
        val path = server.takeRequest().path!!
        assertThat(path).contains("grid=EM28")
        assertThat(path).doesNotContain("EM28ax")
        assertThat(path).contains("goal=MAKE_CONTACT")
        assertThat(path).doesNotContain("callsign")
    }

    @Test
    fun `fresh cache for same request is served without network`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        val r = repo()
        r.recommendation(request)
        now += 60_000 // Still inside validUntil.
        val second = r.recommendation(request) as AdvisorResult.Available
        assertThat(second.fromCache).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `goal change bypasses the fresh cache`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        server.enqueue(MockResponse().setBody(body(goal = "DX")))
        val r = repo()
        r.recommendation(request)
        now += 61_000 // Past the min fetch interval.
        val dx = r.recommendation(request.copy(goal = OperatingGoal.DX)) as AdvisorResult.Available
        assertThat(dx.fromCache).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `offline falls back to stale cache`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        val r = repo()
        r.recommendation(request)
        // Jump past validity, kill the server.
        now = iso("2026-09-27T12:20:00Z")
        server.shutdown()
        val result = r.recommendation(request, force = true) as AdvisorResult.Available
        assertThat(result.fromCache).isTrue()
        assertThat(result.freshness).isEqualTo(Freshness.STALE)
    }

    @Test
    fun `offline with no cache reports OFFLINE`() = runBlocking {
        server.shutdown()
        val result = repo().recommendation(request)
        assertThat((result as AdvisorResult.Unavailable).reason)
            .isEqualTo(UnavailableReason.OFFLINE)
    }

    @Test
    fun `invalid payload never replaces the cache`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        val r = repo()
        r.recommendation(request)
        now = iso("2026-09-27T12:20:00Z")
        server.enqueue(MockResponse().setBody("{\"broken\": true}"))
        val result = r.recommendation(request, force = true) as AdvisorResult.Available
        assertThat(result.fromCache).isTrue()
        assertThat(r.cached()!!.recommendation.recommendedBand).isEqualTo("20m")
    }

    @Test
    fun `http 500 with no cache reports HTTP_ERROR`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = repo().recommendation(request)
        assertThat((result as AdvisorResult.Unavailable).reason)
            .isEqualTo(UnavailableReason.HTTP_ERROR)
        assertThat(result.detail).contains("500")
    }

    @Test
    fun `429 imposes a back-off that even force honors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        val r = repo()
        val first = r.recommendation(request)
        assertThat((first as AdvisorResult.Unavailable).reason)
            .isEqualTo(UnavailableReason.HTTP_ERROR)
        now += 60_000
        val second = r.recommendation(request, force = true)
        assertThat((second as AdvisorResult.Unavailable).reason)
            .isEqualTo(UnavailableReason.RATE_LIMITED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `cooldown suppresses repeat requests but force overrides`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(body()))
        val r = repo()
        r.recommendation(request) // 404, no cache.
        now += 10_000 // Inside the 60s cooldown.
        val cooled = r.recommendation(request)
        assertThat((cooled as AdvisorResult.Unavailable).reason)
            .isEqualTo(UnavailableReason.RATE_LIMITED)
        assertThat(server.requestCount).isEqualTo(1)
        val forced = r.recommendation(request, force = true)
        assertThat(forced).isInstanceOf(AdvisorResult.Available::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `missing grid reports NO_GRID without any request`() = runBlocking {
        val result = repo().recommendation(AdvisorRequest(grid = null, callsign = null))
        assertThat((result as AdvisorResult.Unavailable).reason)
            .isEqualTo(UnavailableReason.NO_GRID)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `valid callsign is sent uppercase, invalid omitted`() = runBlocking {
        server.enqueue(MockResponse().setBody(body(callsign = "K1AF")))
        val r = repo()
        r.recommendation(request.copy(callsign = "k1af"))
        assertThat(server.takeRequest().path).contains("callsign=K1AF")

        now += 61_000
        server.enqueue(MockResponse().setBody(body()))
        r.recommendation(request.copy(callsign = "NOCALL", goal = OperatingGoal.POTA))
        assertThat(server.takeRequest().path).doesNotContain("callsign")
    }

    @Test
    fun `clearCache drops the stored recommendation`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        val r = repo()
        r.recommendation(request)
        assertThat(r.cached()).isNotNull()
        r.clearCache()
        assertThat(r.cached()).isNull()
    }

    @Test
    fun `expired cache is reported as EXPIRED`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        val r = repo()
        r.recommendation(request)
        now = iso("2026-09-27T14:00:00Z") // Far past validity + grace.
        assertThat(r.cached()!!.freshness).isEqualTo(Freshness.EXPIRED)
    }

    @Test
    fun `changing target region refetches instead of reusing another target`() =
        runBlocking {
            val r = repo()
            val targetRequest = request.copy(goal = OperatingGoal.TARGET, targetRegion = "EUROPE")
            server.enqueue(
                MockResponse().setBody(
                    body(goal = "TARGET").replace(
                        "\"goal\": \"TARGET\"",
                        "\"goal\": \"TARGET\", \"targetRegion\": \"EUROPE\"",
                    ),
                ),
            )
            r.recommendation(targetRequest)
            assertThat(server.takeRequest().path).contains("targetRegion=EUROPE")
            now += 61_000
            server.enqueue(
                MockResponse().setBody(
                    body(goal = "TARGET").replace(
                        "\"goal\": \"TARGET\"",
                        "\"goal\": \"TARGET\", \"targetRegion\": \"ASIA\"",
                    ),
                ),
            )
            val changed = r.recommendation(targetRequest.copy(targetRegion = "ASIA")) as AdvisorResult.Available
            assertThat(server.takeRequest().path).contains("targetRegion=ASIA")
            assertThat(changed.recommendation.targetRegion).isEqualTo("ASIA")
            assertThat(changed.fromCache).isFalse()
            assertThat(r.recommendation(targetRequest.copy(targetRegion = "ASIA")))
                .isEqualTo(changed.copy(fromCache = true))
            assertThat(server.requestCount).isEqualTo(2)
        }

    @Test
    fun `target cooldown does not display a cached answer for another region`() =
        runBlocking {
            cache.save(
                body(goal = "TARGET").replace(
                    "\"goal\": \"TARGET\"",
                    "\"goal\": \"TARGET\", \"targetRegion\": \"EUROPE\"",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(503))
            val r = repo()
            val asia = request.copy(goal = OperatingGoal.TARGET, targetRegion = "ASIA")
            assertThat(r.recommendation(asia)).isInstanceOf(AdvisorResult.Unavailable::class.java)
            assertThat(r.recommendation(asia, force = true)).isInstanceOf(AdvisorResult.Unavailable::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
}
