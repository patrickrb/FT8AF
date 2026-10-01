package radio.ks3ckc.ft8af.flags

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.k1af.ft8af.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide access point for feature flags, wired from BuildConfig +
 * SharedPreferences in [init] (called from FT8AFApplication.onCreate, cheap
 * and synchronous) and refreshed from the backend opportunistically off the
 * main thread. Matches the app's manual-singleton pattern (no DI container).
 *
 * Before [init] runs (or in isolated unit tests) every flag resolves to its
 * build-time default via a null-safe fallback, so nothing can crash from
 * ordering.
 */
object FeatureFlags : FeatureFlagRepository {
    @Volatile
    private var delegate: FeatureFlagRepository? = null

    @Volatile
    var overrideStore: FlagOverrideStore? = null
        private set

    private var fetcher: RemoteFlagConfigFetcher? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Build-time defaults: debug ships enabled, release ships disabled. */
    fun buildDefault(flag: FeatureFlag): Boolean = when (flag) {
        FeatureFlag.BAND_ADVISOR -> BuildConfig.FEATURE_BAND_ADVISOR_DEFAULT
        FeatureFlag.PERSONAL_PSK_ANALYTICS -> BuildConfig.FEATURE_PERSONAL_PSK_ANALYTICS_DEFAULT
        FeatureFlag.PROPAGATION_ALERTS -> BuildConfig.FEATURE_PROPAGATION_ALERTS_DEFAULT
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        val overrides = SharedPrefsFlagOverrideStore(appContext)
        val remoteStore = SharedPrefsRemoteFlagConfigStore(appContext)
        overrideStore = overrides
        delegate = DefaultFeatureFlagRepository(
            isDebugBuild = BuildConfig.DEBUG,
            buildDefaults = ::buildDefault,
            overrideStore = overrides,
            remoteConfigStore = remoteStore,
        )
        fetcher = RemoteFlagConfigFetcher(remoteStore)
        // The remote refresh is NOT kicked here: Application.onCreate also runs
        // under Robolectric for every unit test, and startup must never depend
        // on (or even attempt) network. ComposeMainActivity triggers
        // [refreshRemoteConfigAsync] when the real UI comes up.
    }

    /**
     * Kick a background refresh of the remote configuration if one is due.
     * Never blocks, never throws into the caller.
     */
    fun refreshRemoteConfigAsync() {
        val f = fetcher ?: return
        scope.launch {
            try {
                f.refreshIfDue()
            } catch (_: Exception) {
                // Remote config must never interfere with normal operation.
            }
        }
    }

    override fun isEnabled(flag: FeatureFlag): Boolean =
        delegate?.isEnabled(flag) ?: buildDefault(flag)

    override fun evaluate(flag: FeatureFlag): FlagEvaluation =
        delegate?.evaluate(flag)
            ?: FlagEvaluation(flag, buildDefault(flag), FlagValueSource.BUILD_DEFAULT)

    @VisibleForTesting
    internal fun setDelegateForTests(repository: FeatureFlagRepository?) {
        delegate = repository
    }
}
