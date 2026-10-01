package radio.ks3ckc.ft8af.flags

/** Read side of the flag system — what feature code consults. */
interface FeatureFlagRepository {
    fun isEnabled(flag: FeatureFlag): Boolean

    /** [isEnabled] plus which layer supplied the value (for the debug screen). */
    fun evaluate(flag: FeatureFlag): FlagEvaluation
}

/** Persistence for developer overrides (only consulted in debug builds). */
interface FlagOverrideStore {
    fun overrideFor(flag: FeatureFlag): FlagOverride
    fun setOverride(flag: FeatureFlag, override: FlagOverride)
}

/** Persistence for the last valid remote configuration. */
interface RemoteFlagConfigStore {
    /** The cached config, already validated at save time, or null. */
    fun cached(): RemoteFlagConfig?

    /** Cache a payload that already passed [parseRemoteFlagConfig]. */
    fun save(rawJson: String, fetchedAtMs: Long)

    /** Epoch ms of the last fetch attempt (success or failure), 0 if never. */
    fun lastAttemptMs(): Long
    fun recordAttempt(nowMs: Long)
}

/**
 * Deterministic flag resolution. Precedence, highest first:
 *
 *  1. Debug developer override — debug builds only ([isDebugBuild]); a release
 *     build never consults the override store, so a leftover forced value can't
 *     leak into production behavior.
 *  2. Cached remote configuration — only while the payload itself is fresh
 *     (its `expiresAt` has not passed) and it actually contains the flag.
 *  3. Build-time default from BuildConfig.
 *
 * All inputs are injected so tests can drive every layer with a fixed clock.
 */
class DefaultFeatureFlagRepository(
    private val isDebugBuild: Boolean,
    private val buildDefaults: (FeatureFlag) -> Boolean,
    private val overrideStore: FlagOverrideStore,
    private val remoteConfigStore: RemoteFlagConfigStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FeatureFlagRepository {

    override fun isEnabled(flag: FeatureFlag): Boolean = evaluate(flag).enabled

    override fun evaluate(flag: FeatureFlag): FlagEvaluation {
        if (isDebugBuild) {
            when (overrideStore.overrideFor(flag)) {
                FlagOverride.FORCED_ON ->
                    return FlagEvaluation(flag, true, FlagValueSource.DEBUG_OVERRIDE)
                FlagOverride.FORCED_OFF ->
                    return FlagEvaluation(flag, false, FlagValueSource.DEBUG_OVERRIDE)
                FlagOverride.DEFAULT -> Unit
            }
        }
        val remote = remoteConfigStore.cached()
        if (remote != null && remote.isFresh(clock())) {
            val value = remote.valueFor(flag)
            if (value != null) {
                return FlagEvaluation(flag, value, FlagValueSource.REMOTE_CONFIG)
            }
        }
        return FlagEvaluation(flag, buildDefaults(flag), FlagValueSource.BUILD_DEFAULT)
    }
}
