package radio.ks3ckc.ft8af.crash

import android.content.Context
import com.k1af.ft8af.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * Opt-in Sentry crash reporting.
 *
 * Reporting is off until BOTH of these hold:
 *   1. a DSN was compiled in (BuildConfig.SENTRY_DSN, supplied at build time — a
 *      contributor build with no DSN can never report), and
 *   2. the user turned on "Send crash reports" in Advanced Settings.
 *
 * The user's choice lives in a tiny [android.content.SharedPreferences] file (not
 * the config DB) so it can be read in [Application.onCreate][android.app.Application.onCreate]
 * — before the DB is open — which is where an uncaught-exception handler has to be
 * installed to catch early crashes.
 *
 * This app knows the operator's callsign and GPS/grid location, so PII is never
 * attached automatically (`isSendDefaultPii = false`) and only [breadcrumb]s we
 * explicitly hand it (the same lines that go to debug.log) ride along with a crash.
 */
object CrashReporting {
    private const val PREFS_NAME = "crash_reporting"
    private const val KEY_OPT_IN = "sentry_opt_in"

    /**
     * The single gate for whether crash reporting may run: the user opted in AND a
     * DSN is configured. Pure logic, unit-tested — everything else defers to it.
     */
    internal fun shouldEnable(
        dsn: String?,
        optedIn: Boolean,
    ): Boolean = optedIn && !dsn.isNullOrBlank()

    /** True when a DSN was compiled in, i.e. crash reporting is offerable at all. */
    fun isAvailable(): Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    /** The persisted user opt-in choice (defaults to off — reporting is opt-in). */
    fun isOptedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OPT_IN, false)

    private fun saveOptIn(
        context: Context,
        optedIn: Boolean,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OPT_IN, optedIn)
            .apply()
    }

    /**
     * Called once from [FT8AFApplication.onCreate][radio.ks3ckc.ft8af.FT8AFApplication].
     * A no-op unless the user has opted in and a DSN is present.
     */
    fun init(context: Context) {
        if (shouldEnable(BuildConfig.SENTRY_DSN, isOptedIn(context))) {
            start(context)
        }
    }

    /**
     * Persist the Settings toggle and start/stop the SDK live so the change takes
     * effect without an app restart. Turning it off calls [Sentry.close]; turning
     * it on with no DSN compiled in just records the preference.
     */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        saveOptIn(context, enabled)
        if (shouldEnable(BuildConfig.SENTRY_DSN, enabled)) {
            start(context)
        } else {
            Sentry.close()
        }
    }

    /** Bring the SDK up. Idempotent — a second call while already running is ignored. */
    private fun start(context: Context) {
        if (Sentry.isEnabled()) return
        SentryAndroid.init(context) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            // Callsign + GPS live in this app; never let the SDK attach IP / user
            // identifiers on its own. Only breadcrumbs we choose are sent.
            options.isSendDefaultPii = false
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.isDebug = BuildConfig.DEBUG
        }
    }

    /**
     * Record a breadcrumb — the timeline of events attached to the next crash. Fed
     * the same lines as debug.log (see ComposeMainActivity.fileLog). A no-op when
     * reporting is disabled, so callers need no guard of their own.
     */
    fun breadcrumb(message: String) {
        if (!Sentry.isEnabled()) return
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                this.message = message
                level = SentryLevel.INFO
                category = "app"
            },
        )
    }
}
