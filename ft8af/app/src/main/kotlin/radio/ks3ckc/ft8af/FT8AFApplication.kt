package radio.ks3ckc.ft8af

import android.app.Application
import radio.ks3ckc.ft8af.crash.CrashReporting

/**
 * Process-wide entry point. Exists so opt-in Sentry crash reporting can be
 * installed in [onCreate] — before any Activity/Service starts — which is the
 * only place an uncaught-exception handler catches early-startup crashes.
 *
 * [CrashReporting.init] is a no-op unless the user opted in and a DSN was
 * compiled in, so this stays inert for contributor builds and un-consented users.
 */
class FT8AFApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporting.init(this)
    }
}
