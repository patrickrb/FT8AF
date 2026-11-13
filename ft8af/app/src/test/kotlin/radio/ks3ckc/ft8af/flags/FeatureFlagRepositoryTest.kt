package radio.ks3ckc.ft8af.flags

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** In-memory stores so precedence tests drive every layer deterministically. */
internal class FakeOverrideStore : FlagOverrideStore {
    private val map = mutableMapOf<FeatureFlag, FlagOverride>()
    override fun overrideFor(flag: FeatureFlag): FlagOverride =
        map[flag] ?: FlagOverride.DEFAULT
    override fun setOverride(flag: FeatureFlag, override: FlagOverride) {
        map[flag] = override
    }
}

internal class FakeRemoteStore(var config: RemoteFlagConfig? = null) : RemoteFlagConfigStore {
    var lastAttempt = 0L
    var savedJson: String? = null
    override fun cached(): RemoteFlagConfig? = config
    override fun save(rawJson: String, fetchedAtMs: Long) {
        savedJson = rawJson
        config = parseRemoteFlagConfig(rawJson)
    }
    override fun lastAttemptMs(): Long = lastAttempt
    override fun recordAttempt(nowMs: Long) {
        lastAttempt = nowMs
    }
}

class FeatureFlagRepositoryTest {
    private val now = 1_000_000_000_000L
    private val overrides = FakeOverrideStore()
    private val remote = FakeRemoteStore()

    private fun repo(
        isDebug: Boolean = true,
        default: Boolean = false,
    ) = DefaultFeatureFlagRepository(
        isDebugBuild = isDebug,
        buildDefaults = { default },
        overrideStore = overrides,
        remoteConfigStore = remote,
        clock = { now },
    )

    private fun freshRemote(vararg flags: Pair<String, Boolean>) = RemoteFlagConfig(
        version = 1,
        generatedAtMs = now - 60_000,
        expiresAtMs = now + 60_000,
        flags = flags.toMap(),
    )

    @Test
    fun `build default used when nothing else present`() {
        assertThat(repo(default = false).evaluate(FeatureFlag.BAND_ADVISOR))
            .isEqualTo(FlagEvaluation(FeatureFlag.BAND_ADVISOR, false, FlagValueSource.BUILD_DEFAULT))
        assertThat(repo(default = true).isEnabled(FeatureFlag.BAND_ADVISOR)).isTrue()
    }

    @Test
    fun `fresh remote config beats build default`() {
        remote.config = freshRemote("bandAdvisor" to true)
        val eval = repo(default = false).evaluate(FeatureFlag.BAND_ADVISOR)
        assertThat(eval.enabled).isTrue()
        assertThat(eval.source).isEqualTo(FlagValueSource.REMOTE_CONFIG)
    }

    @Test
    fun `expired remote config falls back to build default`() {
        remote.config = RemoteFlagConfig(
            version = 1,
            generatedAtMs = now - 120_000,
            expiresAtMs = now - 60_000,
            flags = mapOf("bandAdvisor" to true),
        )
        val eval = repo(default = false).evaluate(FeatureFlag.BAND_ADVISOR)
        assertThat(eval.enabled).isFalse()
        assertThat(eval.source).isEqualTo(FlagValueSource.BUILD_DEFAULT)
    }

    @Test
    fun `remote config missing a flag falls back to build default for that flag only`() {
        remote.config = freshRemote("bandAdvisor" to true)
        val repo = repo(default = false)
        assertThat(repo.evaluate(FeatureFlag.BAND_ADVISOR).source)
            .isEqualTo(FlagValueSource.REMOTE_CONFIG)
        val alerts = repo.evaluate(FeatureFlag.PROPAGATION_ALERTS)
        assertThat(alerts.enabled).isFalse()
        assertThat(alerts.source).isEqualTo(FlagValueSource.BUILD_DEFAULT)
    }

    @Test
    fun `debug override beats fresh remote config`() {
        remote.config = freshRemote("bandAdvisor" to true)
        overrides.setOverride(FeatureFlag.BAND_ADVISOR, FlagOverride.FORCED_OFF)
        val eval = repo(default = true).evaluate(FeatureFlag.BAND_ADVISOR)
        assertThat(eval.enabled).isFalse()
        assertThat(eval.source).isEqualTo(FlagValueSource.DEBUG_OVERRIDE)
    }

    @Test
    fun `forced on override enables a default-off flag`() {
        overrides.setOverride(FeatureFlag.PROPAGATION_ALERTS, FlagOverride.FORCED_ON)
        val eval = repo(default = false).evaluate(FeatureFlag.PROPAGATION_ALERTS)
        assertThat(eval.enabled).isTrue()
        assertThat(eval.source).isEqualTo(FlagValueSource.DEBUG_OVERRIDE)
    }

    @Test
    fun `override cleared back to DEFAULT falls through to next layer`() {
        remote.config = freshRemote("bandAdvisor" to true)
        overrides.setOverride(FeatureFlag.BAND_ADVISOR, FlagOverride.FORCED_OFF)
        overrides.setOverride(FeatureFlag.BAND_ADVISOR, FlagOverride.DEFAULT)
        assertThat(repo(default = false).evaluate(FeatureFlag.BAND_ADVISOR).source)
            .isEqualTo(FlagValueSource.REMOTE_CONFIG)
    }

    @Test
    fun `release build ignores developer overrides entirely`() {
        overrides.setOverride(FeatureFlag.BAND_ADVISOR, FlagOverride.FORCED_ON)
        val eval = repo(isDebug = false, default = false).evaluate(FeatureFlag.BAND_ADVISOR)
        assertThat(eval.enabled).isFalse()
        assertThat(eval.source).isEqualTo(FlagValueSource.BUILD_DEFAULT)
    }

    @Test
    fun `release build still honors remote config`() {
        remote.config = freshRemote("personalPskAnalytics" to true)
        val eval = repo(isDebug = false, default = false)
            .evaluate(FeatureFlag.PERSONAL_PSK_ANALYTICS)
        assertThat(eval.enabled).isTrue()
        assertThat(eval.source).isEqualTo(FlagValueSource.REMOTE_CONFIG)
    }

    @Test
    fun `unknown remote flag names are ignored`() {
        remote.config = freshRemote("someFutureFlag" to true)
        val eval = repo(default = false).evaluate(FeatureFlag.BAND_ADVISOR)
        assertThat(eval.source).isEqualTo(FlagValueSource.BUILD_DEFAULT)
    }
}
