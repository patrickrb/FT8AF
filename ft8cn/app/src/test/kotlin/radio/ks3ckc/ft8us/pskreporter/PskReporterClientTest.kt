package radio.ks3ckc.ft8us.pskreporter

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drive the PSK Reporter client against a MockWebServer with a controllable
 * clock. The production singleton enforces a 4m50s client-side cooldown and
 * a 15-minute back-off on 429/503; both are time-driven, so the test injects
 * `clock` via the @VisibleForTesting seam to fast-forward without sleeping.
 */
@RunWith(RobolectricTestRunner::class)
class PskReporterClientTest {

    private lateinit var server: MockWebServer
    private var now: Long = 1_000_000_000_000L  // arbitrary epoch ms

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        PskReporterClient.resetForTests()
        PskReporterClient.baseUrl = server.url("/query").toString()
        PskReporterClient.clock = { now }
    }

    @After
    fun tearDown() {
        server.shutdown()
        PskReporterClient.resetForTests()
    }

    @Test
    fun fetchSpots_happyPath_parsesAllValidReceptionReports() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))

        val spots = PskReporterClient.fetchSpotsForMe("W1AW", secondsBack = 900)

        assertThat(spots).isNotNull()
        // Fixture has 4 reception reports; two are dropped: JA1XX has an
        // empty receiverLocator (skippedNoGrid path), and VK2YY has a
        // non-numeric frequency (skippedBadGrid path).
        assertThat(spots!!).hasSize(2)
        assertThat(spots.map { it.receiverCallsign })
            .containsExactly("VE3XY", "DL1AA")
    }

    @Test
    fun fetchSpots_populatesLatLngFromReceiverLocator() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))

        val spots = PskReporterClient.fetchSpotsForMe("W1AW", secondsBack = 900)!!
        val ve3xy = spots.first { it.receiverCallsign == "VE3XY" }

        // FN03ab decodes to roughly 43.08N / -79.94W (Niagara peninsula).
        assertThat(ve3xy.receiverLat).isWithin(0.5).of(43.08)
        assertThat(ve3xy.receiverLon).isWithin(0.5).of(-79.94)
        // Plus the metadata we passed through verbatim.
        assertThat(ve3xy.frequencyHz).isEqualTo(14076234L)
        assertThat(ve3xy.snr).isEqualTo(-8)
        assertThat(ve3xy.mode).isEqualTo("FT8")
    }

    @Test
    fun fetchSpots_secondCallWithinCooldown_returnsNullAndDoesNotHitNetwork() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))

        val first = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(first).isNotNull()

        // Advance only 30 seconds (well inside the 4m50s cooldown).
        now += 30_000L
        val second = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(second).isNull()
        // Only the first request reached the server.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun fetchSpots_forceBypassesClientCooldown() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))

        val first = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(first).isNotNull()

        // 30s is well inside the 4m50s cooldown, but force=true overrides it
        // (used for the first overlay fetch when the map is re-entered).
        now += 30_000L
        val forced = PskReporterClient.fetchSpotsForMe("W1AW", 900, force = true)
        assertThat(forced).isNotNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun fetchSpots_cooldownSkipClearsStaleError() = runBlocking<Unit> {
        // First fetch fails with a server error, setting lastError.
        server.enqueue(MockResponse().setResponseCode(500))
        val failed = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(failed).isNull()
        assertThat(PskReporterClient.lastError).isNotNull()

        // A second call within the cooldown is a benign skip (not a failed attempt),
        // so it must clear lastError — the overlay shouldn't keep showing a stale error.
        now += 30_000L
        val skipped = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(skipped).isNull()
        assertThat(PskReporterClient.lastError).isNull()
        // Confirm it really was a cooldown skip, not a network call.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun fetchSpots_forceStillRespectsRateLimitBackoff() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429))

        val rateLimited = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(rateLimited).isNull()

        // Inside the 15-minute back-off: force must NOT override it — we never
        // hammer a server that returned 429/503.
        now += 60_000L
        val forced = PskReporterClient.fetchSpotsForMe("W1AW", 900, force = true)
        assertThat(forced).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun fetchSpots_afterCooldownExpires_callsServerAgain() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))

        val first = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(first).isNotNull()

        // 5 minutes is past the 4m50s cooldown threshold.
        now += 5L * 60_000L
        val second = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(second).isNotNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun fetchSpots_http429_triggers15MinuteBackoff() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429))

        val first = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(first).isNull()
        assertThat(PskReporterClient.lastError).contains("429")

        // Advance 10 minutes — still inside the 15-minute back-off window.
        now += 10L * 60_000L
        val second = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(second).isNull()
        // Only the original 429 hit the server; the second attempt
        // short-circuited on the in-memory back-off.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun fetchSpots_http503_triggersSameBackoffPath() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))

        val first = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(first).isNull()
        assertThat(PskReporterClient.lastError).contains("503")
    }

    @Test
    fun fetchSpots_recoversAfterRateLimitWindowExpires() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))

        val rateLimited = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(rateLimited).isNull()

        // Past both the 15-minute rate-limit window AND the 4m50s cooldown.
        now += 16L * 60_000L
        val recovered = PskReporterClient.fetchSpotsForMe("W1AW", 900)
        assertThat(recovered).isNotNull()
    }

    @Test
    fun fetchSpots_uppercasesCallsignInQueryString() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("pskreporter/reception-report.xml")))

        PskReporterClient.fetchSpotsForMe("w1aw", 900)

        val req = server.takeRequest()
        assertThat(req.path).contains("senderCallsign=W1AW")
    }

    private fun fixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing test resource: $path"
        }.bufferedReader().use { it.readText() }
}
