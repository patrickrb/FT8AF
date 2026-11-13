package radio.ks3ckc.ft8af.flags

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for android.util.Log + org.json inside the fetcher. */
@RunWith(RobolectricTestRunner::class)
class RemoteFlagConfigFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeRemoteStore
    private var now = 1_700_000_000_000L

    private val validBody = """
        {"version":1,"generatedAt":"2026-09-27T12:00:00Z","expiresAt":"2026-09-27T18:00:00Z",
         "flags":{"bandAdvisor":true}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeRemoteStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fetcher() = RemoteFlagConfigFetcher(
        store = store,
        clock = { now },
        baseUrl = server.url("/api/feature-config").toString(),
    )

    @Test
    fun `caches a valid payload`() = runBlocking {
        server.enqueue(MockResponse().setBody(validBody))
        fetcher().refreshIfDue()
        assertThat(store.savedJson).isEqualTo(validBody)
        assertThat(store.config!!.valueFor(FeatureFlag.BAND_ADVISOR)).isTrue()
        assertThat(store.lastAttempt).isEqualTo(now)
    }

    @Test
    fun `invalid payload is rejected and cache untouched`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version": 99}"""))
        fetcher().refreshIfDue()
        assertThat(store.savedJson).isNull()
        assertThat(store.config).isNull()
        // The attempt is still recorded so a broken endpoint isn't hammered.
        assertThat(store.lastAttempt).isEqualTo(now)
    }

    @Test
    fun `http error leaves cache untouched but records the attempt`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        fetcher().refreshIfDue()
        assertThat(store.savedJson).isNull()
        assertThat(store.lastAttempt).isEqualTo(now)
    }

    @Test
    fun `no fetch while cache is fresh`() = runBlocking {
        store.config = RemoteFlagConfig(1, now - 1000, now + 60_000, emptyMap())
        fetcher().refreshIfDue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `expired cache triggers a refetch`() = runBlocking {
        store.config = RemoteFlagConfig(1, now - 120_000, now - 60_000, emptyMap())
        server.enqueue(MockResponse().setBody(validBody))
        fetcher().refreshIfDue()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.savedJson).isEqualTo(validBody)
    }

    @Test
    fun `attempts are rate limited even when cache is missing`() = runBlocking {
        store.lastAttempt = now - (REMOTE_FLAG_MIN_ATTEMPT_INTERVAL_MS - 1)
        fetcher().refreshIfDue()
        assertThat(server.requestCount).isEqualTo(0)

        store.lastAttempt = now - REMOTE_FLAG_MIN_ATTEMPT_INTERVAL_MS
        server.enqueue(MockResponse().setBody(validBody))
        fetcher().refreshIfDue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `shouldFetch decision table`() {
        val fresh = RemoteFlagConfig(1, 0, now + 1, emptyMap())
        val expired = RemoteFlagConfig(1, 0, now - 1, emptyMap())
        // No cache, never attempted -> fetch.
        assertThat(shouldFetchRemoteFlagConfig(null, 0L, now)).isTrue()
        // Fresh cache -> no fetch.
        assertThat(shouldFetchRemoteFlagConfig(fresh, 0L, now)).isFalse()
        // Expired cache -> fetch (attempt interval permitting).
        assertThat(shouldFetchRemoteFlagConfig(expired, 0L, now)).isTrue()
        assertThat(
            shouldFetchRemoteFlagConfig(expired, now - 1000, now),
        ).isFalse()
    }
}
