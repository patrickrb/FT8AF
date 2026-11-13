package radio.ks3ckc.ft8af.flags

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Robolectric for android.util.Log inside the fetcher. */
@RunWith(RobolectricTestRunner::class)
class FeatureFlagsRefreshTest {
    private lateinit var server: MockWebServer
    private val store = FakeRemoteStore()

    private val enablingBody = """
        {"version":1,"generatedAt":"2026-09-27T12:00:00Z","expiresAt":"2099-09-27T18:00:00Z",
         "flags":{"propagationAlerts":true}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        FeatureFlags.setFetcherForTests(
            RemoteFlagConfigFetcher(
                store = store,
                clock = { 1_790_532_000_000L },
                baseUrl = server.url("/api/feature-config").toString(),
            ),
        )
    }

    @After
    fun tearDown() {
        FeatureFlags.setFetcherForTests(null)
        server.shutdown()
    }

    @Test
    fun `onComplete runs after the refreshed config is stored`() {
        server.enqueue(MockResponse().setBody(enablingBody))
        val done = CountDownLatch(1)
        var seenAtCompletion: Boolean? = null
        FeatureFlags.refreshRemoteConfigAsync {
            seenAtCompletion = store.cached()?.valueFor(FeatureFlag.PROPAGATION_ALERTS)
            done.countDown()
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(seenAtCompletion).isTrue()
    }

    @Test
    fun `onComplete still runs when the fetch fails`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val done = CountDownLatch(1)
        FeatureFlags.refreshRemoteConfigAsync { done.countDown() }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(store.cached()).isNull()
    }

    @Test
    fun `onComplete runs even with no fetcher wired`() {
        FeatureFlags.setFetcherForTests(null)
        val done = CountDownLatch(1)
        FeatureFlags.refreshRemoteConfigAsync { done.countDown() }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
    }
}
