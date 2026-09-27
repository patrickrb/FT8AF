package radio.ks3ckc.ft8af.bandadvisor

import android.content.Context
import android.content.SharedPreferences
import com.k1af.ft8af.BuildConfig
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal

/**
 * Manual-singleton wiring for Band Advisor (matches the app's no-DI pattern):
 * owns the repository choice, the persisted goal, and the local caches.
 */
object BandAdvisor {
    private const val PREFS_NAME = "ft8af_band_advisor"
    private const val KEY_GOAL = "goal"
    private const val KEY_USE_FIXTURES = "use_fixtures"
    private const val KEY_CACHED_RECOMMENDATION = "cached_recommendation_json"

    @Volatile
    private var repository: BandAdvisorRepository? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The active repository. Fixture data is a DEBUG-ONLY development aid:
     * the toggle is invisible and ignored in release builds, so fabricated
     * recommendations can never be presented as live data to users.
     */
    fun repository(context: Context): BandAdvisorRepository {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val repo = if (BuildConfig.DEBUG && useFixtures(context)) {
                FixtureBandAdvisorRepository()
            } else {
                ApiBandAdvisorRepository(SharedPrefsRecommendationCacheStore(prefs(context)))
            }
            repository = repo
            return repo
        }
    }

    /** Debug toggle: serve deterministic fixture data instead of the API. */
    fun useFixtures(context: Context): Boolean =
        BuildConfig.DEBUG && prefs(context).getBoolean(KEY_USE_FIXTURES, false)

    fun setUseFixtures(context: Context, value: Boolean) {
        if (!BuildConfig.DEBUG) return
        prefs(context).edit().putBoolean(KEY_USE_FIXTURES, value).apply()
        synchronized(this) { repository = null } // Rebuild with the new source.
    }

    /** The operator's selected goal, persisted across sessions. */
    fun goal(context: Context): OperatingGoal =
        OperatingGoal.fromWireName(prefs(context).getString(KEY_GOAL, null))

    fun setGoal(context: Context, goal: OperatingGoal) {
        prefs(context).edit().putString(KEY_GOAL, goal.wireName).apply()
    }

    /** Clear locally cached recommendation + personal analytics (privacy). */
    fun clearLocalData(context: Context) {
        repository(context).clearCache()
        prefs(context).edit().remove(KEY_CACHED_RECOMMENDATION).apply()
        radio.ks3ckc.ft8af.bandadvisor.personal.PersonalAnalyticsStore.clear(context)
    }

    /** Prefs-backed cache so the last recommendation survives restarts. */
    private class SharedPrefsRecommendationCacheStore(
        private val prefs: SharedPreferences,
    ) : RecommendationCacheStore {
        override fun load(): String? = prefs.getString(KEY_CACHED_RECOMMENDATION, null)
        override fun save(json: String) {
            prefs.edit().putString(KEY_CACHED_RECOMMENDATION, json).apply()
        }
        override fun clear() {
            prefs.edit().remove(KEY_CACHED_RECOMMENDATION).apply()
        }
    }
}
