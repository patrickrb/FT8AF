package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Request shape and failure classification against a stub server. What is being
 * pinned here is the contract with roadsontheair.com: the paths, the Bearer header, and
 * — most importantly — which failures are worth retrying from a moving vehicle
 * and which are the operator's problem to fix.
 */
@RunWith(RobolectricTestRunner::class)
class RotaClientTest {
    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `registerOperator posts the callsign and returns the key`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody("""{"id":"op1","callsign":"K1AF","apiKey":"rota_abc123"}"""),
            )
            val result = RotaClient.registerOperator(baseUrl, "k1af", homeGrid = "DM79")

            assertThat(result.getOrNull()).isEqualTo("rota_abc123")
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/operators")
            assertThat(request.method).isEqualTo("POST")
            val body = JSONObject(request.body.readUtf8())
            assertThat(body.getString("callsign")).isEqualTo("K1AF")
            assertThat(body.getString("homeGrid")).isEqualTo("DM79")
        }

    @Test
    fun `an already-registered callsign surfaces the server's message`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setBody("""{"error":"That callsign is already registered."}"""),
            )
            val error = RotaClient.registerOperator(baseUrl, "K1AF").exceptionOrNull()

            assertThat(error).isInstanceOf(RotaHttpException::class.java)
            assertThat((error as RotaHttpException).serverMessage)
                .isEqualTo("That callsign is already registered.")
            // A 409 is the user's to resolve — retrying would fail identically forever.
            assertThat(isRetryableRotaFailure(error)).isFalse()
        }

    @Test
    fun `createTrip sends the bearer key and returns the handle`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"id":"trip-1","shareToken":"tok","status":"active"}"""),
            )
            val handle =
                RotaClient.createTrip(
                    baseUrl = baseUrl,
                    apiKey = "rota_key",
                    name = "Route 66",
                    startTimeMs = 1_753_970_709_000L,
                    privacy = "delayed",
                ).getOrNull()

            assertThat(handle).isEqualTo(RotaTripHandle("trip-1", "tok"))
            val request = server.takeRequest()
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer rota_key")
            val body = JSONObject(request.body.readUtf8())
            assertThat(body.getString("name")).isEqualTo("Route 66")
            assertThat(body.getString("startTime")).isEqualTo("2025-07-31T14:05:09.000Z")
            assertThat(body.getString("privacy")).isEqualTo("delayed")
        }

    @Test
    fun `sendLive posts points and qsos to the trip's live endpoint`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"points":{"inserted":1},"qsos":{"inserted":1,"duplicatesExisting":0,"duplicatesInBatch":0}}""",
                ),
            )
            val batch =
                RotaBatch(
                    points = listOf(TripPoint(1_753_970_709_000L, 39.0, -105.0)),
                    qsos = listOf(TripQso("K1AF", 1_753_970_709_000L, band = "20m", mode = "FT8")),
                )
            val ack = RotaClient.sendLive(baseUrl, "k", "trip-1", batch).getOrNull()

            assertThat(ack?.qsosInserted).isEqualTo(1)
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/trips/trip-1/live")
            val body = JSONObject(request.body.readUtf8())
            assertThat(body.getJSONArray("points").length()).isEqualTo(1)
            assertThat(body.getJSONArray("qsos").getJSONObject(0).getString("callsign"))
                .isEqualTo("K1AF")
        }

    @Test
    fun `a live batch the server accepts with no body still reports success`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(""))
            val batch = RotaBatch(listOf(TripPoint(1L, 39.0, -105.0)), emptyList())
            assertThat(RotaClient.sendLive(baseUrl, "k", "t", batch).isSuccess).isTrue()
        }

    @Test
    fun `completeTrip hits the complete endpoint`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"status":"completed"}"""))
            assertThat(RotaClient.completeTrip(baseUrl, "k", "trip-1").isSuccess).isTrue()
            assertThat(server.takeRequest().path).isEqualTo("/api/trips/trip-1/complete")
        }

    @Test
    fun `announceTrip posts a planned trip to the trips endpoint`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"plan-1"}"""))
            val id =
                RotaClient.announceTrip(
                    baseUrl = baseUrl,
                    apiKey = "k",
                    name = "I-70 westbound",
                    startTimeMs = 1_753_970_709_000L,
                    bands = listOf("20m", "40m"),
                    modes = listOf("FT8"),
                ).getOrNull()

            assertThat(id).isEqualTo("plan-1")
            val request = server.takeRequest()
            // /api/activations is gone; an announcement is a trip with a status.
            assertThat(request.path).isEqualTo("/api/trips")
            val body = JSONObject(request.body.readUtf8())
            assertThat(body.getString("status")).isEqualTo("planned")
            assertThat(body.getString("name")).isEqualTo("I-70 westbound")
            assertThat(body.getJSONArray("bands").length()).isEqualTo(2)
        }

    @Test
    fun `startPlannedTrip promotes the plan by id and keeps its share token`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"id":"plan-1","status":"active","startTime":"2026-08-02T14:00:00.000Z","shareToken":"tok-9"}""",
                ),
            )
            val handle =
                RotaClient.startPlannedTrip(
                    baseUrl = baseUrl,
                    apiKey = "k",
                    tripId = "plan-1",
                    startTimeMs = 1_753_970_709_000L,
                ).getOrNull()

            assertThat(handle?.id).isEqualTo("plan-1")
            assertThat(handle?.shareToken).isEqualTo("tok-9")
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/trips/plan-1/start")
            assertThat(JSONObject(request.body.readUtf8()).getString("startTime"))
                .isEqualTo("2025-07-31T14:05:09.000Z")
        }

    @Test
    fun `startPlannedTrip survives a server that sends no share token`() =
        runBlocking {
            // The field was added after the endpoint shipped; a missing one must
            // leave the share link empty rather than fail the start and strand
            // the trip in "pending create" forever.
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"plan-1","status":"active"}"""))
            val handle =
                RotaClient.startPlannedTrip(baseUrl, "k", "plan-1", 1_753_970_709_000L).getOrNull()

            assertThat(handle?.id).isEqualTo("plan-1")
            assertThat(handle?.shareToken).isNull()
        }

    @Test
    fun `a 409 from start comes back as an http failure the caller can classify`() =
        runBlocking {
            // The manager turns this into "already started, adopt the row" rather
            // than an error — but only if the code survives as RotaHttpException.
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Trip is already active"}"""))
            val error =
                RotaClient.startPlannedTrip(baseUrl, "k", "plan-1", 1_753_970_709_000L).exceptionOrNull()

            assertThat(error).isInstanceOf(RotaHttpException::class.java)
            assertThat((error as RotaHttpException).httpCode).isEqualTo(409)
            assertThat(error.serverMessage).isEqualTo("Trip is already active")
        }

    @Test
    fun `a 409 start means the plan is already the running trip, not a failure`() {
        // The rover retried over a flaky link and the first attempt actually
        // landed. Surfacing this as an error would leave a trip stuck "pending
        // create" with a queue that never drains, so the manager adopts the row.
        val outcome = classifyPlanStartFailure(RotaHttpException(409, ""), "plan-1")
        assertThat(outcome).isEqualTo(PlanStartOutcome.ALREADY_STARTED)
    }

    @Test
    fun `a 404 start means the plan was cancelled, so fall back to a plain trip`() {
        // Cancelled on the site while the phone had no signal. The drive still
        // happened; creating an ordinary trip is what keeps its route.
        val outcome = classifyPlanStartFailure(RotaHttpException(404, ""), "plan-1")
        assertThat(outcome).isEqualTo(PlanStartOutcome.PLAN_GONE)
    }

    @Test
    fun `other start failures retry, and a plain create never falls back`() {
        assertThat(classifyPlanStartFailure(RotaHttpException(503, ""), "plan-1"))
            .isEqualTo(PlanStartOutcome.RETRY)
        assertThat(classifyPlanStartFailure(IOException("no route to host"), "plan-1"))
            .isEqualTo(PlanStartOutcome.RETRY)
        // A 401 is fatal, but that is `failed()`'s call via isRetryableRotaFailure —
        // what matters here is that it is not mistaken for an adopt-or-recreate.
        assertThat(classifyPlanStartFailure(RotaHttpException(401, ""), "plan-1"))
            .isEqualTo(PlanStartOutcome.RETRY)
        // No plan id: there was no promotion to fall back from.
        assertThat(classifyPlanStartFailure(RotaHttpException(409, ""), ""))
            .isEqualTo(PlanStartOutcome.RETRY)
        assertThat(classifyPlanStartFailure(RotaHttpException(404, ""), ""))
            .isEqualTo(PlanStartOutcome.RETRY)
    }

    @Test
    fun `a server error is retryable, a rejected request is not`() {
        assertThat(isRetryableRotaFailure(RotaHttpException(503, ""))).isTrue()
        assertThat(isRetryableRotaFailure(RotaHttpException(429, ""))).isTrue()
        assertThat(isRetryableRotaFailure(IOException("no route to host"))).isTrue()
        assertThat(isRetryableRotaFailure(RotaHttpException(401, ""))).isFalse()
        assertThat(isRetryableRotaFailure(RotaHttpException(403, ""))).isFalse()
        assertThat(isRetryableRotaFailure(IllegalStateException("bad parse"))).isFalse()
    }

    @Test
    fun `backoff grows and then holds at fifteen minutes`() {
        assertThat(rotaBackoffMs(1)).isEqualTo(30_000L)
        assertThat(rotaBackoffMs(2)).isEqualTo(60_000L)
        assertThat(rotaBackoffMs(3)).isEqualTo(120_000L)
        assertThat(rotaBackoffMs(6)).isEqualTo(15 * 60_000L)
        // Past the cap the wait must not keep doubling toward hours.
        assertThat(rotaBackoffMs(50)).isEqualTo(15 * 60_000L)
    }
}
