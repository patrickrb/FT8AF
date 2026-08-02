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
    fun `createActivation posts the announcement`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"act-1"}"""))
            val id =
                RotaClient.createActivation(
                    baseUrl = baseUrl,
                    apiKey = "k",
                    title = "I-70 westbound",
                    startTimeMs = 1_753_970_709_000L,
                    bands = listOf("20m", "40m"),
                    modes = listOf("FT8"),
                ).getOrNull()

            assertThat(id).isEqualTo("act-1")
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertThat(body.getString("title")).isEqualTo("I-70 westbound")
            assertThat(body.getJSONArray("bands").length()).isEqualTo(2)
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
