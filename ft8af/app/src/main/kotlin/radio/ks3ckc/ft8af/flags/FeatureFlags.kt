package radio.ks3ckc.ft8af.flags

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.k1af.ft8af.BuildConfig
import kotlinx.coroutines.CancellationException
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

    @Volatile
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
     * Refresh the remote configuration now if one is due (internally
     * rate-limited). Suspends for the fetch; never throws anything but
     * cancellation. Used directly by background work that must evaluate
     * flags against a current config rather than an expired cache.
     */
    suspend fun refreshRemoteConfig() {
        val f = fetcher ?: return
        try {
            f.refreshIfDue()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Remote config must never interfere with normal operation.
        }
    }

    /**
     * Kick a background refresh of the remote configuration if one is due.
     * Never blocks, never throws into the caller. [onComplete] runs on the
     * background thread after the attempt finishes (whether or not anything
     * changed), so callers can reconcile state that depends on the refreshed
     * flags, e.g. propagation-alert scheduling.
     */
    fun refreshRemoteConfigAsync(onComplete: () -> Unit = {}) {
        scope.launch {
            refreshRemoteConfig()
            try {
                onComplete()
            } catch (_: Exception) {
                // A failing reconcile must not crash the refresh scope.
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

    @VisibleForTesting
    internal fun setFetcherForTests(remoteFetcher: RemoteFlagConfigFetcher?) {
        fetcher = remoteFetcher
    }
}
