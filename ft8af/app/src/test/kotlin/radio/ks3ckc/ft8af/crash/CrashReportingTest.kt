package radio.ks3ckc.ft8af.crash

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.BuildConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the crash-reporting gate ([CrashReporting.shouldEnable]) and the opt-in
 * persistence. Robolectric is only needed for the SharedPreferences-backed paths;
 * the truth-table cases are pure logic that happen to run under the same runner.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReportingTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // --- shouldEnable: the single gate. Enabled only when opted in AND a DSN exists. ---

    @Test
    fun shouldEnable_trueOnlyWhenOptedInAndDsnPresent() {
        assertThat(CrashReporting.shouldEnable("https://k@o0.ingest.sentry.io/1", true)).isTrue()
    }

    @Test
    fun shouldEnable_falseWhenNotOptedIn() {
        assertThat(CrashReporting.shouldEnable("https://k@o0.ingest.sentry.io/1", false)).isFalse()
    }

    @Test
    fun shouldEnable_falseWhenDsnNull() {
        assertThat(CrashReporting.shouldEnable(null, true)).isFalse()
    }

    @Test
    fun shouldEnable_falseWhenDsnEmpty() {
        assertThat(CrashReporting.shouldEnable("", true)).isFalse()
    }

    @Test
    fun shouldEnable_falseWhenDsnBlankWhitespace() {
        assertThat(CrashReporting.shouldEnable("   ", true)).isFalse()
    }

    // --- opt-in persistence ---

    @Test
    fun optIn_defaultsToOff() {
        assertThat(CrashReporting.isOptedIn(context)).isFalse()
    }

    @Test
    fun setEnabled_persistsTheChoice() {
        CrashReporting.setEnabled(context, true)
        assertThat(CrashReporting.isOptedIn(context)).isTrue()

        CrashReporting.setEnabled(context, false)
        assertThat(CrashReporting.isOptedIn(context)).isFalse()
    }

    // --- build-time availability + safety of the disabled paths ---

    @Test
    fun isAvailable_reflectsCompiledInDsn() {
        // isAvailable() must exactly track whether a DSN was compiled in — regardless
        // of whether this particular build has one. SENTRY_DSN comes from a Gradle
        // prop / env var, so it may be blank (typical) or present (local/CI); either
        // way isAvailable() equals SENTRY_DSN.isNotBlank().
        assertThat(CrashReporting.isAvailable()).isEqualTo(BuildConfig.SENTRY_DSN.isNotBlank())
    }

    @Test
    fun breadcrumb_isNoOpWhenReportingDisabled() {
        // With the SDK uninitialised, breadcrumb() must swallow rather than throw
        // so every fileLog() caller is safe without its own guard.
        CrashReporting.breadcrumb("late-start skip 320ms")
    }

    // --- manifest wiring: Sentry's auto-init ContentProvider must be OFF ---

    @Test
    fun manifest_disablesSentryAutoInit() {
        // The SDK bundles a SentryInitProvider that inits at process start and, with
        // no manifest DSN, crashes on launch ("DSN is required"). We init manually and
        // only after opt-in, so the manifest MUST carry io.sentry.auto-init=false.
        // Re-adding it (or dropping this meta-data) reintroduces a boot crash.
        val appInfo =
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
        assertThat(appInfo.metaData.getBoolean("io.sentry.auto-init", true)).isFalse()
    }
}
