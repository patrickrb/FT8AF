package radio.ks3ckc.ft8af.qrz

import com.k1af.ft8af.GeneralVariables
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
 * Drive the QrzXmlClient singleton against a MockWebServer. The production
 * client hard-codes its endpoint as DEFAULT_BASE_URL but exposes an
 * @VisibleForTesting `baseUrl` seam; resetForTests() clears the in-memory
 * session, cache, lastError, and restores the production URL.
 *
 * Robolectric is required for android.util.Log (used by the client's
 * file/stderr logger).
 */
@RunWith(RobolectricTestRunner::class)
class QrzXmlClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        QrzXmlClient.resetForTests()
        QrzXmlClient.baseUrl = server.url("/xml/current/").toString()
        // Production reads credentials from GeneralVariables; default to a
        // configured pair for happy-path tests. Individual tests override.
        GeneralVariables.qrzXmlUsername = "testuser"
        GeneralVariables.qrzXmlPassword = "testpass"
    }

    @After
    fun tearDown() {
        server.shutdown()
        QrzXmlClient.resetForTests()
        GeneralVariables.qrzXmlUsername = ""
        GeneralVariables.qrzXmlPassword = ""
    }

    @Test
    fun lookup_happyPath_returnsImageUrl() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-ok.xml")))
        server.enqueue(MockResponse().setBody(fixture("qrz/lookup-with-image.xml")))

        val result = QrzXmlClient.lookup("K1ABC")

        assertThat(result).isNotNull()
        assertThat(result!!.imageUrl).isEqualTo("https://cdn.qrz.com/k1abc/photo.jpg")
        // Two requests: auth + lookup
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun lookup_missingCredentials_returnsNullAndSetsLastError() = runBlocking<Unit> {
        GeneralVariables.qrzXmlUsername = ""
        GeneralVariables.qrzXmlPassword = ""

        val result = QrzXmlClient.lookup("K1ABC")

        assertThat(result).isNull()
        assertThat(QrzXmlClient.lastError).contains("not configured")
        // No HTTP traffic should have occurred — we short-circuit before
        // reaching the network.
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun lookup_authFailure_setsLastErrorFromErrorTag() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-bad.xml")))

        val result = QrzXmlClient.lookup("K1ABC")

        assertThat(result).isNull()
        assertThat(QrzXmlClient.lastError).isEqualTo("Username/password incorrect")
    }

    @Test
    fun lookup_http500_returnsNull() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = QrzXmlClient.lookup("K1ABC")

        assertThat(result).isNull()
    }

    @Test
    fun lookup_noImageInResponse_returnsNullAndDoesNotCache() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-ok.xml")))
        server.enqueue(MockResponse().setBody(fixture("qrz/lookup-no-image.xml")))
        // Second lookup re-uses the session, so only the lookup response.
        server.enqueue(MockResponse().setBody(fixture("qrz/lookup-no-image.xml")))

        val first = QrzXmlClient.lookup("W1AW")
        val second = QrzXmlClient.lookup("W1AW")

        assertThat(first).isNull()
        assertThat(second).isNull()
        // Three requests: auth + two lookups (no caching of negative results).
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun lookup_cachesPositiveResultOnSecondCall() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-ok.xml")))
        server.enqueue(MockResponse().setBody(fixture("qrz/lookup-with-image.xml")))

        val first = QrzXmlClient.lookup("K1ABC")
        val second = QrzXmlClient.lookup("K1ABC")

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(second!!.imageUrl).isEqualTo(first!!.imageUrl)
        // Cache hit means no third request to the server.
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun lookup_isCaseInsensitiveOnCallsign() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-ok.xml")))
        server.enqueue(MockResponse().setBody(fixture("qrz/lookup-with-image.xml")))

        val upper = QrzXmlClient.lookup("K1ABC")
        val lower = QrzXmlClient.lookup("k1abc")

        // Lookup normalises to uppercase before the cache check; the lowercase
        // call should hit the cache and not generate a new HTTP request.
        assertThat(upper).isNotNull()
        assertThat(lower).isNotNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun lookup_sessionExpired_reAuthenticatesAndRetries() = runBlocking<Unit> {
        // Order of responses the client will see:
        //   1. auth -> session key SESSION-OK-...
        //   2. lookup -> "Invalid session key" error (forces re-auth)
        //   3. re-auth -> fresh session
        //   4. lookup retry -> success with image
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-ok.xml")))
        server.enqueue(MockResponse().setBody(fixture("qrz/session-expired.xml")))
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-ok.xml")))
        server.enqueue(MockResponse().setBody(fixture("qrz/lookup-with-image.xml")))

        val result = QrzXmlClient.lookup("K1ABC")

        assertThat(result).isNotNull()
        assertThat(result!!.imageUrl).isEqualTo("https://cdn.qrz.com/k1abc/photo.jpg")
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun testConnection_happyPath_returnsOk() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-ok.xml")))

        assertThat(QrzXmlClient.testConnection()).isEqualTo("OK")
    }

    @Test
    fun testConnection_badCreds_returnsErrorMessage() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-bad.xml")))

        val msg = QrzXmlClient.testConnection()
        assertThat(msg).isEqualTo("Username/password incorrect")
    }

    @Test
    fun testConnection_missingCredentials_returnsMissingMessage() = runBlocking<Unit> {
        GeneralVariables.qrzXmlUsername = ""
        GeneralVariables.qrzXmlPassword = ""

        assertThat(QrzXmlClient.testConnection()).isEqualTo("Missing username or password")
    }

    @Test
    fun clearCache_resetsLastError() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(fixture("qrz/auth-bad.xml")))
        QrzXmlClient.lookup("K1ABC") // sets lastError
        assertThat(QrzXmlClient.lastError).isNotNull()

        QrzXmlClient.clearCache()

        assertThat(QrzXmlClient.lastError).isNull()
    }

    private fun fixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing test resource: $path"
        }.bufferedReader().use { it.readText() }
}
