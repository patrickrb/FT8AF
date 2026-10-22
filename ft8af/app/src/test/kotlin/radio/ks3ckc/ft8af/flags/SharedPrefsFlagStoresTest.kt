package radio.ks3ckc.ft8af.flags

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPrefsFlagStoresTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val validBody = """
        {"version":1,"generatedAt":"2026-09-27T12:00:00Z","expiresAt":"2026-09-27T18:00:00Z",
         "flags":{"bandAdvisor":true}}
    """.trimIndent()

    @Test
    fun `override round-trips and clears`() {
        val store = SharedPrefsFlagOverrideStore(context)
        assertThat(store.overrideFor(FeatureFlag.BAND_ADVISOR)).isEqualTo(FlagOverride.DEFAULT)

        store.setOverride(FeatureFlag.BAND_ADVISOR, FlagOverride.FORCED_ON)
        assertThat(store.overrideFor(FeatureFlag.BAND_ADVISOR)).isEqualTo(FlagOverride.FORCED_ON)
        // A second store instance sees the persisted value.
        assertThat(SharedPrefsFlagOverrideStore(context).overrideFor(FeatureFlag.BAND_ADVISOR))
            .isEqualTo(FlagOverride.FORCED_ON)
        // Other flags are unaffected.
        assertThat(store.overrideFor(FeatureFlag.PROPAGATION_ALERTS)).isEqualTo(FlagOverride.DEFAULT)

        store.setOverride(FeatureFlag.BAND_ADVISOR, FlagOverride.DEFAULT)
        assertThat(store.overrideFor(FeatureFlag.BAND_ADVISOR)).isEqualTo(FlagOverride.DEFAULT)
    }

    @Test
    fun `remote config round-trips`() {
        val store = SharedPrefsRemoteFlagConfigStore(context)
        assertThat(store.cached()).isNull()

        store.save(validBody, 123L)
        val cached = store.cached()!!
        assertThat(cached.valueFor(FeatureFlag.BAND_ADVISOR)).isTrue()
        assertThat(SharedPrefsRemoteFlagConfigStore(context).cached()).isEqualTo(cached)
    }

    @Test
    fun `invalid payload is never cached over a valid one`() {
        val store = SharedPrefsRemoteFlagConfigStore(context)
        store.save(validBody, 123L)
        store.save("garbage", 456L)
        assertThat(store.cached()!!.valueFor(FeatureFlag.BAND_ADVISOR)).isTrue()
    }

    @Test
    fun `attempt timestamps round-trip`() {
        val store = SharedPrefsRemoteFlagConfigStore(context)
        assertThat(store.lastAttemptMs()).isEqualTo(0L)
        store.recordAttempt(789L)
        assertThat(store.lastAttemptMs()).isEqualTo(789L)
    }
}
